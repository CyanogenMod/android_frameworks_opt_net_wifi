/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.Log;
import android.util.Pair;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * This Class is a Work-In-Progress, intended behavior is as follows:
 * Essentially this class automates a user toggling 'Airplane Mode' when WiFi "won't work".
 * IF each available saved network has failed connecting more times than the FAILURE_THRESHOLD
 * THEN Watchdog will restart Supplicant, wifi driver and return WifiStateMachine to InitialState.
 */
public class WifiLastResortWatchdog {
    private static final String TAG = "WifiLastResortWatchdog";
    private static final boolean VDBG = false;
    private static final boolean DBG = true;
    /**
     * Association Failure code
     */
    public static final int FAILURE_CODE_ASSOCIATION = 1;
    /**
     * Authentication Failure code
     */
    public static final int FAILURE_CODE_AUTHENTICATION = 2;
    /**
     * Dhcp Failure code
     */
    public static final int FAILURE_CODE_DHCP = 3;
    /**
     * Maximum number of scan results received since we last saw a BSSID.
     * If it is not seen before this limit is reached, the network is culled
     */
    public static final int MAX_BSSID_AGE = 10;
    /**
     * BSSID used to increment failure counts against ALL bssids associated with a particular SSID
     */
    public static final String BSSID_ANY = "any";
    /**
     * Failure count that each available networks must meet to possibly trigger the Watchdog
     */
    public static final int FAILURE_THRESHOLD = 7;
    /**
     * Cached WifiConfigurations of available networks seen within MAX_BSSID_AGE scan results
     * Key:BSSID, Value:Counters of failure types
     */
    private Map<String, AvailableNetworkFailureCount> mRecentAvailableNetworks = new HashMap<>();
    /**
     * Map of SSID to <FailureCount, AP count>, used to count failures & number of access points
     * belonging to an SSID.
     */
    private Map<String, Pair<AvailableNetworkFailureCount, Integer>> mSsidFailureCount =
            new HashMap<>();

