/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.content.Context;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

class WifiQualifiedNetworkSelector {
    private WifiScanner mScanner;
    private WifiStateMachine mWifiStateMachine;
    private WifiConfigStore mWifiConfigStore;
    private WifiInfo mWifiInfo;
    private NetworkScoreManager mScoreManager;
    private WifiNetworkScoreCache mNetworkScoreCache;
    private static final String TAG = "WifiQualifiedNetworkSelector:";
    private boolean mDbg = true;
    private WifiConfiguration mCurrentConnectedNetwork = null;
    private String mCurrentBssid = null;
    //buffer most recent scan results
    private List<ScanDetail> mScanDetails = null;

    private Map<String, Integer> mBssidBlackList =  new HashMap<String, Integer>();
    //Minimum time gap between last successful Qualified Network Selection and new selection attempt
    //usable only when current state is connected state   default 10 s
    private static final int MINIMUM_QUALIFIED_NETWORK_SELECTION_INTERVAL = 10 * 1000;

    //if current network is on 2.4GHz band and has a RSSI over this, need not new network selection
    public static final int QUALIFIED_RSSI_24G_BAND = -73;
    //if current network is on 5GHz band and has a RSSI over this, need not new network selection
    public static final int QUALIFIED_RSSI_5G_BAND = -70;
    //any RSSI larger than this will benefit the traffic very limited
    public static final int RSSI_SATURATION_2G_BAND = -60;
    public static final int RSSI_SATURATION_5G_BAND = -57;
    //Any value below this will be considered not usable
    public static final int MINIMUM_2G_ACCEPT_RSSI = -85;
    public static final int MINIMUM_5G_ACCEPT_RSSI = -82;

    private static final int RSSI_SCORE_SLOPE = 4;
    private static final int RSSI_SCORE_OFFSET = 85;

    public static final int BAND_AWARD_5GHz = 40;
    public static final int SAME_NETWORK_AWARD = 16;

    private static final int SAME_BSSID_AWARD = 24;
    private static final int LAST_SELECTION_AWARD = 480;
    private static final int PASSPOINT_SECURITY_AWARD = 40;
    private static final int SECURITY_AWARD = 80;
    private final int mNoIntnetPenalty;
    //TODO: check whether we still need this one when we update the scan manager
    public static final int SCAN_RESULT_MAXIMUNM_AGE = 40000;
    private static final int INVALID_TIME_STAMP = -1;
    private long mLastQualifiedNetworkSelectionTimeStamp = INVALID_TIME_STAMP;

    // Temporarily, for dog food
    private final LocalLog mLocalLog = new LocalLog(16384);
    private int mRssiScoreSlope = RSSI_SCORE_SLOPE;
    private int mRssiScoreOffset = RSSI_SCORE_OFFSET;
    private int mSameBssidAward = SAME_BSSID_AWARD;
    private int mLastSelectionAward = LAST_SELECTION_AWARD;
    private int mPasspointSecurityAward = PASSPOINT_SECURITY_AWARD;
    private int mSecurityAward = SECURITY_AWARD;
    private boolean mAllowUntrustedConnections = false;
    private int mUserPreferedBand = WifiManager.WIFI_FREQUENCY_BAND_AUTO;

    private void qnsLog(String log) {
        if (mDbg) {
            mLocalLog.log(log);
        }
    }

    private void qnsLoge(String log) {
        mLocalLog.log(log);
    }

    /**
     * @return current target connected network
     */
    public WifiConfiguration getConnetionTargetNetwork() {
        return mCurrentConnectedNetwork;
    }

    /**
     * set the user selected preferred band
     * @param band
     */
    public void setUserPreferedBand(int band) {
        mUserPreferedBand = band;
    }

    WifiQualifiedNetworkSelector(WifiConfigStore configureStore, Context context,
                                            WifiStateMachine stateMachine, WifiInfo wifiInfo) {
        mWifiConfigStore = configureStore;
        mWifiStateMachine = stateMachine;
        mWifiInfo = wifiInfo;

                context.getSystemService(Context.NETWORK_SCORE_SERVICE);
        if (mScoreManager != null) {
            mNetworkScoreCache = new WifiNetworkScoreCache(context);
            mScoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache);
        } else {
            qnsLoge("No network score service: Couldn't register as a WiFi score Manager, type="
                    + NetworkKey.TYPE_WIFI + " service= " + Context.NETWORK_SCORE_SERVICE);
            mNetworkScoreCache = null;
        }

