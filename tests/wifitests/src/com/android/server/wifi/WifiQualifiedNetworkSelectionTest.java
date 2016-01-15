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

import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example Unit Test File
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

    @Test
    /**
     * In this test. we simulate following scenario
     * WifiStateMachine is under disconnected state
     * Two networks test1, test2 are secured network
     * Both network are enabled
     * test1 is @ 2GHz with RSSI
     */
    public void chooseNetworkDisconnect5GOver2G() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] frequencies = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS][ESS]"};
        int[] levels = {-50, -65};

        List<ScanDetail> scanDetails = getScanDetails(ssids, bssids, frequencies, caps, levels);
        when(mWifiStateMachine.getScanResultsListNoCopyUnsync()).thenReturn(scanDetails);
        when(mWifiStateMachine.isConnected()).thenReturn(false);
        when(mWifiStateMachine.isDisconnected()).thenReturn(true);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.networkId = 0;
        configuration1.SSID = "\"test1\"";
        WifiConfiguration configuration2 = new WifiConfiguration();
        configuration2.networkId = 1;
        configuration2.SSID = "\"test2\"";

        List<WifiConfiguration> savedNetwork =  new ArrayList<WifiConfiguration>();
        savedNetwork.add(configuration1);
        savedNetwork.add(configuration2);

        when(mWifiConfigStore.isOpenNetwork(any(WifiConfiguration.class))).thenReturn(false);
        when(mWifiConfigStore.isOpenNetwork(any(ScanResult.class))).thenReturn(false);
        when(mWifiConfigStore.getWifiConfiguration(0)).thenReturn(configuration1);
        when(mWifiConfigStore.getWifiConfiguration(1)).thenReturn(configuration2);
        when(mWifiConfigStore.getConfiguredNetworks()).thenReturn(savedNetwork);
        when(mWifiConfigStore.getLastSelectedConfiguration()).thenReturn(null);
        when(mWifiConfigStore.isBssidBlacklisted(any(String.class))).thenReturn(false);

        List<WifiConfiguration> associateWithScanResult1 = new ArrayList<WifiConfiguration>();
        associateWithScanResult1.add(configuration1);

        List<WifiConfiguration> associateWithScanResult2 = new ArrayList<WifiConfiguration>();
        associateWithScanResult2.add(configuration2);

        when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(0))).thenReturn(
                associateWithScanResult1);
        when(mWifiConfigStore.updateSavedNetworkWithNewScanDetail(scanDetails.get(1))).thenReturn(
                associateWithScanResult2);
        ScanResult scanResult = scanDetails.get(1).getScanResult();

        mWifiQualifiedNetworkSelector.selectQualifiedNetwork(false);
        verify(mWifiStateMachine).sendMessage(WifiStateMachine.CMD_AUTO_ROAM, 1, 1, scanResult);
    }
}
