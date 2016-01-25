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

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;

/**
 * Helper for creating and populating WifiConfigurations in unit tests.
 */
public class WifiConfigurationUtil {
    public static WifiConfiguration generateWifiConfig(int networkId, int uid, String ssid,
            boolean shared, boolean enabled, String fqdn, String providerFriendlyName) {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = ssid;
        config.networkId = networkId;
        config.creatorUid = uid;
        config.shared = shared;
        config.status = enabled ? WifiConfiguration.Status.ENABLED
                : WifiConfiguration.Status.DISABLED;
        if (fqdn != null) {
            config.FQDN = fqdn;
            config.providerFriendlyName = providerFriendlyName;
            config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        }
        return config;
    }
}
