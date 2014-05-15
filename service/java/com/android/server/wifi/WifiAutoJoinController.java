/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.net.wifi.WifiConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import android.os.SystemClock;
import android.util.Log;

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
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final boolean mStaStaSupported = false;
    private static final int SCAN_RESULT_CACHE_SIZE = 80;


    private HashMap<String, ScanResult> scanResultCache =
            new HashMap<String, ScanResult>();

    WifiAutoJoinController(Context c, WifiStateMachine w, WifiConfigStore s,
                           WifiTrafficPoller t, WifiNative n) {
        mContext = c;
        mWifiStateMachine = w;
        mWifiConfigStore = s;
        mWifiTrafficPoller = t;
        mWifiNative = n;
        mNetworkScoreCache = null;
        scoreManager = (NetworkScoreManager) mContext.getSystemService(Context.NETWORK_SCORE_SERVICE);
        if (scoreManager == null)
            logDbg("Registered scoreManager NULL " + " service " + Context.NETWORK_SCORE_SERVICE);
        else
            logDbg("Registered scoreManager NOT NULL" + " service " + Context.NETWORK_SCORE_SERVICE);

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

    int mScanResultMaximumAge = 30000; /* milliseconds unit */

    /* flush out scan results older than mScanResultMaximumAge */
    private void ageScanResultsOut(int delay) {
        if (delay <= 0) {
            delay = mScanResultMaximumAge; //something sane
        }
        Date now = new Date();
        long milli = now.getTime();
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

    /* Check if this network is known to kepler and return its score */
    private int isScoredNetwork(ScanResult result) {
        if (mNetworkScoreCache == null)
            return 0;
        return mNetworkScoreCache.getNetworkScore(result);
    }


    void addToScanCache(List<ScanResult> scanList) {
        WifiConfiguration associatedConfig;

        for(ScanResult result: scanList) {
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
            }

            scanResultCache.put(result.BSSID, new ScanResult(result));

            ScanResult srn = scanResultCache.get(result.BSSID);

            //add this BSSID to the scanResultCache of the relevant WifiConfiguration
            associatedConfig = mWifiConfigStore.updateSavedNetworkHistory(result);

            //try to associate this BSSID to an existing Saved Wificonfiguration
            if (associatedConfig == null) {
                associatedConfig = mWifiConfigStore.associateWithConfiguration(result);
                if (associatedConfig != null) {
                    if (VDBG) {
                        logDbg("addToScanCache save associated config "
                                + associatedConfig.SSID + " with " + associatedConfig.SSID);
                    }
                    mWifiStateMachine.sendMessage(WifiManager.SAVE_NETWORK, associatedConfig);
                }
            }
        }
    }

    void logDbg(String message) {
        long now = SystemClock.elapsedRealtimeNanos();
        String ts = String.format("[%,d us] ", now/1000);
        Log.e(TAG, ts + message   + " stack:"
                + Thread.currentThread().getStackTrace()[2].getMethodName() +" - "
                + Thread.currentThread().getStackTrace()[3].getMethodName() +" - "
                + Thread.currentThread().getStackTrace()[4].getMethodName() +" - "
                + Thread.currentThread().getStackTrace()[5].getMethodName());

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
     ***/
    private int compareNetwork(WifiConfiguration candidate) {
        WifiConfiguration currentNetwork = mWifiStateMachine.getCurrentWifiConfiguration();
        if (currentNetwork == null)
            return 1000;

        if (candidate.configKey(true).equals(currentNetwork.configKey(true))) {
            return -1;
        }

        int order = compareWifiConfigurations(currentNetwork, candidate);

        if (order > 0) {
            //ascending: currentNetwork < candidate
            return 10; //will try switch over to the candidate
        }

        return 0;
    }



    public void updateSavedConfigurationsPriorities(int netId) {

        WifiConfiguration selected = mWifiConfigStore.getWifiConfiguration(netId);
        if (selected == null) {
            return;
        }

        // reenable autojoin for this network,
        // since the user want to connect to this configuration
        selected.autoJoinStatus = WifiConfiguration.AUTO_JOIN_ENABLED;

        if (DBG) {
            if (selected.connectChoices != null) {
                logDbg("updateSavedConfigurationsPriorities will update "
                        + Integer.toString(netId) + " now: "
                        + Integer.toString(selected.connectChoices.size()));
            } else {
                logDbg("updateSavedConfigurationsPriorities will update "
                        + Integer.toString(netId));
            }
        }

        List<WifiConfiguration> networks =  mWifiConfigStore.getRecentConfiguredNetworks(12000, false);
        if (networks == null)
            return;

        for (WifiConfiguration config: networks) {

            if (DBG)
                logDbg("updateSavedConfigurationsPriorities got " + config.SSID);

            if (selected.configKey(true).equals(config.configKey(true))) {
                continue;
            }

            //we were preferred over a recently seen config
            if (selected.connectChoices == null) {
                selected.connectChoices = new HashMap<String, Integer>();
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

            //remember the user's choice:
            //add the recently seen config to the selected's choice
            logDbg("updateSavedConfigurationsPriorities add a choice " + selected.configKey(true)
                    + " over " + config.configKey(true) + " RSSI " + Integer.toString(rssi));
            selected.connectChoices.put(config.configKey(true), rssi);

            if (config.connectChoices != null) {
                if (VDBG)
                    logDbg("updateSavedConfigurationsPriorities try to remove "
                            + selected.configKey(true) + " from " + config.configKey(true));

                //remove the selected from the recently seen config's array
                config.connectChoices.remove(selected.configKey(true));
            }
            printChoices(config);
        }

        if (selected.connectChoices != null) {
            if (VDBG) logDbg("updateSavedConfigurationsPriorities " + Integer.toString(netId)
                    + " now: " + Integer.toString(selected.connectChoices.size()));
        }

        mWifiConfigStore.writeKnownNetworkHistory();
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
            //error
            logDbg("compareWifiConfigurations NULL band status!");
            return 0;
        }
        if ((astatus.rssi5 > -70) && (bstatus.rssi5 == -127)
                && ((astatus.rssi5+boost5) > (bstatus.rssi24))) {
            //a is seen on 5GHz with good RSSI, greater rssi than b
            //a is of higher priority - descending
            order = -1;
        } else if ((bstatus.rssi5 > -70) && (astatus.rssi5 == -127)
                && ((bstatus.rssi5+boost5) > (bstatus.rssi24))) {
            //b is seen on 5GHz with good RSSI, greater rssi than a
            //a is of lower priority - ascending
            order = 1;
        }
        return order;
    }

    int compareWifiConfigurations(WifiConfiguration a, WifiConfiguration b) {
        int order = 0;

        boolean linked = false;

        if ((a.linkedConfigurations != null) && (b.linkedConfigurations != null)) {
            if ((a.linkedConfigurations.get(b.configKey(true))!= null)
                    && (b.linkedConfigurations.get(a.configKey(true))!= null)) {
                linked = true;
            }
        }

        if (a.ephemeral && b.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " + b.SSID
                        + " over " + a.SSID);
            }
            return 1; //b is of higher priority - ascending
        }
        if (b.ephemeral && a.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " +a.SSID
                        + " over " + b.SSID);
            }
            return -1; //a is of higher priority - descending
        }

        int boost5 = 25;
        if (linked) {
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

            if ((astatus.rssi5 > -70) && (bstatus.rssi5 == -127)
                    && ((astatus.rssi5+boost5) > (bstatus.rssi24))) {
                    //a is seen on 5GHz with good RSSI, greater rssi than b
                    //a is of higher priority - descending
                    order = -10;

                if (VDBG) {
                    logDbg("compareWifiConfigurations linked and prefers " + a.SSID
                            + " over " + b.SSID
                            + " due to 5GHz RSSI " + Integer.toString(astatus.rssi5)
                            + " over: 5=" + Integer.toString(bstatus.rssi5)
                            + ", 2.4=" + Integer.toString(bstatus.rssi5));
                }
            } else if ((bstatus.rssi5 > -70) && (astatus.rssi5 == -127)
                    && ((bstatus.rssi5+boost5) > (bstatus.rssi24))) {
                    //b is seen on 5GHz with good RSSI, greater rssi than a
                    //a is of lower priority - ascending
                if (VDBG)   {
                    logDbg("compareWifiConfigurations linked and prefers " + b.SSID
                            + " over " + a.SSID + " due to 5GHz RSSI "
                            + Integer.toString(astatus.rssi5) + " over: 5="
                            + Integer.toString(bstatus.rssi5) + ", 2.4="
                            + Integer.toString(bstatus.rssi5));
                }
                order = 10;
            }
        }
        //assuming that the WifiConfiguration aren't part of the same "extended roam domain",
        //then compare by user's choice.
        if (hasConnectChoice(a, b)) {
            //a is of higher priority - descending
            order = order -2;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers -2 " + a.SSID
                        + " over " + b.SSID + " due to user choice order -> " + Integer.toString(order));
            }
        }

        if (hasConnectChoice(b, a)) {
            //a is of lower priority - ascending
            order = order + 2;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers +2 " + b.SSID + " over "
                        + a.SSID + " due to user choice order ->" + Integer.toString(order));
            }
        }

        if (order == 0) {
            //we don't know anything - pick the last seen i.e. K behavior
            //we should do this only for recently picked configurations
            if (a.priority > b.priority) {
                //a is of higher priority - descending
                if (VDBG)   {
                    logDbg("compareWifiConfigurations prefers -1 " + a.SSID + " over "
                            + b.SSID + " due to priority");
                }

                order = -1;
            } else if (a.priority < b.priority) {
                //a is of lower priority - ascending
                if (VDBG)  {
                    logDbg("compareWifiConfigurations prefers +1 " + b.SSID + " over "
                            + a.SSID + " due to priority");
                }

              order = 1;
            } else {
                //maybe just look at RSSI or band
                if (VDBG)  {
                    logDbg("compareWifiConfigurations prefers +1 " + b.SSID + " over "
                            + a.SSID + " due to nothing");
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
            logDbg("compareWifiConfigurations Done: " + a.SSID + sorder
                    + b.SSID + " order " + Integer.toString(order));
        }

        return order;
    }

    /* attemptAutoJoin function implement the core of the a network switching algorithm */
    void attemptAutoJoin() {
        WifiConfiguration candidate = null;

        /* obtain the subset of recently seen networks */
        List<WifiConfiguration> list = mWifiConfigStore.getRecentConfiguredNetworks(3000, true);
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

        int currentNetId = -1;
        for (String key : status) {
            if (key.regionMatches(0, "id=", 0, 3)) {
                int idx = 3;
                currentNetId = 0;
                while (idx < key.length()) {
                    char c = key.charAt(idx);

                    if ((c >= 0x30) && (c <= 0x39)) {
                        currentNetId *= 10;
                        currentNetId += c - 0x30;
                        idx++;
                    } else {
                        break;
                    }
                }
            }
        }
        logDbg("attemptAutoJoin() num recent config " + Integer.toString(list.size())
                +  " ---> currentId=" + Integer.toString(currentNetId));

        /* select Best Network candidate from known WifiConfigurations */
        for (WifiConfiguration config : list) {
            if ((config.status == WifiConfiguration.Status.DISABLED)
                    && (config.disableReason == WifiConfiguration.DISABLED_AUTH_FAILURE)) {
                logDbg("attemptAutoJoin skip candidate due to auth failure "
                        + config.SSID + " key " + config.configKey(true));
                continue;
            }
            if (config.autoJoinStatus != WifiConfiguration.AUTO_JOIN_ENABLED) {
                logDbg("attemptAutoJoin skip candidate due to auto join status "
                        + Integer.toString(config.autoJoinStatus) + " " + config.SSID + " key "
                        + config.configKey(true));
                continue;
            }

            if (config.networkId == currentNetId) {
                logDbg("attemptAutoJoin skip current candidate  " + Integer.toString(currentNetId)
                        + " key " + config.configKey(true));
                continue;
            }

            if (DBG) logDbg("attemptAutoJoin trying candidate id=" + config.networkId + " "
                    + config.SSID + " key " + config.configKey(true));

            if (candidate == null) {
                candidate = config;
            } else {
                if (VDBG)  {
                    logDbg("attemptAutoJoin will compare candidate  " + candidate.SSID
                            + " with " + config.SSID + " key " + config.configKey(true));
                }

                int order = compareWifiConfigurations(candidate, config);

                if (VDBG) {
                    logDbg("attemptAutoJoin did compare candidate " + Integer.toString(order));
                }

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
            Date now = new Date();
            long now_ms = now.getTime();

            if (rssi5 < -60 && rssi24 < -70) {
                for (ScanResult result : scanResultCache.values()) {
                    if ((now_ms - result.seen) < 3000) {
                        int score = mNetworkScoreCache.getNetworkScore(result);
                        if (score > 0) {
                            // try any arbitrary formula for now, adding apple and oranges,
                            // i.e. adding network score and "dBm over noise"
                           if (result.frequency < 4000) {
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

        if (candidate != null) {
	    /* if candidate is found, check the state of the connection so as
	       to decide if we should be acting on this candidate and switching over */
           if (VDBG) {
               logDbg("attemptAutoJoin did find candidate " + candidate.SSID
                       + " key " + candidate.configKey(true));
           }

            int networkDelta = compareNetwork(candidate);
            if (networkDelta > 0)
                logDbg("attemptAutoJoin did find candidate " + candidate.SSID
                        + " for delta " + Integer.toString(networkDelta));

            /* ASK traffic poller permission to switch:
                for instance,
                if user is currently streaming voice traffic,
                then don’t switch regardless of the delta */

            if (mWifiTrafficPoller.shouldSwitchNetwork(networkDelta)) {
                if (mStaStaSupported) {

                } else {
                    if (DBG) {
                        logDbg("AutoJoin auto connect to netId "
                                + Integer.toString(candidate.networkId)
                                + " SSID " + candidate.SSID);
                    }

                    mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_CONNECT,
                            candidate.networkId);
                    //mWifiConfigStore.enableNetworkWithoutBroadcast(candidate.networkId, true);

                    //we would do the below only if we want to persist the new choice
                    //mWifiConfigStore.selectNetwork(candidate.networkId);

                }
            }
        }
        if (VDBG) logDbg("Done attemptAutoJoin");
    }
}

