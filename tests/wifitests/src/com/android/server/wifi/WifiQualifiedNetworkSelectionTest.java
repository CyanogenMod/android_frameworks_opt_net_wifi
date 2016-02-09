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

import static com.android.server.wifi.WifiConfigurationUtil.SECURITY_EAP;
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
import android.net.wifi.WifiEnterpriseConfig;
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
        mResource = getResource();
        mScoreManager = getNetworkScoreManager();
        mContext = getContext();
        mWifiConfigStore = getWifiConfigStore();
        mWifiInfo = getWifiInfo();
        mWifiQualifiedNetworkSelector = new WifiQualifiedNetworkSelector(mWifiConfigStore, mContext,
                mWifiInfo);
        mWifiQualifiedNetworkSelector.enableVerboseLogging(1);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private WifiQualifiedNetworkSelector mWifiQualifiedNetworkSelector = null;
    private WifiConfigStore mWifiConfigStore = null;
    private Context mContext;
    private Resources mResource;
    private NetworkScoreManager mScoreManager;
    private WifiInfo mWifiInfo;
    private static final String[] DEFAULT_SSIDS = {"\"test1\"", "\"test2\""};
    private static final String[] DEFAULT_BSSIDS = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
    private static final String TAG = "QNS Unit Test";

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
        when(resource.getInteger(R.integer.config_wifi_framework_RSSI_SCORE_SLOPE)).thenReturn(4);
        return resource;
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
     * set configuration to a passpoint configuration
     * @param config
     */
    private void setConfigPasspoint(WifiConfiguration config) {
        config.FQDN = "android.qns.unitTest";
        config.providerFriendlyName = "android.qns.unitTest";
        WifiEnterpriseConfig  enterpriseConfig = mock(WifiEnterpriseConfig.class);
        when(enterpriseConfig.getEapMethod()).thenReturn(WifiEnterpriseConfig.Eap.PEAP);

    }

    /**
     * add the Confgurations to WifiConfigStore (configurestore can take them out according to the
     * networkd ID)
     * @param configs input confgures need to be added to WifiConfigureStore
     */
    private void prepareConfigStore(final WifiConfiguration[] configs) {
        when(mWifiConfigStore.getWifiConfiguration(anyInt()))
                .then(new AnswerWithArguments() {
                    public WifiConfiguration answer(int netId) {
                        if (netId >= 0 && netId < configs.length) {
                            return configs[netId];
                        } else {
                            return null;
                        }
                    }
                });
    }

    /**
     * Link scan results to the saved configurations
     * @param configs saved configurations
     * @param scanDetails come in scan results
     */
    private void scanResultLinkConfiguration(WifiConfiguration[] configs,
            List<ScanDetail> scanDetails) {

        int index = 0;
        for (WifiConfiguration config : configs) {
            List<WifiConfiguration> associateWithScanResult = new ArrayList<WifiConfiguration>();
            associateWithScanResult.add(config);
            when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(index)))
                    .thenReturn(associateWithScanResult);
            index++;
        }
    }

    /**
     * verify whether the chosen configuration matched with the expected chosen scan result
     * @param chosenScanResult the expected chosen scan result
     * @param candidate the chosen configuration
     */
    private void verifySelectedResult(ScanResult chosenScanResult, WifiConfiguration candidate) {
        ScanResult candidateScan = candidate.getNetworkSelectionStatus().getCandidate();
        assertEquals("choose the wrong SSID", chosenScanResult.SSID, candidate.SSID);
        assertEquals("choose the wrong BSSID", chosenScanResult.BSSID, candidateScan.BSSID);
    }

    // QNS test under disconnected State
    /**
     * Case #1    choose 2GHz stronger RSSI test
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled, encrypted and at 2.4 GHz
     * test1 is with RSSI -70 test2 is with RSSI -60
     * Expected behavior: test2 is chosen
     */
    @Test
    public void chooseNetworkDisconnected2GHighestRssi() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 2417};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-70, -60};
        int[] security = {SECURITY_PSK, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);

        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);

        scanResultLinkConfiguration(savedConfigs, scanDetails);

        ScanResult chosenScanResult = scanDetails.get(scanDetails.size() - 1).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #2    choose 5GHz Stronger RSSI Test
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled, encrypted and at 5 GHz
     * test1 is with RSSI -70 test2 is with RSSI -60
     * Expected behavior: test2 is chosen
     */
    @Test
    public void chooseNetworkDisconnected5GHighestRssi() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {5180, 5610};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-70, -60};
        int[] security = {SECURITY_PSK, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);

        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);

        ScanResult chosenScanResult = scanDetails.get(scanDetails.size() - 1).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #3    5GHz over 2GHz bonus Test
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled
     * test1 is @ 2GHz with RSSI -60
     * test2 is @ 5Ghz with RSSI -65
     * Expected behavior: test2 is chosen due to 5GHz bonus
     */
    @Test
    public void chooseNetworkDisconnect5GOver2GTest() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-60, -65};
        int[] security = {SECURITY_PSK, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);
        ScanResult chosenScanResult = scanDetails.get(scanDetails.size() - 1).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #4    2GHz over 5GHz dur to 5GHz signal too weak test
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled
     * test1 is @ 2GHz with RSSI -60
     * test2 is @ 5Ghz with RSSI -75
     * Expected behavior: test1 is chosen due to 5GHz signal is too weak (5GHz bonus can not
     * compensate)
     */
    @Test
    public void chooseNetworkDisconnect2GOver5GTest() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-60, -75};
        int[] security = {SECURITY_PSK, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #5    2GHz signal Saturation test
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled
     * test1 is @ 2GHz with RSSI -50
     * test2 is @ 5Ghz with RSSI -65
     * Expected behavior: test2 is chosen. Although the RSSI delta here is 15 too, because 2GHz RSSI
     * saturates at -60, the real RSSSI delta is only 5, which is less than 5GHz bonus
     */
    @Test
    public void chooseNetworkDisconnect2GRssiSaturationTest() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-50, -65};
        int[] security = {SECURITY_PSK, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);
        ScanResult chosenScanResult = scanDetails.get(scanDetails.size() - 1).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #6    Minimum RSSI test
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled
     * test1 is @ 2GHz with RSSI -50
     * test2 is @ 5Ghz with RSSI -65
     * Expected behavior: no network is chosen because both network are below the minimum threshold
     */
    @Test
    public void chooseNetworkMinimumRssiTest() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-86, -83};
        int[] security = {SECURITY_EAP, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        assertEquals("choose the wrong SSID", null, candidate);
    }
    /**
     * Case #7    encrypted network over passpoint network
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1 is secured network, test2 are passpoint network
     * Both network are enabled and at 2.4 GHz. Both have RSSI of -70
     * Expected behavior: test1 is chosen
     */
    @Test
    public void chooseNetworkSecurityOverPassPoint() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[NONE]"};
        int[] levels = {-70, -70};
        int[] security = {SECURITY_EAP, SECURITY_NONE};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        setConfigPasspoint(savedConfigs[1]);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #8    passpoint network over open network
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1 is passpoint network, test2 is open network
     * Both network are enabled and at 2.4 GHz. Both have RSSI of -70
     * Expected behavior: test1 is chosen
     */
    @Test
    public void chooseNetworkPasspointOverOpen() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f8", "6c:f3:7f:ae:8c:f4"};
        int[] frequencies = {2437, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-70, -70};
        int[] security = {SECURITY_NONE, SECURITY_NONE};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        setConfigPasspoint(savedConfigs[0]);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #9    secure network over open network
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1 is secure network, test2 is open network
     * Both network are enabled and at 2.4 GHz. Both have RSSI of -70
     * Expected behavior: test1 is chosen
     */
    @Test
    public void chooseNetworkSecureOverOpen() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-70, -70};
        int[] security = {SECURITY_PSK, SECURITY_NONE};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);
        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * Case #10
     * In this test. we simulate following scenario
     * There are three saved networks: test1, test2 and test3. Now user select the network test3
     * check test3 has been saved in test1's and test2's ConnectChoice
     */
    @Test
    public void userSelectsNetworkForFirstTime() {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        int[] security = {SECURITY_PSK, SECURITY_PSK, SECURITY_NONE};

        final WifiConfiguration[] configs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(configs);
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
     * case 11
     * In this test, we simulate following scenario:
     * There are three networks: test1, test2, test3 and test3 is the user preference
     * All three networks are enabled
     * test1 is @ 2.4GHz with RSSI -50 PSK
     * test2 is @ 5Ghz with RSSI -65 PSK
     * test3 is @ 2.4GHz with RSSI -55 open
     * Expected behavior: test3 is chosen
     */
    @Test
    public void chooseUserPreferredNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        int[] security = {SECURITY_PSK, SECURITY_PSK, SECURITY_NONE};

        final WifiConfiguration[] configs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(configs);
        for (WifiConfiguration network : configs) {
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            status.setSeenInLastQualifiedNetworkSelection(true);
        }

        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(Arrays.asList(configs));

        //set user preference
        mWifiQualifiedNetworkSelector.userSelectNetwork(ssids.length - 1, true);
        //Generate mocked recent scan results
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5"};
        int[] frequencies = {2437, 5180, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]", "NONE"};
        int[] levels = {-50, -65, -55};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        scanResultLinkConfiguration(configs, scanDetails);

        ScanResult chosenScanResult = scanDetails.get(scanDetails.size() - 1).getScanResult();
        when(mWifiConfigStore.getWifiConfiguration(configs[2].configKey()))
                .thenReturn(configs[2]);

        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);
        verifySelectedResult(chosenScanResult, candidate);
    }

    /**
     * case #12
     *
     * For two Aps, BSSIDA and BSSIDB. Disable BSSIDA, then check whether BSSIDA is disabled and
     * BSSIDB is enabled. Then enable BSSIDA, check whether both BSSIDs are enabled.
     */
    @Test
    public void enableBssidTest() {
        String bssidA = "6c:f3:7f:ae:8c:f3";
        String bssidB = "6c:f3:7f:ae:8c:f4";
        //check by default these two BSSIDs should be enabled
        assertEquals("bssidA should be enabled by default",
                mWifiQualifiedNetworkSelector.isBssidDisabled(bssidA), false);
        assertEquals("bssidB should be enabled by default",
                mWifiQualifiedNetworkSelector.isBssidDisabled(bssidB), false);

        //disable bssidA, check whether A is dsiabled and B is still enabled
        mWifiQualifiedNetworkSelector.enableBssidForQualitynetworkSelection(bssidA, false);
        assertEquals("bssidA should be disabled",
                mWifiQualifiedNetworkSelector.isBssidDisabled(bssidA), true);
        assertEquals("bssidB should still be enabled",
                mWifiQualifiedNetworkSelector.isBssidDisabled(bssidB), false);

        //re-enable bssidA, check whether A is dsiabled and B is still enabled
        mWifiQualifiedNetworkSelector.enableBssidForQualitynetworkSelection(bssidA, true);
        assertEquals("bssidA should be enabled by default",
                mWifiQualifiedNetworkSelector.isBssidDisabled(bssidA), false);
        assertEquals("bssidB should be enabled by default",
                mWifiQualifiedNetworkSelector.isBssidDisabled(bssidB), false);

        //make sure illegal input will not cause crash
        mWifiQualifiedNetworkSelector.enableBssidForQualitynetworkSelection(null, true);
    }

    /**
     * case #13
     * In this test. we simulate following scenario:
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network and found in scan results
     * Both network are enabled
     * test1 is @ 2GHz with RSSI -50
     * test2 is @ 5Ghz with RSSI -65
     * test2's BSSID is disabled
     * expected return test1
     */
    @Test
    public void networkChooseWithOneBssidDisabled() {
        String[] ssids = DEFAULT_SSIDS;
        String[] bssids = DEFAULT_BSSIDS;
        int[] frequencies = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-50, -65};
        int[] security = {SECURITY_PSK, SECURITY_PSK};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        WifiConfiguration[] savedConfigs = generateWifiConfigurations(ssids, security);
        prepareConfigStore(savedConfigs);

        final List<WifiConfiguration> savedNetwork = Arrays.asList(savedConfigs);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        scanResultLinkConfiguration(savedConfigs, scanDetails);
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();

        mWifiQualifiedNetworkSelector.enableBssidForQualitynetworkSelection(bssids[1], false);
        WifiConfiguration candidate = mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false,
                false, scanDetails, false, false, true, false);

        verifySelectedResult(chosenScanResult, candidate);

    }

}
