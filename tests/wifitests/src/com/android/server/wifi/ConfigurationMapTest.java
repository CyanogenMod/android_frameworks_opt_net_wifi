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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.net.wifi.WifiConfiguration;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.ConfigurationMapTest}.
 */
@SmallTest
public class ConfigurationMapTest {
    private static final List<WifiConfiguration> CONFIGS = Arrays.asList(
            WifiConfigurationUtil.generateWifiConfig(0, 1000000, "\"red\"", true, true, null, null),
            WifiConfigurationUtil.generateWifiConfig(
                    1, 1000001, "\"green\"", false, true, null, null),
            WifiConfigurationUtil.generateWifiConfig(
                    2, 1000002, "\"blue\"", true, false, "example.com", "Blue"),
            WifiConfigurationUtil.generateWifiConfig(
                    3, 1100000, "\"cyan\"", true, true, null, null),
            WifiConfigurationUtil.generateWifiConfig(
                    4, 1100001, "\"yellow\"", false, false, null, null),
            WifiConfigurationUtil.generateWifiConfig(
                    5, 1100002, "\"magenta\"", true, true, "example.org", "Magenta"));

    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    private final ConfigurationMap mConfigs = new ConfigurationMap();

    public void switchUser(int newUserId) {
        Set<WifiConfiguration> hiddenConfigurations = new HashSet<>();
        for (WifiConfiguration config : mConfigs.valuesForAllUsers()) {
            if (config.isVisibleToUser(mCurrentUserId) && !config.isVisibleToUser(newUserId)) {
                hiddenConfigurations.add(config);
            }
        }

        mCurrentUserId = newUserId;
        assertEquals(hiddenConfigurations, new HashSet<>(mConfigs.handleUserSwitch(newUserId)));
    }

    public void verifyGetters(List<WifiConfiguration> configs) {
        final Set<WifiConfiguration> configsForCurrentUser = new HashSet<>();
        final Set<WifiConfiguration> enabledConfigsForCurrentUser = new HashSet<>();
        final List<WifiConfiguration> configsNotForCurrentUser = new ArrayList<>();

        // Find out which network configurations should be / should not be visible to the current
        // user. Also, check that *ForAllUsers() methods can be used to access all network
        // configurations, irrespective of their visibility to the current user.
        for (WifiConfiguration config : configs) {
            if (config.isVisibleToUser(mCurrentUserId)) {
                configsForCurrentUser.add(config);
                if (config.status != WifiConfiguration.Status.DISABLED) {
                    enabledConfigsForCurrentUser.add(config);
                }
            } else {
                configsNotForCurrentUser.add(config);
            }

            assertEquals(config, mConfigs.getForAllUsers(config.networkId));
            assertEquals(config,
                    mConfigs.getByConfigKeyIDForAllUsers(config.configKey().hashCode()));
        }

        // Verify that *ForCurrentUser() methods can be used to access network configurations
        // visible to the current user.
        for (WifiConfiguration config : configsForCurrentUser) {
            assertEquals(config, mConfigs.getForCurrentUser(config.networkId));
            if (config.FQDN != null) {
                assertEquals(config, mConfigs.getByFQDNForCurrentUser(config.FQDN));
            }
            assertEquals(config, mConfigs.getByConfigKeyForCurrentUser(config.configKey()));
            final boolean wasEphemeral = config.ephemeral;
            config.ephemeral = false;
            assertNull(mConfigs.getEphemeralForCurrentUser(config.SSID));
            config.ephemeral = true;
            assertEquals(config, mConfigs.getEphemeralForCurrentUser(config.SSID));
            config.ephemeral = wasEphemeral;
        }

        // Verify that *ForCurrentUser() methods cannot be used to access network configurations not
        // visible to the current user.
        for (WifiConfiguration config : configsNotForCurrentUser) {
            assertNull(mConfigs.getForCurrentUser(config.networkId));
            if (config.FQDN != null) {
                assertNull(mConfigs.getByFQDNForCurrentUser(config.FQDN));
            }
            assertNull(mConfigs.getByConfigKeyForCurrentUser(config.configKey()));
            final boolean wasEphemeral = config.ephemeral;
            config.ephemeral = false;
            assertNull(mConfigs.getEphemeralForCurrentUser(config.SSID));
            config.ephemeral = true;
            assertNull(mConfigs.getEphemeralForCurrentUser(config.SSID));
            config.ephemeral = wasEphemeral;
        }

        // Verify that the methods which refer to more than one network configuration return the
        // correct sets of networks.
        assertEquals(configs.size(), mConfigs.sizeForAllUsers());
        assertEquals(configsForCurrentUser.size(), mConfigs.sizeForCurrentUser());
        assertEquals(enabledConfigsForCurrentUser,
                new HashSet<WifiConfiguration>(mConfigs.getEnabledNetworksForCurrentUser()));
        assertEquals(new HashSet<>(configs),
                new HashSet<WifiConfiguration>(mConfigs.valuesForAllUsers()));
    }

    /**
     * Verifies that all getters return the correct network configurations, taking into account the
     * current user. Also verifies that handleUserSwitch() returns the list of network
     * configurations that are no longer visible.
     */
    @Test
    public void testGettersAndHandleUserSwitch() {
        for (WifiConfiguration config : CONFIGS) {
            assertNull(mConfigs.put(config));
        }

        verifyGetters(CONFIGS);

        switchUser(10);
        verifyGetters(CONFIGS);

        switchUser(11);
        verifyGetters(CONFIGS);
    }

    /**
     * Verifies put(), remove() and clear().
     */
    @Test
    public void testPutRemoveClear() {
        final List<WifiConfiguration> configs = new ArrayList<>();
        final WifiConfiguration config1 = CONFIGS.get(0);

        // Verify that there are no network configurations to start with.
        switchUser(UserHandle.getUserId(config1.creatorUid));
        verifyGetters(configs);

        // Add |config1|.
        assertNull(mConfigs.put(config1));
        // Verify that the getters return |config1|.
        configs.add(config1);
        verifyGetters(configs);

        // Overwrite |config1| with |config2|.
        final WifiConfiguration config2 = CONFIGS.get(1);
        config2.networkId = config1.networkId;
        assertEquals(config1, mConfigs.put(config2));
        // Verify that the getters return |config2| only.
        configs.clear();
        configs.add(config2);
        verifyGetters(configs);

        // Add |config3|.
        final WifiConfiguration config3 = CONFIGS.get(2);
        assertNull(mConfigs.put(config3));
        // Verify that the getters return |config2| and |config3|.
        configs.add(config3);
        verifyGetters(configs);

        // Remove |config2|.
        assertEquals(config2, mConfigs.remove(config2.networkId));
        // Verify that the getters return |config3| only.
        configs.remove(config2);
        verifyGetters(configs);

        // Clear all network configurations.
        mConfigs.clear();
        // Verify that the getters do not return any network configurations.
        configs.clear();
        verifyGetters(configs);
    }
}
