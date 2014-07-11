/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.net.WifiKey;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import android.os.SystemClock;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Date;

/**
 * AutoJoin controller is responsible for WiFi Connect decision
 *
 * It runs in the thread context of WifiStateMachine
 *
 */
public class WifiAutoJoinController {

    private Context mContext;
    private WifiStateMachine mWifiStateMachine;
    private WifiConfigStore mWifiConfigStore;
    private WifiTrafficPoller mWifiTrafficPoller;
    private WifiNative mWifiNative;

    private NetworkScoreManager scoreManager;
    private WifiNetworkScoreCache mNetworkScoreCache;

    private static final String TAG = "WifiAutoJoinController ";
    private static boolean DBG = false;
    private static boolean VDBG = false;
    private static final boolean mStaStaSupported = false;
    private static final int SCAN_RESULT_CACHE_SIZE = 80;

    private String mCurrentConfigurationKey = null; //used by autojoin

    private HashMap<String, ScanResult> scanResultCache =
            new HashMap<String, ScanResult>();

    //lose the non-auth failure blacklisting after 8 hours
    private final static long loseBlackListHardMilli = 1000 * 60 * 60 * 8;
    //lose some temporary blacklisting after 30 minutes
    private final static long loseBlackListSoftMilli = 1000 * 60 * 30;

    public static final int AUTO_JOIN_IDLE = 0;
    public static final int AUTO_JOIN_ROAMING = 1;
    public static final int AUTO_JOIN_EXTENDED_ROAMING = 2;
    public static final int AUTO_JOIN_OUT_OF_NETWORK_ROAMING = 3;

