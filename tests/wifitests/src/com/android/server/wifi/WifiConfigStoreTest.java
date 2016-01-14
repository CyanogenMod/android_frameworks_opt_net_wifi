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
 * limitations under the License
 */

package com.android.server.wifi;

import static org.hamcrest.CoreMatchers.not;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.intThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.test.AndroidTestCase;

import com.android.server.wifi.hotspot2.omadm.MOManager;
import com.android.server.wifi.hotspot2.pps.Credential;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigStore}.
 */
public class WifiConfigStoreTest extends AndroidTestCase {
    private static final int UID = 10;
    private static final String[] SSIDS = {"\"red\"", "\"green\"", "\"blue\""};
    private static final String[] ENCODED_SSIDS = {"726564", "677265656e", "626c7565"};
    private static final String[] FQDNS = {null, "example.com", "example.org"};
    private static final String[] PROVIDER_FRIENDLY_NAMES = {null, "Green", "Blue"};
    private static final String[] CONFIG_KEYS = {"\"red\"NONE", "example.comWPA_EAP",
            "example.orgWPA_EAP"};

    @Mock private Context mContext;
    @Mock private WifiStateMachine mWifiStateMachine;
    @Mock private WifiNative mWifiNative;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private MOManager mMOManager;
    private WifiConfigStore mConfigStore;

    @Override
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        final Context realContext = getContext();
        when(mContext.getPackageName()).thenReturn(realContext.getPackageName());
        when(mContext.getResources()).thenReturn(realContext.getResources());

        mConfigStore = new WifiConfigStore(mContext, mWifiStateMachine, mWifiNative,
                mFrameworkFacade);