        mRssiScoreSlope = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE);
        mRssiScoreOffset = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET);
        mSameBssidAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD);
        mLastSelectionAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_LAST_SELECTION_AWARD);
        mPasspointSecurityAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_PASSPOINT_SECURITY_AWARD);
        mSecurityAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD);
        mNoIntnetPenalty = (mWifiConfigStore.thresholdSaturatedRssi24.get() + mRssiScoreOffset)
                * mRssiScoreSlope + mWifiConfigStore.bandAward5Ghz.get()
                + mWifiConfigStore.currentNetworkBoost.get() + mSameBssidAward + mSecurityAward;
    }

    void enableVerboseLogging(int verbose) {
        mDbg = verbose > 0;
    }

    /**
     * Set whether connections to untrusted connections are allowed.
     */
    void setAllowUntrustedConnections(boolean allow) {
        boolean changed = (mAllowUntrustedConnections != allow);
        mAllowUntrustedConnections = allow;
        if (changed) {
            // Trigger a scan so as to reattempt autojoin
            mWifiStateMachine.startScanForUntrustedSettingChange();
        }
    }

    private String getNetworkString(WifiConfiguration network) {
        if (network == null) {
            return null;
        }

        return (network.SSID + ":" + network.networkId);

    }

    void enableNetworkByUser(WifiConfiguration network) {
        if (network != null) {
            mWifiConfigStore.updateNetworkSelectionStatus(network,
                    WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
            mWifiConfigStore.setLatestUserSelectedConfiguration(network);
        }
    }

    /**
     * check whether current network is good enough we need not consider any potential switch
     * @param currentNetwork -- current connected network
     * @return true -- qualified and do not consider potential network switch
     *         false -- not good enough and should try potential network switch
     */
    private boolean isNetworkQualified(WifiConfiguration currentNetwork) {

        if (currentNetwork == null) {
            qnsLog("Disconnected");
            return false;
        } else {
            qnsLog("Current network is: " + currentNetwork.SSID + " ,ID is: "
                    + currentNetwork.networkId);
        }

        //if current connected network is an ephemeral network,we will consider
        // there is no current network
        if (currentNetwork.ephemeral) {
            qnsLog("Current is ephemeral. Start reselect");
            return false;
        }

        //if current network is open network, not qualified
        if (mWifiConfigStore.isOpenNetwork(currentNetwork)) {
            qnsLog("Current network is open network");
            return false;
        }

        // Current network band must match with user preference selection
        if (mWifiInfo.is24GHz() && (mUserPreferedBand != WifiManager.WIFI_FREQUENCY_BAND_2GHZ)) {
            qnsLog("Current band dose not match user preference. Start Qualified Network"
                    + " Selection Current band = " + (mWifiInfo.is24GHz() ? "2.4GHz band"
                    : "5GHz band") + "UserPreference band = " + mUserPreferedBand);
            return false;
        }

        int currentRssi = mWifiInfo.getRssi();
        if ((mWifiInfo.is24GHz() && currentRssi < QUALIFIED_RSSI_24G_BAND) || (mWifiInfo.is5GHz()
                && currentRssi < mWifiConfigStore.thresholdQualifiedRssi5.get())) {
            qnsLog("Current band = " + (mWifiInfo.is24GHz() ? "2.4GHz band" : "5GHz band")
                    + "current RSSI is: " + currentRssi);
            return false;
        }

        return true;
    }

    /*
     * check whether QualifiedNetworkSelection is needed or not
     * return - true need a Qualified Network Selection procedure
     *        - false do not need a QualifiedNetworkSelection procedure
     */
    private boolean needQualifiedNetworkSelection() {
        //TODO:This should be called in scan result call back in the future
        mScanDetails = mWifiStateMachine.getScanResultsListNoCopyUnsync();
        if (mScanDetails.size() == 0) {
            qnsLog("empty scan result");
            return false;
        }

        // Do not trigger Qualified Network Selection during L2 link debouncing procedure
        if (mWifiStateMachine.isLinkDebouncing()) {
            qnsLog("Need not Qualified Network Selection during L2 debouncing");
            return false;
        }

        if (mWifiStateMachine.isConnected()) {
            //already connected. Just try to find better candidate
            //if switch network is not allowed in connected mode, do not trigger Qualified Network
            //Selection
            if (!mWifiConfigStore.getEnableNewNetworkSelectionWhenAssociated()) {
                qnsLog("Switch network under connection is not allowed");
                return false;
            }

            //Do not select again if last selection is within
            //MINIMUM_QUALIFIED_NETWORK_SELECTION_INTERVAL
            if (mLastQualifiedNetworkSelectionTimeStamp != INVALID_TIME_STAMP) {
                long gap = System.currentTimeMillis() - mLastQualifiedNetworkSelectionTimeStamp;
                if (gap < MINIMUM_QUALIFIED_NETWORK_SELECTION_INTERVAL) {
                    qnsLog("Too short to last successful Qualified Network Selection Gap is:" + gap
                            + " ms!");
                    return false;
                }
            }

            WifiConfiguration currentNetwork =
                    mWifiConfigStore.getWifiConfiguration(mWifiInfo.getNetworkId());
            if (mCurrentConnectedNetwork != null && mCurrentConnectedNetwork.networkId
                    != currentNetwork.networkId) {
                //If this happens, supplicant switch the connection silently. This is a bug
                // FIXME: 11/10/15
                qnsLoge("supplicant switched the network silently" + " last Qualified Network"
                        + " Selection:" + getNetworkString(mCurrentConnectedNetwork)
                        + " current network:" + getNetworkString(currentNetwork));
                mCurrentConnectedNetwork = currentNetwork;
                mCurrentBssid = mWifiInfo.getBSSID();
                //We do not believe lower layer choice
                return true;
            }

            String bssid = mWifiInfo.getBSSID();
            if (mCurrentBssid != null && !mCurrentBssid.equals(bssid)) {
                //If this happens, supplicant roamed silently. This is a bug
                // FIXME: 11/10/15
                qnsLoge("supplicant roamed silently. Last selected BSSID:" + mCurrentBssid
                        + " current BSSID:" + bssid);
                mCurrentBssid = mWifiInfo.getBSSID();
                //We do not believe lower layer choice
                return true;
            }

            if (!isNetworkQualified(mCurrentConnectedNetwork)) {
                //need not trigger Qualified Network Selection if current network is qualified
                qnsLog("Current network is not qualified");
                return true;
            } else {
                return false;
            }
        } else if (mWifiStateMachine.isDisconnected()) {
            mCurrentConnectedNetwork = null;
            mCurrentBssid = null;
            //Do not start Qualified Network Selection if current state is a transient state
            if (mWifiStateMachine.isSupplicantTransientState()) {
                return false;
            }
        } else {
            //Do not allow new network selection in other state
            qnsLog("WifiStateMachine is not on connected or disconnected state");
            return false;
        }

        return true;
    }

    int calculateBssidScore(ScanResult scanResult, WifiConfiguration network,
            WifiConfiguration currentNetwork, boolean sameBssid, boolean sameSelect,
            StringBuffer sbuf) {

        int score = 0;
        //calculate the RSSI score
        int rssi = scanResult.level <= mWifiConfigStore.thresholdSaturatedRssi24.get()
                ? scanResult.level : mWifiConfigStore.thresholdSaturatedRssi24.get();
        score += (rssi + mRssiScoreOffset) * mRssiScoreSlope;
        sbuf.append(" RSSI score: " +  score);
        if (scanResult.is5GHz()) {
            //5GHz band
            score += mWifiConfigStore.bandAward5Ghz.get();
            sbuf.append(" 5GHz bonus: " + mWifiConfigStore.bandAward5Ghz.get());
        }

        //last user selection award
        if (sameSelect) {
            long timeDifference = System.currentTimeMillis()
                    - mWifiConfigStore.getLastSelectedTimeStamp();

            if (timeDifference > 0) {
                int bonus = mLastSelectionAward - (int) (timeDifference / 1000 / 60);
                score += bonus > 0 ? bonus : 0;
                sbuf.append(" User selected it last time " + (timeDifference / 1000 / 60)
                        + " minutes ago, bonus:" + bonus);
            }
        }

        //same network award
        if (network == currentNetwork || network.isLinked(currentNetwork)) {
            score += mWifiConfigStore.currentNetworkBoost.get();
            sbuf.append(" Same network with current associated. Bonus: "
                    + mWifiConfigStore.currentNetworkBoost.get());
        }

        //same BSSID award
        if (sameBssid) {
            score += mSameBssidAward;
            sbuf.append(" Same BSSID with current association. Bonus: " + mSameBssidAward);
        }

        //security award
        if (!TextUtils.isEmpty(network.FQDN)) {
            score += mPasspointSecurityAward;
            sbuf.append(" Passpoint Bonus:" + mPasspointSecurityAward);
        } else if (!mWifiConfigStore.isOpenNetwork(network)) {
            score += mSecurityAward;
            sbuf.append(" Secure network Bonus:" + mSecurityAward);
        }

        //Penalty for no internet network. Make sure if there is any network with Internet,
        //however, if there is no any other network with internet, this network can be chosen
        if (network.numNoInternetAccessReports > 0 && !network.validatedInternetAccess) {
            score -= mNoIntnetPenalty;
            sbuf.append(" No internet Penalty:-" + mNoIntnetPenalty);
        }

        if (mDbg) {
            sbuf.append("\n" + TAG + "Score for scanresult: " + scanResult +  " and Network ID: "
                    + network.networkId + " final score:" + score + "\n");
        }
        return score;
    }

    /**
     * This API try to update all the saved networks' network selection status
     */
    private void updateSavedNetworkSelectionStatus() {
        List<WifiConfiguration> savedNetworks = mWifiConfigStore.getConfiguredNetworks();
        if (savedNetworks.size() == 0) {
            qnsLog("no saved network");
            return;
        }

        if (mDbg) {
            StringBuffer sbuf = new StringBuffer("Saved Network List\n");
            for (WifiConfiguration network : savedNetworks) {
                WifiConfiguration.NetworkSelectionStatus networkStatus =
                        network.getNetworkSelectionStatus();
                sbuf.append("    " + getNetworkString(network) + " "
                        + networkStatus.getNetworkStatusString() + " Disable account: ");
                for (int index = networkStatus.NETWORK_SELECTION_ENABLE;
                        index < networkStatus.NETWORK_SELECTION_DISABLED_MAX; index++) {
                    sbuf.append(networkStatus.getDisableReasonCounter(index) + " ");
                }
                sbuf.append("\n");
            }
            qnsLog(sbuf.toString());
        }
    }

    /**
     * Roaming to a new AP. This means the new AP belong to the same network with the current
     * connection
     * @param scanResultCandidate : new AP to roam to
     */
    private void roamToNewAp(ScanResult scanResultCandidate) {
        String currentAssociationId = (mCurrentConnectedNetwork == null ? "Disconnected" :
                mCurrentConnectedNetwork.SSID + ":" + mCurrentBssid);
        String targetAssociationId = scanResultCandidate.SSID + ":" + scanResultCandidate.BSSID;

        qnsLog("Roaming from " + currentAssociationId + " to " + targetAssociationId);
        // the third parameter 0 means roam from Network Selection
        mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_ROAM,
                    mCurrentConnectedNetwork.networkId, 0, scanResultCandidate);
        mCurrentBssid = scanResultCandidate.BSSID;
    }

    /**
     * Connect to a new AP.This means the new AP belong to a different network with the current
     * connection
     */
    private void connectToNewAp(WifiConfiguration newNetworkCandidate, String newBssid) {
        // FIXME: 11/12/15 need fix auto_connect wifisatetmachine codes
        String currentAssociationId = (mCurrentConnectedNetwork == null ? "Disconnected" :
                mCurrentConnectedNetwork.SSID + ":" + mCurrentBssid);
        String targetAssociationId = newNetworkCandidate.SSID + ":" + newBssid;

        qnsLog("reconnect from " + currentAssociationId + " to " + targetAssociationId);
        // the third parameter 0 means connect request from Network Selection
        mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_CONNECT,
                newNetworkCandidate.networkId, 0, newBssid);
        mCurrentBssid = newBssid;
        mCurrentConnectedNetwork = newNetworkCandidate;
    }

    /**
     * check whether a network slection is needed. If need, check all the new scan results and
     * select a new qualified network/BSSID to connect to
     * @param forceSelectNetwork if this one is true, start a qualified network selection anyway,
     *                           no matter current network is already qualified or not.
     */
    public void selectQualifiedNetwork(boolean forceSelectNetwork) {
        qnsLog("==========start qualified Network Selection==========");

        if (mCurrentConnectedNetwork == null) {
            mCurrentConnectedNetwork =
                    mWifiConfigStore.getWifiConfiguration(mWifiInfo.getNetworkId());
        }

        if (mCurrentBssid == null) {
            mCurrentBssid = mWifiInfo.getBSSID();
        }

        if (!forceSelectNetwork && !needQualifiedNetworkSelection()) {
            qnsLog("Quit qualified Network Selection since it is not forced and current network is"
                    + " qualified already");
            return;
        }

        int currentHighestScore = Integer.MIN_VALUE;
        ScanResult scanResultCandidate = null;
        WifiConfiguration networkCandidate = null;
        int unTrustedHighestScore = WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        ScanResult untrustedScanResultCandidate = null;
        WifiConfiguration unTrustedNetworkCandidate = null;
        String lastUserSelectedNetWorkKey = mWifiConfigStore.getLastSelectedConfiguration();
        WifiConfiguration lastUserSelectedNetwork =
                mWifiConfigStore.getWifiConfiguration(lastUserSelectedNetWorkKey);
        if (lastUserSelectedNetwork != null) {
            qnsLog("Last selection is " + lastUserSelectedNetwork.SSID + " Time to now: "
                    + ((System.currentTimeMillis() - mWifiConfigStore.getLastSelectedTimeStamp())
                            / 1000 / 60 + " minutes"));
        }

        updateSavedNetworkSelectionStatus();

        StringBuffer lowSignalScan = new StringBuffer();
        StringBuffer notSavedScan = new StringBuffer();
        StringBuffer noValidSsid = new StringBuffer();
        StringBuffer scoreHistory =  new StringBuffer();

        //iterate all scan results and find the best candidate with the highest score
        for (ScanDetail scanDetail : mScanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();
            //skip bad scan result
            if (scanResult.SSID == null || TextUtils.isEmpty(scanResult.SSID)) {
                if (mDbg) {
                    //We should not see this in ePNO
                    noValidSsid.append(scanResult.BSSID + " / ");
                }
                continue;
            }

            String scanId = scanResult.SSID + ":" + scanResult.BSSID;
            //check whether this BSSID is blocked or not
            if (mWifiConfigStore.isBssidBlacklisted(scanResult.BSSID)) {
                //We should not see this in ePNO
                Log.e(TAG, scanId + " is in blacklist.");
                continue;
            }

            //skip scan result with too weak signals
            if ((scanResult.is24GHz() && scanResult.level
                    < mWifiConfigStore.thresholdMinimumRssi24.get())
                    || (scanResult.is5GHz() && scanResult.level
                    < mWifiConfigStore.thresholdMinimumRssi5.get())) {
                if (mDbg) {
                    lowSignalScan.append(scanId + "(" + (scanResult.is24GHz() ? "2.4GHz" : "5GHz")
                            + ")" + scanResult.level + " / ");
                }
                continue;
            }

            //check whether this scan result belong to a saved network
            boolean ephemeral = false;
            List<WifiConfiguration> associatedWifiConfigurations =
                    mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetail);
            if (associatedWifiConfigurations == null) {
                ephemeral =  true;
                if (mDbg) {
                    notSavedScan.append(scanId + " / ");
                }
            } else if (associatedWifiConfigurations.size() == 1) {
                //if there are more than 1 associated network, it must be a passpoint network
                WifiConfiguration network = associatedWifiConfigurations.get(0);
                if (network.ephemeral) {
                    ephemeral =  true;
                }
            }

            if (ephemeral) {
                if (mAllowUntrustedConnections) {
                    int netScore = mNetworkScoreCache.getNetworkScore(scanResult, false);
                    //get network score
                    if (netScore != WifiNetworkScoreCache.INVALID_NETWORK_SCORE) {
                        qnsLog(scanId + "has score: " + netScore);
                        if (netScore > unTrustedHighestScore) {
                            unTrustedHighestScore = netScore;
                            untrustedScanResultCandidate = scanResult;
                            qnsLog(scanId + " become the new untrusted candidate");
                        }
                    }
                }
                continue;
            }

            // calculate the core of each scanresult whose associated network is not ephemeral. Due
            // to one scane result can associated with more than 1 network, we need calcualte all
            // the scores and use the highest one as the scanresults score
            int highestScore = Integer.MIN_VALUE;
            int score;
            WifiConfiguration configurationCandidateForThisScan = null;

            for (WifiConfiguration network : associatedWifiConfigurations) {
                if (!network.getNetworkSelectionStatus().isNetworkEnabled()) {
                    continue;
                }
                score = calculateBssidScore(scanResult, network, mCurrentConnectedNetwork,
                        (mCurrentBssid == null ? false : mCurrentBssid.equals(scanResult.BSSID)),
                        (lastUserSelectedNetwork == null ? false : lastUserSelectedNetwork.networkId
                         == network.networkId), scoreHistory);
                if (score > highestScore) {
                    highestScore = score;
                    configurationCandidateForThisScan = network;
                }
            }

            if (highestScore > currentHighestScore || (highestScore == currentHighestScore
                    && scanResultCandidate != null
                    && scanResult.level > scanResultCandidate.level)) {
                currentHighestScore = highestScore;
                scanResultCandidate = scanResult;
                networkCandidate = configurationCandidateForThisScan;
            }
        }

        if (mDbg) {
            qnsLog(lowSignalScan + " skipped due to low signal\n");
            qnsLog(notSavedScan + " skipped due to not saved\n ");
            qnsLog(noValidSsid + " skipped due to not valid SSID\n");
            qnsLog(scoreHistory.toString());
        }

        // if we can not find scanCadidate in saved network
        if (scanResultCandidate == null && mAllowUntrustedConnections) {

            if (untrustedScanResultCandidate == null) {

                qnsLog("Can not find any candidate");
                return;
            }

            if (unTrustedNetworkCandidate == null) {
                unTrustedNetworkCandidate =
                        mWifiConfigStore.wifiConfigurationFromScanResult(
                                untrustedScanResultCandidate);

                unTrustedNetworkCandidate.ephemeral = true;
                mWifiConfigStore.saveNetwork(unTrustedNetworkCandidate,
                        WifiConfiguration.UNKNOWN_UID);


                qnsLog("new ephemeral candidate" + untrustedScanResultCandidate.SSID + ":"
                        + untrustedScanResultCandidate.BSSID + "network ID:"
                        + unTrustedNetworkCandidate.networkId);

            } else {
                qnsLog("choose existing ephemeral candidate" + untrustedScanResultCandidate.SSID
                        + ":" + untrustedScanResultCandidate.BSSID + "network ID:"
                        + unTrustedNetworkCandidate.networkId);
            }
            scanResultCandidate = untrustedScanResultCandidate;
            networkCandidate = unTrustedNetworkCandidate;

        }

        if (scanResultCandidate == null) {
            qnsLog("Can not find any suitable candidates");
            return;
        }
        String currentAssociationId = (mCurrentConnectedNetwork == null ? "Disconnected" :
                mCurrentConnectedNetwork.SSID + ":" + mCurrentBssid);

        //In passpoint, saved configuration has garbage SSID
        if (!TextUtils.isEmpty(networkCandidate.FQDN)) {
            // This will updateb the passpoint configuration in WifiConfigStore
            networkCandidate.SSID = "\"" + scanResultCandidate.SSID + "\"";
        }
        if (scanResultCandidate.BSSID.equals(mCurrentBssid)) {
            //current BSSID is best, need not change
            qnsLog(currentAssociationId + " is already the best choice!");
        } else if (mCurrentConnectedNetwork != null
                && (mCurrentConnectedNetwork.networkId == networkCandidate.networkId
                        || mCurrentConnectedNetwork.isLinked(networkCandidate))) {
            roamToNewAp(scanResultCandidate);
        } else {
            //select another network case Fix me
            // FIXME: 11/12/15 need fix auto_connect wifisatetmachine codes
            connectToNewAp(networkCandidate, scanResultCandidate.BSSID);
        }
        mLastQualifiedNetworkSelectionTimeStamp = System.currentTimeMillis();
        return;
    }

    //Dump the logs
    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of Quality Network Selection");
        pw.println(" - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println(" - Log End ----");
    }
}