    WifiAutoJoinController(Context c, WifiStateMachine w, WifiConfigStore s,
                           WifiTrafficPoller t, WifiNative n) {
        mContext = c;
        mWifiStateMachine = w;
        mWifiConfigStore = s;
        mWifiTrafficPoller = t;
        mWifiNative = n;
        mNetworkScoreCache = null;
        scoreManager =
                (NetworkScoreManager) mContext.getSystemService(Context.NETWORK_SCORE_SERVICE);
        if (scoreManager == null)
            logDbg("Registered scoreManager NULL " + " service " + Context.NETWORK_SCORE_SERVICE);

        if (scoreManager != null) {
            mNetworkScoreCache = new WifiNetworkScoreCache(mContext);
            scoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache);
        } else {
            logDbg("No network score service: Couldnt register as a WiFi score Manager, type="
                    + Integer.toString(NetworkKey.TYPE_WIFI)
                    + " service " + Context.NETWORK_SCORE_SERVICE);
            mNetworkScoreCache = null;
        }
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0 ) {
            DBG = true;
            VDBG = true;
        } else {
            DBG = false;
            VDBG = false;
        }
    }

    int mScanResultMaximumAge = 30000; /* milliseconds unit */

    /*
     * flush out scan results older than mScanResultMaximumAge
     *
     * */
    private void ageScanResultsOut(int delay) {
        if (delay <= 0) {
            delay = mScanResultMaximumAge; //something sane
        }
        long milli = System.currentTimeMillis();
        if (VDBG) {
            logDbg("ageScanResultsOut delay " + Integer.valueOf(delay) + " size "
                    + Integer.valueOf(scanResultCache.size()) + " now " + Long.valueOf(milli));
        }

        Iterator<HashMap.Entry<String,ScanResult>> iter = scanResultCache.entrySet().iterator();
        while (iter.hasNext()) {
            HashMap.Entry<String,ScanResult> entry = iter.next();
            ScanResult result = entry.getValue();

            if ((result.seen + delay) < milli) {
                iter.remove();
            }
        }
    }

    void addToScanCache(List<ScanResult> scanList) {
        WifiConfiguration associatedConfig;

        ArrayList<NetworkKey> unknownScanResults = new ArrayList<NetworkKey>();

        for(ScanResult result: scanList) {
            if (result.SSID == null) continue;
            result.seen = System.currentTimeMillis();

            ScanResult sr = scanResultCache.get(result.BSSID);
            if (sr != null) {
                // if there was a previous cache result for this BSSID, average the RSSI values

                int previous_rssi = sr.level;
                long previously_seen_milli = sr.seen;

                /* average RSSI with previously seen instances of this scan result */
                int avg_rssi = result.level;

                if ((previously_seen_milli > 0)
                        && (previously_seen_milli < mScanResultMaximumAge/2)) {
                    /*
                    *
                    * previously_seen_milli = 0 => RSSI = 0.5 * previous_seen_rssi + 0.5 * new_rssi
                    *
                    * If previously_seen_milli is 15+ seconds old:
                    *      previously_seen_milli = 15000 => RSSI = new_rssi
                    *
                    */

                    double alpha = 0.5 - (double)previously_seen_milli
                            / (double)mScanResultMaximumAge;

                    avg_rssi = (int)((double)avg_rssi * (1-alpha) + (double)previous_rssi * alpha);
                }
                result.level = avg_rssi;

                //remove the previous Scan Result
                scanResultCache.remove(result.BSSID);
            } else {
                if (!mNetworkScoreCache.isScoredNetwork(result)) {
                    WifiKey wkey;
                    //TODO : find out how we can get there without a valid UTF-8 encoded SSID
                    //TODO: which will cause WifiKey constructor to fail
                    try {
                        wkey = new WifiKey("\"" + result.SSID + "\"", result.BSSID);
                    } catch (IllegalArgumentException e) {
                        logDbg("AutoJoinController: received badly encoded SSID=[" + result.SSID +
                                "] ->skipping this network");
                        wkey = null;
                    }
                    if (wkey != null) {
                        NetworkKey nkey = new NetworkKey(wkey);
                        //if we don't know this scan result then request a score to Herrevad
                        unknownScanResults.add(nkey);
                    }
                }
            }

            scanResultCache.put(result.BSSID, new ScanResult(result));

            //add this BSSID to the scanResultCache of the relevant WifiConfiguration
            associatedConfig = mWifiConfigStore.updateSavedNetworkHistory(result);

            //try to associate this BSSID to an existing Saved WifiConfiguration
            if (associatedConfig == null) {
                associatedConfig = mWifiConfigStore.associateWithConfiguration(result);
                if (associatedConfig != null && associatedConfig.SSID != null) {
                    if (VDBG) {
                        logDbg("addToScanCache save associated config "
                                + associatedConfig.SSID + " with " + associatedConfig.SSID);
                    }
                    mWifiStateMachine.sendMessage(WifiManager.SAVE_NETWORK, associatedConfig);
                }
            }
        }

        if (unknownScanResults.size() != 0) {
            NetworkKey[] newKeys =
                    unknownScanResults.toArray(new NetworkKey[unknownScanResults.size()]);
                //kick the score manager, we will get updated scores asynchronously
            scoreManager.requestScores(newKeys);
        }
    }

    void logDbg(String message) {
        logDbg(message, false);
    }

    void logDbg(String message, boolean stackTrace) {
        long now = SystemClock.elapsedRealtimeNanos();
        String ts = String.format("[%,d us] ", now / 1000);
        if (stackTrace) {
            Log.e(TAG, ts + message + " stack:"
                    + Thread.currentThread().getStackTrace()[2].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[3].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[4].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[5].getMethodName());
        } else {
            Log.e(TAG, ts + message);
        }
    }

    /* called directly from WifiStateMachine  */
    void newSupplicantResults() {
        List<ScanResult> scanList = mWifiStateMachine.syncGetScanResultsList();
        addToScanCache(scanList);
        ageScanResultsOut(mScanResultMaximumAge);
        if (DBG)
           logDbg("newSupplicantResults size=" + Integer.valueOf(scanResultCache.size()) );

        attemptAutoJoin();
        mWifiConfigStore.writeKnownNetworkHistory();

    }


    /* not used at the moment
     * should be a call back from WifiScanner HAL ??
     * this function is not hooked and working yet, it will receive scan results from WifiScanners
     * with the list of IEs,then populate the capabilities by parsing the IEs and inject the scan
     * results as normal.
     */
    void newHalScanResults() {
        List<ScanResult> scanList = null;//mWifiScanner.syncGetScanResultsList();
        String akm = WifiParser.parse_akm(null, null);
        logDbg(akm);
        addToScanCache(scanList);
        ageScanResultsOut(0);
        attemptAutoJoin();
        mWifiConfigStore.writeKnownNetworkHistory();
    }

    /* network link quality changed, called directly from WifiTrafficPoller,
    or by listening to Link Quality intent */
    void linkQualitySignificantChange() {
        attemptAutoJoin();
    }

    /*
     * compare a WifiConfiguration against the current network, return a delta score
     * If not associated, and the candidate will always be better
     * For instance if the candidate is a home network versus an unknown public wifi,
     * the delta will be infinite, else compare Kepler scores etc…
     * Negatve return values from this functions are meaningless per se, just trying to
     * keep them distinct for debug purpose (i.e. -1, -2 etc...)
     ***/
    private int compareNetwork(WifiConfiguration candidate) {
        if (candidate == null)
            return -3;

        WifiConfiguration currentNetwork = mWifiStateMachine.getCurrentWifiConfiguration();
        if (currentNetwork == null) {
           return 1000;
        }

        if (candidate.configKey(true).equals(currentNetwork.configKey(true))) {
            return -2;
        }

        int order = compareWifiConfigurations(currentNetwork, candidate);

        if (order > 0) {
            //ascending: currentNetwork < candidate
            return 10; //will try switch over to the candidate
        }

        return 0;
    }

    /**
     * update the network history fields fo that configuration
     * - if userTriggered, we mark the configuration as "non selfAdded" since the user has seen it
     * and took over management
     * - if it is a "connect", remember which network were there at the point of the connect, so
     * as those networks get a relative lower score than the selected configuration
     *
     * @param netId
     * @param userTriggered : if the update come from WiFiManager
     * @param connect : if the update includes a connect
     **/
    public void updateConfigurationHistory(int netId, boolean userTriggered, boolean connect) {
        WifiConfiguration selected = mWifiConfigStore.getWifiConfiguration(netId);
        if (selected == null) {
            return;
        }

        if (selected.SSID == null) {
            return;
        }

        if (userTriggered) {
            // reenable autojoin for this network,
            // since the user want to connect to this configuration
            selected.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
            selected.selfAdded = false;
        }

        if (DBG && userTriggered) {
            if (selected.connectChoices != null) {
                logDbg("updateConfigurationHistory will update "
                        + Integer.toString(netId) + " now: "
                        + Integer.toString(selected.connectChoices.size())
                        + " uid=" + Integer.toString(selected.creatorUid), true);
            } else {
                logDbg("updateConfigurationHistory will update "
                        + Integer.toString(netId)
                        + " uid=" + Integer.toString(selected.creatorUid), true);
            }
        }

        if (connect && userTriggered) {
            boolean found = false;
            List<WifiConfiguration> networks =
                    mWifiConfigStore.getRecentConfiguredNetworks(12000, false);
            if (networks != null) {
                for (WifiConfiguration config : networks) {
                    if (DBG) {
                        logDbg("updateConfigurationHistory got " + config.SSID + " nid="
                                + Integer.toString(config.networkId));
                    }

                    if (selected.configKey(true).equals(config.configKey(true))) {
                        found = true;
                        continue;
                    }

                    int rssi = WifiConfiguration.INVALID_RSSI;
                    if (config.visibility != null) {
                        rssi = config.visibility.rssi5;
                        if (config.visibility.rssi24 > rssi)
                            rssi = config.visibility.rssi24;
                    }
                    if (rssi < -80) {
                        continue;
                    }

                    //the selected configuration was preferred over a recently seen config
                    //hence remember the user's choice:
                    //add the recently seen config to the selected's connectChoices array

                    if (selected.connectChoices == null) {
                        selected.connectChoices = new HashMap<String, Integer>();
                    }

                    logDbg("updateConfigurationHistory add a choice " + selected.configKey(true)
                            + " over " + config.configKey(true)
                            + " RSSI " + Integer.toString(rssi));

                    //add the visible config to the selected's connect choice list
                    selected.connectChoices.put(config.configKey(true), rssi);

                    if (config.connectChoices != null) {
                        if (VDBG) {
                            logDbg("updateConfigurationHistory will remove "
                                    + selected.configKey(true) + " from " + config.configKey(true));
                        }
                        //remove the selected from the recently seen config's connectChoice list
                        config.connectChoices.remove(selected.configKey(true));

                        if (selected.linkedConfigurations != null) {
                           //remove the selected's linked configuration from the
                           //recently seen config's connectChoice list
                           for (String key : selected.linkedConfigurations.keySet()) {
                               config.connectChoices.remove(key);
                           }
                        }
                    }
                }
                if (found == false) {
                     // log an error for now but do something stringer later
                     // we will need a new scan before attempting to connect to this
                     // configuration anyhow and thus we can process the scan results then
                     logDbg("updateConfigurationHistory try to connect to an old network!! : "
                             + selected.configKey());
                }

                if (selected.connectChoices != null) {
                    if (VDBG)
                        logDbg("updateConfigurationHistory " + Integer.toString(netId)
                                + " now: " + Integer.toString(selected.connectChoices.size()));
                }
            }
        }

        //TODO: write only if something changed
        if (userTriggered || connect) {
            mWifiConfigStore.writeKnownNetworkHistory();
        }
    }

    void printChoices(WifiConfiguration config) {
        int num = 0;
        if (config.connectChoices!= null) {
            num = config.connectChoices.size();
        }

        logDbg("printChoices " + config.SSID + " num choices: " + Integer.toString(num));
        if (config.connectChoices!= null) {
            for (String key : config.connectChoices.keySet()) {
                logDbg("                 " + key);
            }
        }
    }

    boolean hasConnectChoice(WifiConfiguration source, WifiConfiguration target) {
        boolean found = false;
        if (source == null)
            return false;
        if (target == null)
            return false;

        if (source.connectChoices != null) {
            if ( source.connectChoices.get(target.configKey(true)) != null) {
                found = true;
            }
        }

        if (source.linkedConfigurations != null) {
            for (String key : source.linkedConfigurations.keySet()) {
                WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(key);
                if (config != null) {
                    if (config.connectChoices != null) {
                        if (config.connectChoices.get(target.configKey(true)) != null) {
                            found = true;
                        }
                    }
                }
            }

        }
        return found;
    }

    int compareWifiConfigurationsRSSI(WifiConfiguration a, WifiConfiguration b) {
        int order = 0;
        int boost5 = 25;
        WifiConfiguration.Visibility astatus = a.visibility;
        WifiConfiguration.Visibility bstatus = b.visibility;
        if (astatus == null || bstatus == null) {
            //error -> cant happen, need to throw en exception
            logDbg("compareWifiConfigurations NULL band status!");
            return 0;
        }
        if ((astatus.rssi5 > -70) && (bstatus.rssi5 == WifiConfiguration.INVALID_RSSI)
                && ((astatus.rssi5 + boost5) > (bstatus.rssi24))) {
            //a is seen on 5GHz with good RSSI, greater rssi than b
            //a is of higher priority - descending
            order = -1;
        } else if ((bstatus.rssi5 > -70) && (astatus.rssi5 == WifiConfiguration.INVALID_RSSI)
                && ((bstatus.rssi5 + boost5) > (bstatus.rssi24))) {
            //b is seen on 5GHz with good RSSI, greater rssi than a
            //a is of lower priority - ascending
            order = 1;
        }
        return order;
    }


    int compareWifiConfigurations(WifiConfiguration a, WifiConfiguration b) {
        int order = 0;
        String lastSelectedConfiguration = mWifiConfigStore.getLastSelectedConfiguration();
        boolean linked = false;

        if ((a.linkedConfigurations != null) && (b.linkedConfigurations != null)
                && (a.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)
                && (b.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)) {
            if ((a.linkedConfigurations.get(b.configKey(true))!= null)
                    && (b.linkedConfigurations.get(a.configKey(true))!= null)) {
                linked = true;
            }
        }

        if (a.ephemeral && b.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " + b.configKey()
                        + " over " + a.configKey());
            }
            return 1; //b is of higher priority - ascending
        }
        if (b.ephemeral && a.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " +a.configKey()
                        + " over " + b.configKey());
            }
            return -1; //a is of higher priority - descending
        }

        int aRssiBoost5 = 0;
        int bRssiBoost5 = 0;
        //apply Hysteresis: boost the RSSI value of the currently connected configuration
        int aRssiBoost = 0;
        int bRssiBoost = 0;
        if (null != mCurrentConfigurationKey) {
            if (a.configKey().equals(mCurrentConfigurationKey)) {
                aRssiBoost += 10;
            } else if (b.configKey().equals(mCurrentConfigurationKey)) {
                bRssiBoost += 10;
            }
        }
        if (linked) {
            int ascore;
            int bscore;
            // then we try prefer 5GHz, and try to ignore user's choice
            WifiConfiguration.Visibility astatus = a.visibility;
            WifiConfiguration.Visibility bstatus = b.visibility;
            if (astatus == null || bstatus == null) {
                //error
                logDbg("compareWifiConfigurations NULL band status!");
                return 0;
            }

            if (VDBG)  {
                logDbg("compareWifiConfigurations linked: " + Integer.toString(astatus.rssi5)
                        + "," + Integer.toString(astatus.rssi24) + "   "
                        + Integer.toString(bstatus.rssi5) + ","
                        + Integer.toString(bstatus.rssi24));
            }

            //Boost RSSI value of 5GHz bands iff the base value is better than -65
            //This implements band preference where we prefer 5GHz if RSSI5 is good enough, whereas
            //we prefer 2.4GHz otherwise.
            //Note that 2.4GHz doesn't need a boost since at equal power the RSSI is 6-10 dB higher
            if ((astatus.rssi5+aRssiBoost) > WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD) {
                aRssiBoost5 = 25;
            }
            if ((bstatus.rssi5+bRssiBoost) > WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD) {
                bRssiBoost5 = 25;
            }

            if (astatus.rssi5+aRssiBoost5 > astatus.rssi24) {
                //prefer a's 5GHz
                ascore = astatus.rssi5 + aRssiBoost5 + aRssiBoost;
            } else {
                //prefer a's 2.4GHz
                ascore = astatus.rssi24 + aRssiBoost;
            }
            if (bstatus.rssi5+bRssiBoost5 > bstatus.rssi24) {
                //prefer b's 5GHz
                bscore = bstatus.rssi5 + bRssiBoost5 + bRssiBoost;
            } else {
                //prefer b's 2.4GHz
                bscore = bstatus.rssi24 + bRssiBoost;
            }
            if (ascore > bscore) {
                //a is seen on 5GHz with good RSSI, greater rssi than b
                //a is of higher priority - descending
                order = -10;
                if (VDBG) {
                    logDbg("compareWifiConfigurations linked and prefers " + a.configKey()
                            + " rssi=(" + a.visibility.rssi24
                            + "," + a.visibility.rssi5
                            + ") num=(" + a.visibility.num24
                            + "," + a.visibility.num5 + ")"
                            + " over " + b.configKey()
                            + " rssi=(" + b.visibility.rssi24
                            + "," + b.visibility.rssi5
                            + ") num=(" + b.visibility.num24
                            + "," + b.visibility.num5 + ")"
                            + " due to RSSI");
                }
            } else if (bscore > ascore) {
                //b is seen on 5GHz with good RSSI, greater rssi than a
                //a is of lower priority - ascending
                order = 10;
                if (VDBG) {
                    logDbg("compareWifiConfigurations linked and prefers " + b.configKey()
                            + " rssi=(" + b.visibility.rssi24
                            + "," + b.visibility.rssi5
                            + ") num=(" + b.visibility.num24
                            + "," + b.visibility.num5 + ")"
                            + " over " + a.configKey()
                            + " rssi=(" + a.visibility.rssi24
                            + "," + a.visibility.rssi5
                            + ") num=(" + a.visibility.num24
                            + "," + a.visibility.num5 + ")"
                            + " due to RSSI");
                }
            }
        }

        //compare by user's choice.
        if (hasConnectChoice(a, b)) {
            //a is of higher priority - descending
            order = order -2;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers -2 " + a.configKey()
                        + " over " + b.configKey()
                        + " due to user choice order -> " + Integer.toString(order));
            }
        }

        if (hasConnectChoice(b, a)) {
            //a is of lower priority - ascending
            order = order + 2;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers +2 " + b.configKey() + " over "
                        + a.configKey() + " due to user choice order ->" + Integer.toString(order));
            }
        }

        //TODO count the number of association rejection
        // and use this to adjust the order by more than +/- 3
        if ((a.status == WifiConfiguration.Status.DISABLED)
                && (a.disableReason == WifiConfiguration.DISABLED_ASSOCIATION_REJECT)) {
            //a is of lower priority - ascending
            //lower the comparison score a bit
            order = order +3;
        }
        if ((b.status == WifiConfiguration.Status.DISABLED)
                && (b.disableReason == WifiConfiguration.DISABLED_ASSOCIATION_REJECT)) {
            //a is of higher priority - descending
            //lower the comparison score a bit
            order = order -3;
        }

        if ((lastSelectedConfiguration != null)
                && a.configKey().equals(lastSelectedConfiguration)) {
            // a is the last selected configuration, so keep it above connect choices (+/-2) and
            // above RSSI based selection of linked configuration (+/- 11)
            // by giving a -11
            // Additional other factors like BAD RSSI (still to do) and
            // ASSOC_REJECTION high counts will then still
            // tip the auto-join to roam
            order = order - 11;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers -11 " + a.configKey()
                        + " over " + b.configKey() + " because a is the last selected -> "
                        + Integer.toString(order));
            }
        } else if ((lastSelectedConfiguration != null)
                && b.configKey().equals(lastSelectedConfiguration)) {
            // b is the last selected configuration, so keep it above connect choices (+/-2) and
            // above RSSI based selection of linked configuration (+/- 11)
            // by giving a +11
            // Additional other factors like BAD RSSI (still to do) and
            // ASSOC_REJECTION high counts will then still
            // tip the auto-join to roam
            order = order + 11;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers +11 " + a.configKey()
                        + " over " + b.configKey() + " because b is the last selected -> "
                        + Integer.toString(order));
            }
        }

        if (order == 0) {
            //we don't know anything - pick the last seen i.e. K behavior
            //we should do this only for recently picked configurations
            if (a.priority > b.priority) {
                //a is of higher priority - descending
                if (VDBG)   {
                    logDbg("compareWifiConfigurations prefers -1 " + a.configKey() + " over "
                            + b.configKey() + " due to priority");
                }

                order = -1;
            } else if (a.priority < b.priority) {
                //a is of lower priority - ascending
                if (VDBG)  {
                    logDbg("compareWifiConfigurations prefers +1 " + b.configKey() + " over "
                            + a.configKey() + " due to priority");
                }

              order = 1;
            } else {
                //maybe just look at RSSI or band
                if (VDBG)  {
                    logDbg("compareWifiConfigurations prefers +1 " + b.configKey() + " over "
                            + a.configKey() + " due to nothing");
                }

                order = compareWifiConfigurationsRSSI(a, b); //compare RSSI
            }
        }

        String sorder = " == ";
        if (order > 0)
            sorder = " < ";
        if (order < 0)
            sorder = " > ";

        if (VDBG)   {
            logDbg("compareWifiConfigurations Done: " + a.configKey() + sorder
                    + b.configKey() + " order " + Integer.toString(order));
        }

        return order;
    }

    /* attemptRoam function implement the core of the same SSID switching algorithm */
    ScanResult attemptRoam(WifiConfiguration current, int age) {
        ScanResult a = null;
        if (current == null) {
            if (VDBG)   {
                logDbg("attemptRoam not associated");
            }
            return null;
        }
        if (current.scanResultCache == null) {
            if (VDBG)   {
                logDbg("attemptRoam no scan cache");
            }
            return null;
        }
        if (current.scanResultCache.size() > 6) {
            if (VDBG)   {
                logDbg("attemptRoam scan cache size "
                        + current.scanResultCache.size() + " --> bail");
            }
            //implement same SSID roaming only for configurations
            // that have less than 4 BSSIDs
            return null;
        }
        String currentBSSID = mWifiStateMachine.getCurrentBSSID();
        if (currentBSSID == null) {
            if (DBG)   {
                logDbg("attemptRoam currentBSSID unknown");
            }
            return null;
        }

        if (current.bssidOwnerUid!= 0 && current.bssidOwnerUid != Process.WIFI_UID) {
            if (DBG)   {
                logDbg("attemptRoam BSSID owner is "
                        + Long.toString(current.bssidOwnerUid) + " -> bail");
            }
            return null;
        }

        //determine which BSSID we want to associate to, taking account
        // relative strength of 5 and 2.4 GHz BSSIDs
        long now_ms = System.currentTimeMillis();
        int bRssiBoost5 = 0;
        int aRssiBoost5 = 0;
        int bRssiBoost = 0;
        int aRssiBoost = 0;
        for (ScanResult b : current.scanResultCache.values()) {

            if ((b.seen == 0) || (b.BSSID == null)) {
                continue;
            }

            if (b.status != ScanResult.ENABLED) {
                continue;
            }

            if (b.status != ScanResult.ENABLED)
                continue;

            if ((now_ms - b.seen) > age) continue;

            //pick first one
            if (a == null) {
                a = b;
                continue;
            }

            if (currentBSSID.equals(b.BSSID)) {
                //reduce the benefit of hysteresis if RSSI <= -75
                if (b.level <= WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD) {
                    bRssiBoost = +6;
                } else {
                    bRssiBoost = +10;
                }
            }
            if (currentBSSID.equals(a.BSSID)) {
                if (a.level <= WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD) {
                    //reduce the benefit of hysteresis if RSSI <= -75
                    aRssiBoost = +6;
                } else {
                    aRssiBoost = +10;
                }
            }
            if (b.is5GHz() && (b.level+bRssiBoost)
                    > WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD) {
                bRssiBoost5 = 25;
            } else if (b.is5GHz() && (b.level+bRssiBoost)
                    < WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD) {
                bRssiBoost5 = -10;
            }
            if (a.is5GHz() && (a.level+aRssiBoost)
                    > WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD) {
                aRssiBoost5 = 25;
            } else if (a.is5GHz() && (a.level+aRssiBoost)
                    < WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD) {
                aRssiBoost5 = -10;
            }

            if (VDBG)  {
                String comp = " < ";
                if (b.level + bRssiBoost + bRssiBoost5 > a.level +aRssiBoost + aRssiBoost5) {
                    comp = " > ";
                }
                logDbg("attemptRoam: "
                        + b.BSSID + " rssi=" + b.level + " boost=" + Integer.toString(bRssiBoost)
                        + "/" + Integer.toString(bRssiBoost5) + " freq=" + b.frequency + comp
                        + a.BSSID + " rssi=" + a.level + " boost=" + Integer.toString(aRssiBoost)
                        + "/" + Integer.toString(aRssiBoost5) + " freq=" + a.frequency);
            }

            if (b.level + bRssiBoost + bRssiBoost5 > a.level +aRssiBoost + aRssiBoost5) {
                //b is the better BSSID
                a = b;
            }
        }
        if (a != null) {
            if (VDBG)  {
                logDbg("attemptRoam: Found "
                        + a.BSSID + " rssi=" + a.level + " freq=" + a.frequency
                        + " Current: " + currentBSSID);
            }
            if (currentBSSID.equals(a.BSSID)) {
                return null;
            }
        }
        return a;
    }

    /* attemptAutoJoin function implement the core of the a network switching algorithm */
    void attemptAutoJoin() {
        int networkSwitchType = AUTO_JOIN_IDLE;

        String lastSelectedConfiguration = mWifiConfigStore.getLastSelectedConfiguration();

        // reset the currentConfiguration Key, and set it only if WifiStateMachine and
        // supplicant agree
        mCurrentConfigurationKey = null;
        WifiConfiguration currentConfiguration = mWifiStateMachine.getCurrentWifiConfiguration();

        WifiConfiguration candidate = null;

        /* obtain the subset of recently seen networks */
        List<WifiConfiguration> list = mWifiConfigStore.getRecentConfiguredNetworks(3000, false);
        if (list == null) {
            if (VDBG)  logDbg("attemptAutoJoin nothing");
            return;
        }

        /* find the currently connected network: ask the supplicant directly */
        String val = mWifiNative.status();
        String status[] = val.split("\\r?\\n");
        if (VDBG) {
            logDbg("attemptAutoJoin() status=" + val + " split="
                    + Integer.toString(status.length));
        }

        int supplicantNetId = -1;
        for (String key : status) {
            if (key.regionMatches(0, "id=", 0, 3)) {
                int idx = 3;
                supplicantNetId = 0;
                while (idx < key.length()) {
                    char c = key.charAt(idx);

                    if ((c >= 0x30) && (c <= 0x39)) {
                        supplicantNetId *= 10;
                        supplicantNetId += c - 0x30;
                        idx++;
                    } else {
                        break;
                    }
                }
            }
        }
        if (DBG) {
            logDbg("attemptAutoJoin() num recent config " + Integer.toString(list.size())
                    + " ---> suppId=" + Integer.toString(supplicantNetId));
        }

        if (currentConfiguration != null) {
            if (supplicantNetId != currentConfiguration.networkId) {
                logDbg("attemptAutoJoin() ERROR wpa_supplicant out of sync nid="
                        + Integer.toString(supplicantNetId) + " WifiStateMachine="
                        + Integer.toString(currentConfiguration.networkId));
                mWifiStateMachine.disconnectCommand();
                return;
            } else {
                mCurrentConfigurationKey = currentConfiguration.configKey();
            }
        }

        int currentNetId = -1;
        if (currentConfiguration != null) {
            // if we are associated to a configuration, it will
            // be compared thru the compareNetwork function
            currentNetId = currentConfiguration.networkId;
        }

        /* run thru all visible configurations without looking at the one we
         * are currently associated to
         * select Best Network candidate from known WifiConfigurations
         * */
        for (WifiConfiguration config : list) {
            if ((config.status == WifiConfiguration.Status.DISABLED)
                    && (config.disableReason == WifiConfiguration.DISABLED_AUTH_FAILURE)) {
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to auth failure: "
                            + config.configKey(true));
                }
                continue;
            }

            if (config.SSID == null) {
                continue;
            }

            if (config.autoJoinStatus >=
                    WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE) {
                //avoid temporarily disabled networks altogether
                //TODO: implement a better logic which will re-enable the network after some time
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to auto join status "
                            + Integer.toString(config.autoJoinStatus) + " key "
                            + config.configKey(true));
                }
                continue;
            }

            //try to unblacklist based on elapsed time
            if (config.blackListTimestamp > 0) {
                long now = System.currentTimeMillis();
                if (now < config.blackListTimestamp) {
                    //looks like there was a change in the system clock since we black listed, and
                    //timestamp is not meaningful anymore, hence lose it.
                    //this event should be rare enough so that we still want to lose the black list
                    config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                } else {
                    if ((now - config.blackListTimestamp) > loseBlackListHardMilli) {
                        //reenable it after 18 hours, i.e. next day
                        config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                    } else if ((now - config.blackListTimestamp) > loseBlackListSoftMilli) {
                        //lose blacklisting due to bad link
                        config.setAutoJoinStatus(config.autoJoinStatus - 8);
                    }
                }
            }

            //try to unblacklist based on good visibility
            if (config.visibility.rssi5 < WifiConfiguration.UNBLACKLIST_THRESHOLD_5_SOFT
                    && config.visibility.rssi24 < WifiConfiguration.UNBLACKLIST_THRESHOLD_24_SOFT) {
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to auto join status "
                            + config.autoJoinStatus
                            + " key " + config.configKey(true)
                            + " rssi=(" + config.visibility.rssi24
                            + "," + config.visibility.rssi5
                            + ") num=(" + config.visibility.num24
                            + "," + config.visibility.num5 + ")");
                }
            } else if (config.visibility.rssi5 < WifiConfiguration.UNBLACKLIST_THRESHOLD_5_HARD
                    && config.visibility.rssi24 < WifiConfiguration.UNBLACKLIST_THRESHOLD_24_HARD) {
                // if the network is simply temporary disabled, don't allow reconnect until
                // rssi becomes good enough
                config.setAutoJoinStatus(config.autoJoinStatus - 1);
                if (DBG) {
                    logDbg("attemptAutoJoin good candidate seen, bumped soft -> status="
                            + config.autoJoinStatus
                            + " key " + config.configKey(true) + " rssi=("
                            + config.visibility.rssi24 + "," + config.visibility.rssi5
                            + ") num=(" + config.visibility.num24
                            + "," + config.visibility.num5 + ")");
                }
            } else {
                config.setAutoJoinStatus(config.autoJoinStatus - 3);
                if (DBG) {
                    logDbg("attemptAutoJoin good candidate seen, bumped hard -> status="
                            + config.autoJoinStatus
                            + " key " + config.configKey(true) + " rssi=("
                            + config.visibility.rssi24 + "," + config.visibility.rssi5
                            + ") num=(" + config.visibility.num24
                            + "," + config.visibility.num5 + ")");
                }
            }

            if (config.autoJoinStatus >=
                    WifiConfiguration.AUTO_JOIN_TEMPORARY_DISABLED) {
                //network is blacklisted, skip
                if (DBG) {
                    logDbg("attemptAutoJoin skip blacklisted -> status="
                            + config.autoJoinStatus
                            + " key " + config.configKey(true) + " rssi=("
                            + config.visibility.rssi24 + "," + config.visibility.rssi5
                            + ") num=(" + config.visibility.num24
                            + "," + config.visibility.num5 + ")");
                }
                continue;
            }
            if (config.networkId == currentNetId) {
                if (DBG) {
                    logDbg("attemptAutoJoin skip current candidate  "
                            + Integer.toString(currentNetId)
                            + " key " + config.configKey(true));
                }
                continue;
            }

            if (lastSelectedConfiguration == null ||
                    !config.configKey().equals(lastSelectedConfiguration)) {
                //don't try to autojoin a network that is too far
                if (config.visibility == null) {
                    continue;
                }
                if (config.visibility.rssi5 < WifiConfiguration.INITIAL_AUTO_JOIN_ATTEMPT_MIN_5
                        && config.visibility.rssi24
                        < WifiConfiguration.INITIAL_AUTO_JOIN_ATTEMPT_MIN_24) {
                    if (DBG) {
                        logDbg("attemptAutoJoin gskip due to low visibility -> status="
                                + config.autoJoinStatus
                                + " key " + config.configKey(true) + " rssi="
                                + config.visibility.rssi24 + ", " + config.visibility.rssi5
                                + " num=" + config.visibility.num24
                                + ", " + config.visibility.num5);
                    }
                    continue;
                }
            }

            if (DBG) {
                logDbg("attemptAutoJoin trying candidate id="
                        + Integer.toString(config.networkId) + " "
                        + config.SSID + " key " + config.configKey(true)
                        + " status=" + config.autoJoinStatus);
            }

            if (candidate == null) {
                candidate = config;
            } else {
                if (VDBG)  {
                    logDbg("attemptAutoJoin will compare candidate  " + candidate.configKey()
                            + " with " + config.configKey());
                }

                int order = compareWifiConfigurations(candidate, config);
                if (order > 0) {
                    //ascending : candidate < config
                    candidate = config;
                }
            }
        }

        /* now, go thru scan result to try finding a better Herrevad network */
        if (mNetworkScoreCache != null) {
            int rssi5 = WifiConfiguration.INVALID_RSSI;
            int rssi24 = WifiConfiguration.INVALID_RSSI;
            WifiConfiguration.Visibility visibility;
            if (candidate != null) {
                rssi5 = candidate.visibility.rssi5;
                rssi24 = candidate.visibility.rssi24;
            }

            //get current date
            long now_ms = System.currentTimeMillis();

            if (rssi5 < -60 && rssi24 < -70) {
                for (ScanResult result : scanResultCache.values()) {
                    if ((now_ms - result.seen) < 3000) {
                        int score = mNetworkScoreCache.getNetworkScore(result);
                        if (score > 0) {
                            // try any arbitrary formula for now, adding apple and oranges,
                            // i.e. adding network score and "dBm over noise"
                           if (result.is24GHz()) {
                                if ((result.level + score) > (rssi24 -40)) {
                                    // force it as open, TBD should we otherwise verify that this
                                    // BSSID only supports open??
                                    result.capabilities = "";

                                    //switch to this scan result
                                    candidate =
                                          mWifiConfigStore.wifiConfigurationFromScanResult(result);
                                    candidate.ephemeral = true;
                                }
                           } else {
                                if ((result.level + score) > (rssi5 -30)) {
                                    // force it as open, TBD should we otherwise verify that this
                                    // BSSID only supports open??
                                    result.capabilities = "";

                                    //switch to this scan result
                                    candidate =
                                          mWifiConfigStore.wifiConfigurationFromScanResult(result);
                                    candidate.ephemeral = true;
                                }
                           }
                        }
                    }
                }
            }
        }

        /* if candidate is found, check the state of the connection so as
            to decide if we should be acting on this candidate and switching over */
        int networkDelta = compareNetwork(candidate);
        if (DBG && candidate != null) {
            logDbg("attemptAutoJoin compare SSID candidate : delta="
                    + Integer.toString(networkDelta) + " "
                    + candidate.configKey()
                    + " linked=" + (currentConfiguration != null
                    && currentConfiguration.isLinked(candidate)));
        }

        /* ASK WifiStateMachine permission to switch:
            for instance,
            if user is currently streaming voice traffic,
            then don’t switch regardless of the delta
            */
        if (mWifiStateMachine.shouldSwitchNetwork(networkDelta)) {
            if (mStaStaSupported) {
                logDbg("mStaStaSupported --> error do nothing now ");
            } else {
                if (currentConfiguration != null && currentConfiguration.isLinked(candidate)) {
                    networkSwitchType = AUTO_JOIN_EXTENDED_ROAMING;
                } else {
                    networkSwitchType = AUTO_JOIN_OUT_OF_NETWORK_ROAMING;
                }
                if (DBG) {
                    logDbg("AutoJoin auto connect with netId "
                            + Integer.toString(candidate.networkId)
                            + " to " + candidate.configKey());
                }
                mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_CONNECT,
                        candidate.networkId, networkSwitchType, candidate);
            }
        }

        if (networkSwitchType == AUTO_JOIN_IDLE) {
            //attempt same WifiConfiguration roaming
            ScanResult roamCandidate = attemptRoam(currentConfiguration, 3000);
            if (roamCandidate != null) {
                if (DBG) {
                    logDbg("AutoJoin auto roam with netId "
                            + Integer.toString(currentConfiguration.networkId)
                            + " " + currentConfiguration.configKey() + " to BSSID="
                            + roamCandidate.BSSID + " freq=" + roamCandidate.frequency
                            + " RSSI=" + roamCandidate.frequency);
                }
                networkSwitchType = AUTO_JOIN_ROAMING;
                mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_ROAM,
                            currentConfiguration.networkId, 1, roamCandidate);
            }
        }
        if (VDBG) logDbg("Done attemptAutoJoin status=" + Integer.toString(networkSwitchType));
    }
}