        when(mMOManager.isEnabled()).thenReturn(true);
        final Field moManagerField = WifiConfigStore.class.getDeclaredField("mMOManager");
        moManagerField.setAccessible(true);
        moManagerField.set(mConfigStore, mMOManager);
    }

    private WifiConfiguration generateWifiConfig(int network) {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = SSIDS[network];
        config.creatorUid = UID;
        if (FQDNS[network] != null) {
            config.FQDN = FQDNS[network];
            config.providerFriendlyName = PROVIDER_FRIENDLY_NAMES[network];
            config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        }
        return config;
    }

    /**
     * Verifies that saveNetwork() correctly stores a network configuration in wpa_supplicant
     * variables.
     * TODO: Test all variables. Currently, only "ssid" and "id_str" are tested.
     */
    public void verifySaveNetwork(int network) {
        // Set up wpa_supplicant.
        when(mWifiNative.addNetwork()).thenReturn(0);
        when(mWifiNative.setNetworkVariable(eq(0), anyString(), anyString())).thenReturn(true);
        when(mWifiNative.setNetworkExtra(eq(0), anyString(), (Map<String, String>) anyObject()))
                .thenReturn(true);
        when(mWifiNative.getNetworkVariable(0, WifiConfiguration.ssidVarName))
                .thenReturn(ENCODED_SSIDS[network]);

        // Store a network configuration.
        mConfigStore.saveNetwork(generateWifiConfig(network), UID);

        // Verify that wpa_supplicant variables were written correctly for the network
        // configuration.
        final Map<String, String> metadata = new HashMap<String, String>();
        if (FQDNS[network] != null) {
            metadata.put(WifiConfigStore.ID_STRING_KEY_FQDN, FQDNS[network]);
        }
        metadata.put(WifiConfigStore.ID_STRING_KEY_CONFIG_KEY, CONFIG_KEYS[network]);
        metadata.put(WifiConfigStore.ID_STRING_KEY_CREATOR_UID, Integer.toString(UID));
        verify(mWifiNative).setNetworkExtra(0, WifiConfigStore.ID_STRING_VAR_NAME,
                metadata);

        // Verify that no wpa_supplicant variables were read or written for any other network
        // configurations.
        verify(mWifiNative, never()).setNetworkExtra(intThat(not(0)), anyString(),
                (Map<String, String>) anyObject());
        verify(mWifiNative, never()).setNetworkVariable(intThat(not(0)), anyString(), anyString());
        verify(mWifiNative, never()).getNetworkVariable(intThat(not(0)), anyString());
    }

    /**
     * Verifies that saveNetwork() correctly stores a regular network configuration.
     */
    public void testSaveNetworkRegular() {
        verifySaveNetwork(0);
    }

    /**
     * Verifies that saveNetwork() correctly stores a HotSpot 2.0 network configuration.
     */
    public void testSaveNetworkHotspot20() {
        verifySaveNetwork(1);
    }

    /**
     * Verifies that loadConfiguredNetworks() correctly reads data from the wpa_supplicant and
     * the MOManager, correlating the two sources based on the FQDN for HotSpot 2.0 networks.
     * TODO: Test all variables. Currently, only "ssid" and "id_str" are tested.
     */
    public void testLoadConfiguredNetworks() throws Exception {
        // Set up list of networks returned by wpa_supplicant.
        final String header = "network id / ssid / bssid / flags";
        String networks = header;
        for (int i = 0; i < SSIDS.length; ++i) {
            networks += "\n" + Integer.toString(i) + "\t" + SSIDS[i] + "\tany";
        }
        when(mWifiNative.listNetworks(anyInt())).thenReturn(header);
        when(mWifiNative.listNetworks(-1)).thenReturn(networks);

        // Set up variables returned by wpa_supplicant for the individual networks.
        for (int i = 0; i < SSIDS.length; ++i) {
            when(mWifiNative.getNetworkVariable(i, WifiConfiguration.ssidVarName))
                .thenReturn(ENCODED_SSIDS[i]);
        }
        // Legacy regular network configuration: No "id_str".
        when(mWifiNative.getNetworkExtra(0, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn(null);
        // Legacy Hotspot 2.0 network configuration: Quoted FQDN in "id_str".
        when(mWifiNative.getNetworkExtra(1, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn(null);
        when(mWifiNative.getNetworkVariable(1, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn('"' + FQDNS[1] + '"');
        // Up-to-date configuration: Metadata in "id_str".
        final Map<String, String> metadata = new HashMap<String, String>();
        metadata.put(WifiConfigStore.ID_STRING_KEY_CONFIG_KEY, CONFIG_KEYS[2]);
        metadata.put(WifiConfigStore.ID_STRING_KEY_CREATOR_UID, Integer.toString(UID));
        metadata.put(WifiConfigStore.ID_STRING_KEY_FQDN, FQDNS[2]);
        when(mWifiNative.getNetworkExtra(2, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn(metadata);

        // Set up list of home service providers returned by MOManager.
        final List<HomeSP> homeSPs = new ArrayList<HomeSP>();
        for (int i : new int[] {1, 2}) {
            homeSPs.add(new HomeSP(null, FQDNS[i], new HashSet<Long>(), new HashSet<String>(),
                    new HashSet<Long>(), new ArrayList<Long>(), PROVIDER_FRIENDLY_NAMES[i], null,
                    new Credential(0, 0, null, false, null, null), null, 0, null, null, null));
        }
        when(mMOManager.loadAllSPs()).thenReturn(homeSPs);

        // Load network configurations.
        mConfigStore.loadConfiguredNetworks();

        // Verify that network configurations were loaded. For HotSpot 2.0 networks, this also
        // verifies that the data read from the wpa_supplicant was correlated with the data read
        // from the MOManager based on the FQDN stored in the wpa_supplicant's "id_str" variable.
        final List<WifiConfiguration> configs = mConfigStore.getConfiguredNetworks();
        assertEquals(SSIDS.length, configs.size());
        for (int i = 0; i < SSIDS.length; ++i) {
            WifiConfiguration config = null;
            // Find the network configuration to test (getConfiguredNetworks() returns them in
            // undefined order).
            for (final WifiConfiguration candidate : configs) {
                if (candidate.networkId == i) {
                    config = candidate;
                    break;
                }
            }
            assertNotNull(config);
            assertEquals(SSIDS[i], config.SSID);
            assertEquals(FQDNS[i], config.FQDN);
            assertEquals(PROVIDER_FRIENDLY_NAMES[i], config.providerFriendlyName);
            assertEquals(CONFIG_KEYS[i], config.configKey(false));
        }
    }
}
