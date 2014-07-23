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
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;

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

    // Lose the non-auth failure blacklisting after 8 hours
    private final static long loseBlackListHardMilli = 1000 * 60 * 60 * 8;
    // Lose some temporary blacklisting after 30 minutes
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

    /**
     * Flush out scan results older than mScanResultMaximumAge
     *
     */
    private void ageScanResultsOut(int delay) {
        if (delay <= 0) {
            delay = mScanResultMaximumAge; // Something sane
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
                // If there was a previous cache result for this BSSID, average the RSSI values

                int previous_rssi = sr.level;
                long previously_seen_milli = sr.seen;

                // Average RSSI with previously seen instances of this scan result
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

                // Remove the previous Scan Result
                scanResultCache.remove(result.BSSID);
            } else {
                if (!mNetworkScoreCache.isScoredNetwork(result)) {
                    WifiKey wkey;
                    // Quoted SSIDs are the only one valid at this stage
                    try {
                        wkey = new WifiKey("\"" + result.SSID + "\"", result.BSSID);
                    } catch (IllegalArgumentException e) {
                        logDbg("AutoJoinController: received badly encoded SSID=[" + result.SSID +
                                "] ->skipping this network");
                        wkey = null;
                    }
                    if (wkey != null) {
                        NetworkKey nkey = new NetworkKey(wkey);
                        //if we don't know this scan result then request a score from the scorer
                        unknownScanResults.add(nkey);
                    }
                }
            }

            scanResultCache.put(result.BSSID, new ScanResult(result));

            // Add this BSSID to the scanResultCache of the relevant WifiConfiguration
            associatedConfig = mWifiConfigStore.updateSavedNetworkHistory(result);

            // Try to associate this BSSID to an existing Saved WifiConfiguration
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
            // Kick the score manager, we will get updated scores asynchronously
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

    // Called directly from WifiStateMachine
    void newSupplicantResults() {
        List<ScanResult> scanList = mWifiStateMachine.syncGetScanResultsList();
        addToScanCache(scanList);
        ageScanResultsOut(mScanResultMaximumAge);
        if (DBG)
           logDbg("newSupplicantResults size=" + Integer.valueOf(scanResultCache.size()) );

        attemptAutoJoin();
        mWifiConfigStore.writeKnownNetworkHistory();

    }


    /**
     * Not used at the moment
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

    /**
     *  network link quality changed, called directly from WifiTrafficPoller,
     * or by listening to Link Quality intent
     */
    void linkQualitySignificantChange() {
        attemptAutoJoin();
    }

    /**
     * compare a WifiConfiguration against the current network, return a delta score
     * If not associated, and the candidate will always be better
     * For instance if the candidate is a home network versus an unknown public wifi,
     * the delta will be infinite, else compare Kepler scores etc…
     * Negatve return values from this functions are meaningless per se, just trying to
     * keep them distinct for debug purpose (i.e. -1, -2 etc...)
     */
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
     */
    public void updateConfigurationHistory(int netId, boolean userTriggered, boolean connect) {
        WifiConfiguration selected = mWifiConfigStore.getWifiConfiguration(netId);
        if (selected == null) {
            return;
        }

        if (selected.SSID == null) {
            return;
        }

        if (userTriggered) {
            // Reenable autojoin for this network,
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
            int choice = 0;
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

                    // Compare RSSI values so as to evaluate the strength of the user preference
                    int order = compareWifiConfigurationsRSSI(config, selected, null);

                    if (order < -30) {
                        // Selected configuration is worse than the visible configuration
                        // hence register a strong choice so as autojoin cannot override this
                        // for instance, the user has select a network
                        // with 1 bar over a network with 3 bars...
                        choice = 60;
                    } else if (order < -20) {
                        choice = 50;
                    } else if (order < -10) {
                        choice = 40;
                    } else if (order < 10) {
                        // Selected configuration is about same or has a slightly better RSSI
                        // hence register a weaker choice, here a difference of at least +/-30 in
                        // RSSI comparison triggered by autoJoin will override the choice
                        choice = 30;
                    } else if (order <= 30) {
                        // Selected configuration is better than the visible configuration
                        // hence we do not know if the user prefers this configuration strongly
                        choice = 20;
                    } else {
                        choice = 10;
                    }

                    // The selected configuration was preferred over a recently seen config
                    // hence remember the user's choice:
                    // add the recently seen config to the selected's connectChoices array

                    if (selected.connectChoices == null) {
                        selected.connectChoices = new HashMap<String, Integer>();
                    }

                    logDbg("updateConfigurationHistory add a choice " + selected.configKey(true)
                            + " over " + config.configKey(true)
                            + " choice " + Integer.toString(choice));

                    Integer currentChoice = selected.connectChoices.get(config.configKey(true));
                    if (currentChoice == null || currentChoice.intValue() < choice) {
                        // Add the visible config to the selected's connect choice list
                        selected.connectChoices.put(config.configKey(true), choice);
                    }

                    if (config.connectChoices != null) {
                        if (VDBG) {
                            logDbg("updateConfigurationHistory will remove "
                                    + selected.configKey(true) + " from " + config.configKey(true));
                        }
                        // Remove the selected from the recently seen config's connectChoice list
                        config.connectChoices.remove(selected.configKey(true));

                        if (selected.linkedConfigurations != null) {
                           // Remove the selected's linked configuration from the
                           // recently seen config's connectChoice list
                           for (String key : selected.linkedConfigurations.keySet()) {
                               config.connectChoices.remove(key);
                           }
                        }
                    }
                }
                if (found == false) {
                     // We haven't found the configuration that the user just selected in our
                     // scan cache.
                     // In that case we will need a new scan before attempting to connect to this
                     // configuration anyhow and thus we can process the scan results then.
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

        // TODO: write only if something changed
        if (userTriggered || connect) {
            mWifiConfigStore.writeKnownNetworkHistory();
        }
    }

    int getConnectChoice(WifiConfiguration source, WifiConfiguration target) {
        Integer choice = null;
        if (source == null || target == null) {
            return 0;
        }

        if (source.connectChoices != null
                && source.connectChoices.containsKey(target.configKey(true))) {
            choice = source.connectChoices.get(target.configKey(true));
        } else if (source.linkedConfigurations != null) {
            for (String key : source.linkedConfigurations.keySet()) {
                WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(key);
                if (config != null) {
                    if (config.connectChoices != null) {
                        choice = config.connectChoices.get(target.configKey(true));
                    }
                }
            }
        }

        if (choice == null) {
            //We didn't find the connect choice
            return 0;
        } else {
            if (choice.intValue() < 0) {
                choice = 20; // Compatibility with older files
            }
            return choice.intValue();
        }
    }


    int getScoreFromVisibility(WifiConfiguration.Visibility visibility, int rssiBoost) {
        int rssiBoost5 = 0;
        int score = 0;

        /**
         * Boost RSSI value of 5GHz bands iff the base value is better than threshold
         * This implements band preference where we prefer 5GHz if RSSI5 is good enough, whereas
         * we prefer 2.4GHz otherwise.
         * Note that 2.4GHz doesn't need a boost since at equal power the RSSI is typically
         * 6-10 dB higher
         */
        if ((visibility.rssi5 + rssiBoost) > WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD) {
            rssiBoost5 = 25;
        } else if ((visibility.rssi5 + rssiBoost)
                > WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD_LOW) {
            rssiBoost5 = 15;
        }

        // Select which band to use so as to score a
        if (visibility.rssi5 + rssiBoost5 > visibility.rssi24) {
            // Prefer a's 5GHz
            score = visibility.rssi5 + rssiBoost5 + rssiBoost;
        } else {
            // Prefer a's 2.4GHz
            score = visibility.rssi24 + rssiBoost;
        }

        return score;
    }

    // Compare WifiConfiguration by RSSI, and return a comparison value in the range [-50, +50]
    // The result represents "approximately" an RSSI difference measured in dBM
    // Adjusted with various parameters:
    // +) current network gets a +15 boost
    // +) 5GHz signal, if they are strong enough, get a +15 or +25 boost, representing the
    // fact that at short range we prefer 5GHz band as it is cleaner of interference and
    // provides for wider channels
    int compareWifiConfigurationsRSSI(WifiConfiguration a, WifiConfiguration b,
                                      String currentConfiguration) {
        int order = 0;

        // Boost used so as to favor current config
        int aRssiBoost = 0;
        int bRssiBoost = 0;

        int scoreA;
        int scoreB;

        // Retrieve the visibility
        WifiConfiguration.Visibility astatus = a.visibility;
        WifiConfiguration.Visibility bstatus = b.visibility;
        if (astatus == null || bstatus == null) {
            // Error visibility wasn't set
            logDbg("compareWifiConfigurations NULL band status!");
            return 0;
        }

        // Apply Hysteresis, boost RSSI of current configuration
        if (null != currentConfiguration) {
            if (a.configKey().equals(currentConfiguration)) {
                aRssiBoost += 15;
            } else if (b.configKey().equals(currentConfiguration)) {
                bRssiBoost += 15;
            }
        }

        if (VDBG)  {
            logDbg("compareWifiConfigurationsRSSI: " + a.configKey()
                    + " " + Integer.toString(astatus.rssi5)
                    + "," + Integer.toString(astatus.rssi24) + "   "
                    + " boost=" + Integer.toString(aRssiBoost)
                    + b.configKey() + " "
                    + Integer.toString(bstatus.rssi5) + ","
                    + Integer.toString(bstatus.rssi24)
                    + " boost=" + Integer.toString(bRssiBoost)
            );
        }

        scoreA = getScoreFromVisibility(astatus, aRssiBoost);
        scoreB = getScoreFromVisibility(bstatus, bRssiBoost);

        // Compare a and b
        // If a score is higher then a > b and the order is descending (negative)
        // If b score is higher then a < b and the order is ascending (positive)
        order = scoreB - scoreA;

        // Normalize the order to [-50, +50]
        if (order > 50) order = 50;
        else if (order < -50) order = -50;

        if (VDBG) {
            String prefer = " = ";
            if (order > 0) {
                prefer = " < "; // Ascending
            } else if (order < 0) {
                prefer = " > "; // Descending
            }
            logDbg("compareWifiConfigurationsRSSI " + a.configKey()
                    + " rssi=(" + a.visibility.rssi24
                    + "," + a.visibility.rssi5
                    + ") num=(" + a.visibility.num24
                    + "," + a.visibility.num5 + ")"
                    + prefer + b.configKey()
                    + " rssi=(" + b.visibility.rssi24
                    + "," + b.visibility.rssi5
                    + ") num=(" + b.visibility.num24
                    + "," + b.visibility.num5 + ")"
                    + " -> " + order);
        }

        return order;
    }


    int compareWifiConfigurationsWithScorer(WifiConfiguration a, WifiConfiguration b) {

        int aRssiBoost = 0;
        int bRssiBoost = 0;

        // Apply Hysteresis : boost RSSI of current configuration before
        // looking up the score
        if (null != mCurrentConfigurationKey) {
            if (a.configKey().equals(mCurrentConfigurationKey)) {
                aRssiBoost += 15;
            } else if (b.configKey().equals(mCurrentConfigurationKey)) {
                bRssiBoost += 15;
            }
        }
        int scoreA = getConfigNetworkScore(a, 3000, aRssiBoost);
        int scoreB = getConfigNetworkScore(a, 3000, bRssiBoost);

        // Both configurations need to have a score for the scorer to be used
        // ...and the scores need to be different:-)
        if (scoreA == WifiNetworkScoreCache.INVALID_NETWORK_SCORE
                || scoreB == WifiNetworkScoreCache.INVALID_NETWORK_SCORE) {
            return 0;
        }

        if (VDBG) {
            String prefer = " = ";
            if (scoreA < scoreB) {
                prefer = " < ";
            } if (scoreA > scoreB) {
                prefer = " > ";
            }
            logDbg("compareWifiConfigurationsWithScorer " + a.configKey()
                    + " rssi=(" + a.visibility.rssi24
                    + "," + a.visibility.rssi5
                    + ") num=(" + a.visibility.num24
                    + "," + a.visibility.num5 + ")"
                    + prefer + b.configKey()
                    + " rssi=(" + b.visibility.rssi24
                    + "," + b.visibility.rssi5
                    + ") num=(" + b.visibility.num24
                    + "," + b.visibility.num5 + ")"
                    + " -> " + Integer.toString(scoreB - scoreA));
        }
        // If scoreA > scoreB, the comparison is descending hence the return value is negative
        return scoreB - scoreA;
    }

    int compareWifiConfigurations(WifiConfiguration a, WifiConfiguration b) {
        int order = 0;
        String lastSelectedConfiguration = mWifiConfigStore.getLastSelectedConfiguration();
        boolean linked = false;

        if ((a.linkedConfigurations != null) && (b.linkedConfigurations != null)
                && (a.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)
                && (b.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)) {
            if ((a.linkedConfigurations.get(b.configKey(true)) != null)
                    && (b.linkedConfigurations.get(a.configKey(true)) != null)) {
                linked = true;
            }
        }

        if (a.ephemeral && b.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " + b.configKey()
                        + " over " + a.configKey());
            }
            return 1; // b is of higher priority - ascending
        }
        if (b.ephemeral && a.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " + a.configKey()
                        + " over " + b.configKey());
            }
            return -1; // a is of higher priority - descending
        }

        // Apply RSSI, in the range [-5, +5]
        // after band adjustment, +n difference roughly corresponds to +10xn dBm
        order = order + compareWifiConfigurationsRSSI(a, b, mCurrentConfigurationKey);

        // If the configurations are not linked, compare by user's choice, only a
        // very high RSSI difference can then override the choice
        if (!linked) {
            int choice;

            choice = getConnectChoice(a, b);
            if (choice > 0) {
                // a is of higher priority - descending
                order = order - choice;
                if (VDBG) {
                    logDbg("compareWifiConfigurations prefers " + a.configKey()
                            + " over " + b.configKey()
                            + " due to user choice order -> " + Integer.toString(order));
                }
            }

            choice = getConnectChoice(b, a);
            if (choice > 0) {
                // a is of lower priority - ascending
                order = order + choice;
                if (VDBG) {
                    logDbg("compareWifiConfigurations prefers " + b.configKey() + " over "
                            + a.configKey() + " due to user choice order ->"
                            + Integer.toString(order));
                }
            }
        }

        if (order == 0) {
            // We don't know anything - pick the last seen i.e. K behavior
            // we should do this only for recently picked configurations
            if (a.priority > b.priority) {
                // a is of higher priority - descending
                if (VDBG) {
                    logDbg("compareWifiConfigurations prefers -1 " + a.configKey() + " over "
                            + b.configKey() + " due to priority");
                }

                order = -1;
            } else if (a.priority < b.priority) {
                // a is of lower priority - ascending
                if (VDBG) {
                    logDbg("compareWifiConfigurations prefers +1 " + b.configKey() + " over "
                            + a.configKey() + " due to priority");
                }
                order = 1;
            }
        }

        String sorder = " == ";
        if (order > 0) {
            sorder = " < ";
        } else if (order < 0) {
            sorder = " > ";
        }

        if (VDBG) {
            logDbg("compareWifiConfigurations Done: " + a.configKey() + sorder
                    + b.configKey() + " order " + Integer.toString(order));
        }

        return order;
    }

    /**
     * attemptRoam function implements the core of the same SSID switching algorithm
     */
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
            // Implement same SSID roaming only for configurations
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

        // Determine which BSSID we want to associate to, taking account
        // relative strength of 5 and 2.4 GHz BSSIDs
        long nowMs = System.currentTimeMillis();
        int bRssiBoost5 = 0;
        int aRssiBoost5 = 0;
        int bRssiBoost = 0;
        int aRssiBoost = 0;
        for (ScanResult b : current.scanResultCache.values()) {

            if ((b.seen == 0) || (b.BSSID == null)
                    || (nowMs - b.seen) > age ) {
                    // TODO: do not apply blacklisting right now so as to leave this
                    // bug as apparent
                    // https://b2.corp.google.com/#/issues/16504012
                    //                    || b.status != ScanResult.ENABLED) {
                continue;
            }

            // Pick first one
            if (a == null) {
                a = b;
                continue;
            }

            // Apply hysteresis: we favor the currentBSSID by giving it a boost
            if (currentBSSID.equals(b.BSSID)) {
                // Reduce the benefit of hysteresis if RSSI <= -75
                if (b.level <= WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD) {
                    bRssiBoost = +6;
                } else {
                    bRssiBoost = +10;
                }
            }
            if (currentBSSID.equals(a.BSSID)) {
                if (a.level <= WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD) {
                    // Reduce the benefit of hysteresis if RSSI <= -75
                    aRssiBoost = +6;
                } else {
                    aRssiBoost = +10;
                }
            }

            // Favor 5GHz: give a boost to 5GHz BSSIDs
            //   Boost the BSSID if it is on 5GHz, above a threshold
            //   But penalize it if it is on 5GHz and below threshold
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

            // Compare the RSSIs after applying the hysteresis boost and the 5GHz
            // boost if applicable
            if (b.level + bRssiBoost + bRssiBoost5 > a.level +aRssiBoost + aRssiBoost5) {
                // b is the better BSSID
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

    /**
     * getNetworkScore()
     *
     * if scorer is present, get the network score of a WifiConfiguration
     *
     * Note: this should be merge with setVisibility
     *
     * @param config
     * @return score
     */
    int getConfigNetworkScore(WifiConfiguration config, int age, int rssiBoost) {

        int score = WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        if (mNetworkScoreCache == null) {
            return score;
        }

        // Get current date
        long nowMs = System.currentTimeMillis();

        // Run thru all cached scan results
        for (ScanResult result : scanResultCache.values()) {
            if ((nowMs - result.seen) < age) {
                int sc = mNetworkScoreCache.getNetworkScore(result, rssiBoost);
                if (sc > score) {
                    score = sc;
                }
            }
        }
        return score;
    }

    /**
     * attemptAutoJoin() function implements the core of the a network switching algorithm
     */
    void attemptAutoJoin() {
        boolean didOverride = false;
        int networkSwitchType = AUTO_JOIN_IDLE;

        String lastSelectedConfiguration = mWifiConfigStore.getLastSelectedConfiguration();

        // Reset the currentConfiguration Key, and set it only if WifiStateMachine and
        // supplicant agree
        mCurrentConfigurationKey = null;
        WifiConfiguration currentConfiguration = mWifiStateMachine.getCurrentWifiConfiguration();

        WifiConfiguration candidate = null;

        // Obtain the subset of recently seen networks
        List<WifiConfiguration> list = mWifiConfigStore.getRecentConfiguredNetworks(3000, false);
        if (list == null) {
            if (VDBG)  logDbg("attemptAutoJoin nothing");
            return;
        }

        // Find the currently connected network: ask the supplicant directly
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
            if (supplicantNetId != currentConfiguration.networkId
                    //https://b.corp.google.com/issue?id=16484607
                    //mark this confition as an error only if the mismatched networkId are valid
                    && supplicantNetId != WifiConfiguration.INVALID_NETWORK_ID
                    && currentConfiguration.networkId != WifiConfiguration.INVALID_NETWORK_ID) {
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
            // If we are associated to a configuration, it will
            // be compared thru the compareNetwork function
            currentNetId = currentConfiguration.networkId;
        }

        /**
         * Run thru all visible configurations without looking at the one we
         * are currently associated to
         * select Best Network candidate from known WifiConfigurations
         */
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
                // Avoid networks disabled because of AUTH failure altogether
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to auto join status "
                            + Integer.toString(config.autoJoinStatus) + " key "
                            + config.configKey(true));
                }
                continue;
            }

            // Try to un-blacklist based on elapsed time
            if (config.blackListTimestamp > 0) {
                long now = System.currentTimeMillis();
                if (now < config.blackListTimestamp) {
                    /**
                     * looks like there was a change in the system clock since we black listed, and
                     * timestamp is not meaningful anymore, hence lose it.
                     * this event should be rare enough so that we still want to lose the black list
                     */
                    config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                } else {
                    if ((now - config.blackListTimestamp) > loseBlackListHardMilli) {
                        // Reenable it after 18 hours, i.e. next day
                        config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                    } else if ((now - config.blackListTimestamp) > loseBlackListSoftMilli) {
                        // Lose blacklisting due to bad link
                        config.setAutoJoinStatus(config.autoJoinStatus - 8);
                    }
                }
            }

            // Try to unblacklist based on good visibility
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
                // If the network is simply temporary disabled, don't allow reconnect until
                // RSSI becomes good enough
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
                // Network is blacklisted, skip
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
                // Don't try to autojoin a network that is too far
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

                int scorerOrder = compareWifiConfigurationsWithScorer(candidate, config);
                int order = compareWifiConfigurations(candidate, config);

                if (scorerOrder * order < 0) {
                    // For debugging purpose, remember that an override happened
                    // during that autojoin Attempt
                    didOverride = true;
                    candidate.numScorerOverride++;
                    config.numScorerOverride++;
                }

                if (scorerOrder != 0) {
                    // If the scorer came up with a result then use the scorer's result, else use
                    // the order provided by the base comparison function
                    order = scorerOrder;
                }

                // The lastSelectedConfiguration is the configuration the user has manually selected
                // thru thru WifiPicker, or that a 3rd party app asked us to connect to via the
                // enableNetwork with disableOthers=true WifiManager API
                // As this is a direct user choice, we strongly prefer this configuration,
                // hence give +/-100
                if ((lastSelectedConfiguration != null)
                        && candidate.configKey().equals(lastSelectedConfiguration)) {
                    // candidate is the last selected configuration,
                    // so keep it above connect choices (+/-60) and
                    // above RSSI/scorer based selection of linked configuration (+/- 50)
                    // by reducing order by -100
                    order = order - 100;
                    if (VDBG)   {
                        logDbg("  prefers -100 " + candidate.configKey()
                                + " over " + config.configKey()
                                + " because it is the last selected -> "
                                + Integer.toString(order));
                    }
                } else if ((lastSelectedConfiguration != null)
                        && config.configKey().equals(lastSelectedConfiguration)) {
                    // config is the last selected configuration,
                    // so keep it above connect choices (+/-60) and
                    // above RSSI/scorer based selection of linked configuration (+/- 50)
                    // by increasing order by +100
                    order = order + 100;
                    if (VDBG)   {
                        logDbg("  prefers +100 " + config.configKey()
                                + " over " + candidate.configKey()
                                + " because it is the last selected -> "
                                + Integer.toString(order));
                    }
                }

                if (order > 0) {
                    // Ascending : candidate < config
                    candidate = config;
                }
            }
        }

        /* Wait for VPN to be available on the system to make use of this code
        // Now, go thru scan result to try finding a better untrusted network
        if (mNetworkScoreCache != null) {
            int rssi5 = WifiConfiguration.INVALID_RSSI;
            int rssi24 = WifiConfiguration.INVALID_RSSI;
            WifiConfiguration.Visibility visibility;
            if (candidate != null) {
                rssi5 = candidate.visibility.rssi5;
                rssi24 = candidate.visibility.rssi24;
            }

            // Get current date
            long nowMs = System.currentTimeMillis();

            // Look for untrusted scored network only if the current candidate is bad
            if (rssi5 < -60 && rssi24 < -70) {
                for (ScanResult result : scanResultCache.values()) {
                    if ((nowMs - result.seen) < 3000) {
                        int score = mNetworkScoreCache.getNetworkScore(result);
                        if (score > 0) {
                            // Try any arbitrary formula for now, adding apple and oranges,
                            // i.e. adding network score and "dBm over noise"
                           if (result.is24GHz()) {
                                if ((result.level + score) > (rssi24 -40)) {
                                    // Force it as open, TBD should we otherwise verify that this
                                    // BSSID only supports open??
                                    result.capabilities = "";

                                    // Switch to this scan result
                                    candidate =
                                          mWifiConfigStore.wifiConfigurationFromScanResult(result);
                                    candidate.ephemeral = true;
                                }
                           } else {
                                if ((result.level + score) > (rssi5 -30)) {
                                    // Force it as open, TBD should we otherwise verify that this
                                    // BSSID only supports open??
                                    result.capabilities = "";

                                    // Switch to this scan result
                                    candidate =
                                          mWifiConfigStore.wifiConfigurationFromScanResult(result);
                                    candidate.ephemeral = true;
                                }
                           }
                        }
                    }
                }
            }
        }*/

        /**
         *  If candidate is found, check the state of the connection so as
         *  to decide if we should be acting on this candidate and switching over
         */
        int networkDelta = compareNetwork(candidate);
        if (DBG && candidate != null) {
            logDbg("attemptAutoJoin compare SSID candidate : delta="
                    + Integer.toString(networkDelta) + " "
                    + candidate.configKey()
                    + " linked=" + (currentConfiguration != null
                    && currentConfiguration.isLinked(candidate)));
        }

        /**
         * Ask WifiStateMachine permission to switch :
         * if user is currently streaming voice traffic,
         * then we should not be allowed to switch regardless of the delta
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
                if (didOverride) {
                    candidate.numScorerOverrideAndSwitchedNetwork++;
                }
                mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_CONNECT,
                        candidate.networkId, networkSwitchType, candidate);
            }
        }

        if (networkSwitchType == AUTO_JOIN_IDLE) {
            // Attempt same WifiConfiguration roaming
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

