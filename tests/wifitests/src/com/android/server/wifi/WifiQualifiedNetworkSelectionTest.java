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
 * limitations under the License
 */

package com.android.server.wifi;

import static com.android.server.wifi.WifiConfigurationUtil.SECURITY_NONE;
import static com.android.server.wifi.WifiConfigurationUtil.SECURITY_PSK;
import static com.android.server.wifi.WifiConfigurationUtil.generateWifiConfig;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;
import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link com.android.server.wifi.WifiQualifiedNetworkSelector}.
 */
@SmallTest
public class WifiQualifiedNetworkSelectionTest {


    @Before
    public void setUp() throws Exception {
        mWifiStateMachine = getWifiStateMachine();
        mWifinative = getWifiNative();
        mResource = getResource();
        mScoreManager = getNetworkScoreManager();
        mContext = getContext();
        mWifiConfigStore = getWifiConfigStore();
        mWifiInfo = getWifiInfo();
        mWifiQualifiedNetworkSelector = new WifiQualifiedNetworkSelector(mWifiConfigStore, mContext,
                mWifiStateMachine, mWifiInfo);
        mWifiQualifiedNetworkSelector.enableVerboseLogging(1);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private WifiStateMachine mWifiStateMachine;
    private WifiQualifiedNetworkSelector mWifiQualifiedNetworkSelector = null;
    private WifiConfigStore mWifiConfigStore = null;
    private WifiNative mWifinative = null;
    private Context mContext;
    private Resources mResource;
    private NetworkScoreManager mScoreManager;
    private WifiInfo mWifiInfo;

    private WifiStateMachine getWifiStateMachine() {
        WifiStateMachine wifiStateMachine = mock(WifiStateMachine.class);

        return wifiStateMachine;
    }

    private List<ScanDetail> getScanDetails(String[] ssids, String[] bssids, int[] frequencies,
            String[] caps, int[] levels) {
        List<ScanDetail> scanDetailList =  new ArrayList<ScanDetail>();
        long timeStamp = System.currentTimeMillis();
        for (int index = 0; index < ssids.length; index++) {
            ScanDetail scanDetail = new ScanDetail(WifiSsid.createFromAsciiEncoded(ssids[index]),
                    bssids[index], caps[index], levels[index], frequencies[index], timeStamp, 0);
            scanDetailList.add(scanDetail);
        }
        return scanDetailList;
    }

    Context getContext() {
        Context context = mock(Context.class);
        Resources resource = mock(Resources.class);

        when(context.getResources()).thenReturn(mResource);
        when(context.getSystemService(Context.NETWORK_SCORE_SERVICE)).thenReturn(mScoreManager);
        return context;
    }

    Resources getResource() {
        Resources resource = mock(Resources.class);

        when(resource.getInteger(R.integer.config_wifi_framework_SECURITY_AWARD)).thenReturn(80);
        when(resource.getInteger(R.integer.config_wifi_framework_RSSI_SCORE_OFFSET)).thenReturn(85);
        when(resource.getInteger(R.integer.config_wifi_framework_SAME_BSSID_AWARD)).thenReturn(24);
        when(resource.getInteger(R.integer.config_wifi_framework_LAST_SELECTION_AWARD))
                .thenReturn(480);
        when(resource.getInteger(R.integer.config_wifi_framework_PASSPOINT_SECURITY_AWARD))
                .thenReturn(40);
        when(resource.getInteger(R.integer.config_wifi_framework_SECURITY_AWARD)).thenReturn(80);
        return resource;
    }

    WifiNative getWifiNative() {
        WifiNative wifiNative = mock(WifiNative.class);

        return wifiNative;
    }

    NetworkScoreManager getNetworkScoreManager() {
        NetworkScoreManager networkScoreManager = mock(NetworkScoreManager.class);

        return networkScoreManager;
    }

    WifiInfo getWifiInfo() {
        WifiInfo wifiInfo = mock(WifiInfo.class);

        //simulate a disconnected state
        when(wifiInfo.is24GHz()).thenReturn(true);
        when(wifiInfo.is5GHz()).thenReturn(false);
        when(wifiInfo.getRssi()).thenReturn(-70);
        when(wifiInfo.getNetworkId()).thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        when(wifiInfo.getBSSID()).thenReturn(null);
        when(wifiInfo.getNetworkId()).thenReturn(-1);
        return wifiInfo;
    }

    WifiConfigStore getWifiConfigStore() {
        WifiConfigStore wifiConfigStore = mock(WifiConfigStore.class);
        wifiConfigStore.thresholdSaturatedRssi24 = new AtomicInteger(
                WifiQualifiedNetworkSelector.RSSI_SATURATION_2G_BAND);
        wifiConfigStore.bandAward5Ghz = new AtomicInteger(
                WifiQualifiedNetworkSelector.BAND_AWARD_5GHz);
        wifiConfigStore.currentNetworkBoost = new AtomicInteger(
                WifiQualifiedNetworkSelector.SAME_NETWORK_AWARD);
        wifiConfigStore.thresholdQualifiedRssi5 = new AtomicInteger(
                WifiQualifiedNetworkSelector.QUALIFIED_RSSI_5G_BAND);
        wifiConfigStore.thresholdMinimumRssi24 = new AtomicInteger(
                WifiQualifiedNetworkSelector.MINIMUM_2G_ACCEPT_RSSI);
        wifiConfigStore.thresholdMinimumRssi5 = new AtomicInteger(
                WifiQualifiedNetworkSelector.MINIMUM_5G_ACCEPT_RSSI);

        when(wifiConfigStore.getEnableNewNetworkSelectionWhenAssociated()).thenReturn(true);
        return wifiConfigStore;
    }

    /**
     * This API is used to generate multiple simulated saved configurations used for test
     * @param ssid array of SSID of saved configuration
     * @param security array  of securities of  saved configuration
     * @return generated new array of configurations based on input
     */
    private WifiConfiguration[] generateWifiConfigurations(String[] ssid, int[] security) {
        if (ssid == null || security == null || ssid.length != security.length
                || ssid.length == 0) {
            return null;
        }

        WifiConfiguration[] configs = new WifiConfiguration[ssid.length];
        for (int index = 0; index < ssid.length; index++) {
            configs[index] = generateWifiConfig(index, 0, ssid[index], false, true, null , null,
                    security[index]);
        }

        return configs;
    }

    /**
     * Case #1
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled
     * test1 is @ 2GHz with RSSI -50
     * test2 is @ 5Ghz with RSSI -65
     * Expected behavior: test2 is chosen
     */
    @Test
    public void chooseNetworkDisconnect5GOver2GTest() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] frequencies = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-50, -65};
        int[] security = {SECURITY_PSK, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        when(mWifiStateMachine.getScanResultsListNoCopyUnsync()).thenReturn(scanDetails);
        when(mWifiStateMachine.isDisconnected()).thenReturn(true);

        final List<WifiConfiguration> savedNetwork =
                Arrays.asList(generateWifiConfigurations(ssids, security));

        WifiConfiguration configuration1 = savedNetwork.get(0);
        WifiConfiguration configuration2 = savedNetwork.get(1);

        when(mWifiConfigStore.getWifiConfiguration(anyInt()))
                .then(new AnswerWithArguments<Boolean>() {
                        public WifiConfiguration answer(int netId) {
                            if (netId >= 0 && netId < savedNetwork.size()) {
                                return savedNetwork.get(netId);
                            } else {
                                return null;
                            }
                        }
                });

        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);

        List<WifiConfiguration> associateWithScanResult1 = new ArrayList<WifiConfiguration>();
        associateWithScanResult1.add(configuration1);

        List<WifiConfiguration> associateWithScanResult2 = new ArrayList<WifiConfiguration>();
        associateWithScanResult2.add(configuration2);

        when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(0))).thenReturn(
                associateWithScanResult1);
        when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(1))).thenReturn(
                associateWithScanResult2);
        ScanResult chosenScanResult = scanDetails.get(scanDetails.size() - 1).getScanResult();

        mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false);
        assertEquals("choose the wrong SSID", chosenScanResult.SSID,
                mWifiQualifiedNetworkSelector.getConnetionTargetNetwork().SSID);
    }


    /**
     * Case #2
     * In this test. we simulate following scenario
     * There are three saved networks: test1, test2 and test3. Now user select the network test3
     */
    @Test
    public void userSelectsNetworkForFirstTime() {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        int[] security = {SECURITY_PSK, SECURITY_PSK, SECURITY_NONE};

        final WifiConfiguration[] configs = generateWifiConfigurations(ssids, security);

        when(mWifiConfigStore.getWifiConfiguration(anyInt()))
                .then(new AnswerWithArguments<Boolean>() {
                    public WifiConfiguration answer(int netId) {
                        if (netId >= 0 && netId < configs.length) {
                            return configs[netId];
                        } else {
                            return null;
                        }
                    }
                });

        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(Arrays.asList(configs));
        for (WifiConfiguration network : configs) {
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            status.setSeenInLastQualifiedNetworkSelection(true);
        }

        mWifiQualifiedNetworkSelector.userSelectNetwork(configs.length - 1, true);
        String key = configs[configs.length - 1].configKey();
        for (int index = 0; index < configs.length; index++) {
            WifiConfiguration config = configs[index];
            WifiConfiguration.NetworkSelectionStatus status = config.getNetworkSelectionStatus();
            if (index == configs.length - 1) {
                assertEquals("User selected network should not have prefernce over it", null,
                        status.getConnectChoice());
            } else {
                assertEquals("Wrong user preference", key, status.getConnectChoice());
            }
        }
    }

    /**
     * case #3
     * In this test, we simulate following scenario:
     * There are three networks: test1, test2, test3 and test3 is the user preference
     * All three networks are enabled
     * test1 is @ 2.4GHz with RSSI -50 PSK
     * test2 is @ 5Ghz with RSSI -65 PSK
     * test3 is @ 2.4GHz with RSSI -55
     * Expected behavior: test3 is chosen
     */
    @Test
    public void chooseUserPreferredNetwork() {
        //Generate mocked saved configurations
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        int[] security = {SECURITY_PSK, SECURITY_PSK, SECURITY_NONE};
        final WifiConfiguration[] configs = generateWifiConfigurations(ssids, security);
        for (WifiConfiguration network : configs) {
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            status.setSeenInLastQualifiedNetworkSelection(true);
        }

        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(Arrays.asList(configs));

        when(mWifiConfigStore.getWifiConfiguration(anyInt()))
                .then(new AnswerWithArguments<Boolean>() {
                        public WifiConfiguration answer(int netId) {
                            if (netId >= 0 && netId < configs.length) {
                                return configs[netId];
                            } else {
                                return null;
                            }
                        }
                });

        //set user preference
        mWifiQualifiedNetworkSelector.userSelectNetwork(ssids.length - 1, true);
        //Generate mocked recent scan results
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5"};
        int[] frequencies = {2437, 5180, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]", "NONE"};
        int[] levels = {-50, -65, -55};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        when(mWifiStateMachine.getScanResultsListNoCopyUnsync()).thenReturn(scanDetails);
        when(mWifiStateMachine.isDisconnected()).thenReturn(true);

        List<WifiConfiguration> associateWithScanResult1 = new ArrayList<WifiConfiguration>();
        associateWithScanResult1.add(configs[0]);

        List<WifiConfiguration> associateWithScanResult2 = new ArrayList<WifiConfiguration>();
        associateWithScanResult2.add(configs[1]);

        List<WifiConfiguration> associateWithScanResult3 = new ArrayList<WifiConfiguration>();
        associateWithScanResult3.add(configs[2]);

        when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(0))).thenReturn(
                associateWithScanResult1);
        when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(1))).thenReturn(
                associateWithScanResult2);
        when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(2))).thenReturn(
                associateWithScanResult3);
        ScanResult chosenScanResult = scanDetails.get(scanDetails.size() - 1).getScanResult();
        when(mWifiConfigStore.getWifiConfiguration(configs[2].configKey()))
                .thenReturn(configs[2]);
        mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false);
        assertEquals("choose the wrong SSID", chosenScanResult.SSID,
                mWifiQualifiedNetworkSelector.getConnetionTargetNetwork().SSID);
    }
}