    /**
     * Refreshes recentAvailableNetworks with the latest available networks
     * Adds new networks, removes old ones that have timed out. Should be called after Wifi
     * framework decides what networks it is potentially connecting to.
     * @param availableNetworks ScanDetail & Config list of potential connection
     * candidates
     */
    public void updateAvailableNetworks(
            List<Pair<ScanDetail, WifiConfiguration>> availableNetworks) {
        if (VDBG) Log.v(TAG, "updateAvailableNetworks: size = " + availableNetworks.size());
        // Add new networks to mRecentAvailableNetworks
        if (availableNetworks != null) {
            for (Pair<ScanDetail, WifiConfiguration> pair : availableNetworks) {
                final ScanDetail scanDetail = pair.first;
                final WifiConfiguration config = pair.second;
                ScanResult scanResult = scanDetail.getScanResult();
                if (scanResult == null) continue;
                String bssid = scanResult.BSSID;
                String ssid = "\"" + scanDetail.getSSID() + "\"";
                if (VDBG) Log.v(TAG, " " + bssid + ": " + scanDetail.getSSID());
                // Cache the scanResult & WifiConfig
                AvailableNetworkFailureCount availableNetworkFailureCount =
                        mRecentAvailableNetworks.get(bssid);
                if (availableNetworkFailureCount == null) {
                    // New network is available
                    availableNetworkFailureCount = new AvailableNetworkFailureCount(config);
                    availableNetworkFailureCount.ssid = ssid;

                    // Count AP for this SSID
                    Pair<AvailableNetworkFailureCount, Integer> ssidFailsAndApCount =
                            mSsidFailureCount.get(ssid);
                    if (ssidFailsAndApCount == null) {
                        // This is a new SSID, create new FailureCount for it and set AP count to 1
                        ssidFailsAndApCount = Pair.create(new AvailableNetworkFailureCount(config),
                                1);
                    } else {
                        final Integer numberOfAps = ssidFailsAndApCount.second;
                        // This is not a new SSID, increment the AP count for it
                        ssidFailsAndApCount = Pair.create(ssidFailsAndApCount.first,
                                numberOfAps + 1);
                    }
                    mSsidFailureCount.put(ssid, ssidFailsAndApCount);
                }
                // refresh config
                availableNetworkFailureCount.config = config;
                // If we saw a network, set its Age to -1 here, aging iteration will set it to 0
                availableNetworkFailureCount.age = -1;
                mRecentAvailableNetworks.put(bssid, availableNetworkFailureCount);
            }
        }

        // Iterate through available networks updating timeout counts & removing networks.
        Iterator<Map.Entry<String, AvailableNetworkFailureCount>> it =
                mRecentAvailableNetworks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AvailableNetworkFailureCount> entry = it.next();
            if (entry.getValue().age < MAX_BSSID_AGE - 1) {
                entry.getValue().age++;
            } else {
                // Decrement this SSID : AP count
                String ssid = entry.getValue().ssid;
                Pair<AvailableNetworkFailureCount, Integer> ssidFails =
                            mSsidFailureCount.get(ssid);
                if (ssidFails != null) {
                    Integer apCount = ssidFails.second - 1;
                    if (apCount > 0) {
                        ssidFails = Pair.create(ssidFails.first, apCount);
                        mSsidFailureCount.put(ssid, ssidFails);
                    } else {
                        mSsidFailureCount.remove(ssid);
                    }
                } else {
                    if (DBG) {
                        Log.d(TAG, "updateAvailableNetworks: SSID to AP count mismatch for "
                                + ssid);
                    }
                }
                it.remove();
            }
        }
        if (VDBG) Log.v(TAG, toString());
    }

    /**
     * Increments the failure reason count for the given bssid. Performs a check to see if we have
     * exceeded a failure threshold for all available networks, and executes the last resort restart
     * @param bssid of the network that has failed connection, can be "any"
     * @param reason Message id from WifiStateMachine for this failure
     * @return true if watchdog triggers, returned for test visibility
     */
    public boolean noteConnectionFailureAndTriggerIfNeeded(String ssid, String bssid, int reason) {
        if (VDBG) {
            Log.v(TAG, "noteConnectionFailureAndTriggerIfNeeded: [" + ssid + ", " + bssid + ", "
                    + reason + "]");
        }
        // Update failure count for the failing network
        updateFailureCountForNetwork(ssid, bssid, reason);
        return false;
    }

    /**
     * Increments the failure reason count for the given network, in 'mSsidFailureCount'
     * Failures are counted per SSID, either; by using the ssid string when the bssid is "any"
     * or by looking up the ssid attached to a specific bssid
     * An unused set of counts is also kept which is bssid specific, in 'mRecentAvailableNetworks'
     * @param ssid of the network that has failed connection
     * @param bssid of the network that has failed connection, can be "any"
     * @param reason Message id from WifiStateMachine for this failure
     */
    private void updateFailureCountForNetwork(String ssid, String bssid, int reason) {
        if (VDBG) {
            Log.v(TAG, "updateFailureCountForNetwork: [" + ssid + ", " + bssid + ", "
                    + reason + "]");
        }
        if (BSSID_ANY.equals(bssid)) {
            incrementSsidFailureCount(ssid, reason);
        } else {
            // Bssid count is actually unused except for logging purposes
            // SSID count is incremented within the BSSID counting method
            incrementBssidFailureCount(ssid, bssid, reason);
        }
    }

    /**
     * Update the per-SSID failure count
     * @param ssid the ssid to increment failure count for
     * @param reason the failure type to increment count for
     */
    private void incrementSsidFailureCount(String ssid, int reason) {
        Pair<AvailableNetworkFailureCount, Integer> ssidFails = mSsidFailureCount.get(ssid);
        if (ssidFails == null) {
            if (DBG) {
                Log.v(TAG, "updateFailureCountForNetwork: No networks for ssid = " + ssid);
            }
            return;
        }
        AvailableNetworkFailureCount failureCount = ssidFails.first;
        failureCount.incrementFailureCount(reason);
    }

    /**
     * Update the per-BSSID failure count
     * @param bssid the bssid to increment failure count for
     * @param reason the failure type to increment count for
     */
    private void incrementBssidFailureCount(String ssid, String bssid, int reason) {
        AvailableNetworkFailureCount availableNetworkFailureCount =
                mRecentAvailableNetworks.get(bssid);
        if (availableNetworkFailureCount == null) {
            if (DBG) {
                Log.d(TAG, "updateFailureCountForNetwork: Unable to find Network [" + ssid
                        + ", " + bssid + "]");
            }
            return;
        }
        if (!availableNetworkFailureCount.ssid.equals(ssid)) {
            if (DBG) {
                Log.d(TAG, "updateFailureCountForNetwork: Failed connection attempt has"
                        + " wrong ssid. Failed [" + ssid + ", " + bssid + "], buffered ["
                        + availableNetworkFailureCount.ssid + ", " + bssid + "]");
            }
            return;
        }
        if (availableNetworkFailureCount.config == null) {
            if (VDBG) {
                Log.v(TAG, "updateFailureCountForNetwork: network has no config ["
                        + ssid + ", " + bssid + "]");
            }
        }
        availableNetworkFailureCount.incrementFailureCount(reason);
        incrementSsidFailureCount(ssid, reason);
    }

    /**
     * Clear failure counts for each network in recentAvailableNetworks
     */
    private void clearAllFailureCounts() {
        if (VDBG) Log.v(TAG, "clearAllFailureCounts.");
        for (Map.Entry<String, AvailableNetworkFailureCount> entry
                : mRecentAvailableNetworks.entrySet()) {
            final AvailableNetworkFailureCount failureCount = entry.getValue();
            entry.getValue().resetCounts();
        }
        for (Map.Entry<String, Pair<AvailableNetworkFailureCount, Integer>> entry
                : mSsidFailureCount.entrySet()) {
            final AvailableNetworkFailureCount failureCount = entry.getValue().first;
            failureCount.resetCounts();
        }
    }
    /**
     * Gets the buffer of recently available networks
     */
    Map<String, AvailableNetworkFailureCount> getRecentAvailableNetworks() {
        return mRecentAvailableNetworks;
    }

    /**
     * Prints all networks & counts within mRecentAvailableNetworks to string
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("WifiLastResortWatchdog: " + mRecentAvailableNetworks.size() + " networks...");
        for (Map.Entry<String, AvailableNetworkFailureCount> entry
                : mRecentAvailableNetworks.entrySet()) {
            sb.append("\n " + entry.getKey() + ": " + entry.getValue());
        }
        sb.append("\nmSsidFailureCount:");
        for (Map.Entry<String, Pair<AvailableNetworkFailureCount, Integer>> entry :
                mSsidFailureCount.entrySet()) {
            final AvailableNetworkFailureCount failureCount = entry.getValue().first;
            final Integer apCount = entry.getValue().second;
            sb.append("\n" + entry.getKey() + ": " + apCount + ", "
                    + failureCount.toString());
        }
        return sb.toString();
    }

    /**
     * @param bssid bssid to check the failures for
     * @return true if any failure count is over FAILURE_THRESHOLD
     */
    public boolean isOverFailureThreshold(String bssid) {
        if ((getFailureCount(bssid, FAILURE_CODE_ASSOCIATION) >= FAILURE_THRESHOLD)
                || (getFailureCount(bssid, FAILURE_CODE_AUTHENTICATION) >= FAILURE_THRESHOLD)
                || (getFailureCount(bssid, FAILURE_CODE_DHCP) >= FAILURE_THRESHOLD)) {
            return true;
        }
        return false;
    }

    /**
     * Get the failure count for a specific bssid. This actually checks the ssid attached to the
     * BSSID and returns the SSID count
     * @param reason failure reason to get count for
     */
    public int getFailureCount(String bssid, int reason) {
        AvailableNetworkFailureCount availableNetworkFailureCount =
                mRecentAvailableNetworks.get(bssid);
        if (availableNetworkFailureCount == null) {
            return 0;
        }
        String ssid = availableNetworkFailureCount.ssid;
        Pair<AvailableNetworkFailureCount, Integer> ssidFails = mSsidFailureCount.get(ssid);
        if (ssidFails == null) {
            if (DBG) {
                Log.d(TAG, "getFailureCount: Could not find SSID count for " + ssid);
            }
            return 0;
        }
        final AvailableNetworkFailureCount failCount = ssidFails.first;
        switch (reason) {
            case FAILURE_CODE_ASSOCIATION:
                return failCount.associationRejection;
            case FAILURE_CODE_AUTHENTICATION:
                return failCount.authenticationFailure;
            case FAILURE_CODE_DHCP:
                return failCount.dhcpFailure;
            default:
                return 0;
        }
    }

    /**
     * This class holds the failure counts for an 'available network' (one of the potential
     * candidates for connection, as determined by framework).
     */
    public static class AvailableNetworkFailureCount {
        /**
         * WifiConfiguration associated with this network. Can be null for Ephemeral networks
         */
        public WifiConfiguration config;
        /**
        * SSID of the network (from ScanDetail)
        */
        public String ssid = "";
        /**
         * Number of times network has failed due to Association Rejection
         */
        public int associationRejection = 0;
        /**
         * Number of times network has failed due to Authentication Failure or SSID_TEMP_DISABLED
         */
        public int authenticationFailure = 0;
        /**
         * Number of times network has failed due to DHCP failure
         */
        public int dhcpFailure = 0;
        /**
         * Number of scanResults since this network was last seen
         */
        public int age = 0;

        AvailableNetworkFailureCount(WifiConfiguration config) {
            config = config;
        }

        /**
         * @param reason failure reason to increment count for
         */
        public void incrementFailureCount(int reason) {
            switch (reason) {
                case FAILURE_CODE_ASSOCIATION:
                    associationRejection++;
                    break;
                case FAILURE_CODE_AUTHENTICATION:
                    authenticationFailure++;
                    break;
                case FAILURE_CODE_DHCP:
                    dhcpFailure++;
                    break;
                default: //do nothing
            }
        }

        /**
         * Set all failure counts for this network to 0
         */
        void resetCounts() {
            associationRejection = 0;
            authenticationFailure = 0;
            dhcpFailure = 0;
        }

        public String toString() {
            return  ssid + ", HasEverConnected: " + ((config != null)
                    ? config.getNetworkSelectionStatus().getHasEverConnected() : false)
                    + ", Failures: {"
                    + "Assoc: " + associationRejection
                    + ", Auth: " + authenticationFailure
                    + ", Dhcp: " + dhcpFailure
                    + "}"
                    + ", Age: " + age;
        }
    }
}
