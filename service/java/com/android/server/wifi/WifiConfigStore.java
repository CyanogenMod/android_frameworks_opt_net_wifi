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
import android.content.Intent;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo.DetailedState;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.Status;
import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;

import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.net.wifi.ScanResult;

import android.os.Environment;
import android.os.FileObserver;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.net.DelayedDiskWrite;
import com.android.server.net.IpConfigStore;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class provides the API to manage configured
 * wifi networks. The API is not thread safe is being
 * used only from WifiStateMachine.
 *
 * It deals with the following
 * - Add/update/remove a WifiConfiguration
 *   The configuration contains two types of information.
 *     = IP and proxy configuration that is handled by WifiConfigStore and
 *       is saved to disk on any change.
 *
 *       The format of configuration file is as follows:
 *       <version>
 *       <netA_key1><netA_value1><netA_key2><netA_value2>...<EOS>
 *       <netB_key1><netB_value1><netB_key2><netB_value2>...<EOS>
 *       ..
 *
 *       (key, value) pairs for a given network are grouped together and can
 *       be in any order. A EOS at the end of a set of (key, value) pairs
 *       indicates that the next set of (key, value) pairs are for a new
 *       network. A network is identified by a unique ID_KEY. If there is no
 *       ID_KEY in the (key, value) pairs, the data is discarded.
 *
 *       An invalid version on read would result in discarding the contents of
 *       the file. On the next write, the latest version is written to file.
 *
 *       Any failures during read or write to the configuration file are ignored
 *       without reporting to the user since the likelihood of these errors are
 *       low and the impact on connectivity is low.
 *
 *     = SSID & security details that is pushed to the supplicant.
 *       supplicant saves these details to the disk on calling
 *       saveConfigCommand().
 *
 *       We have two kinds of APIs exposed:
 *        > public API calls that provide fine grained control
 *          - enableNetwork, disableNetwork, addOrUpdateNetwork(),
 *          removeNetwork(). For these calls, the config is not persisted
 *          to the disk. (TODO: deprecate these calls in WifiManager)
 *        > The new API calls - selectNetwork(), saveNetwork() & forgetNetwork().
 *          These calls persist the supplicant config to disk.
 *
 * - Maintain a list of configured networks for quick access
 *
 */
public class WifiConfigStore extends IpConfigStore {

    private Context mContext;
    private static final String TAG = "WifiConfigStore";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final String SUPPLICANT_CONFIG_FILE = "/data/misc/wifi/wpa_supplicant.conf";

    /* configured networks with network id as the key */
    private HashMap<Integer, WifiConfiguration> mConfiguredNetworks =
            new HashMap<Integer, WifiConfiguration>();

    /* A network id is a unique identifier for a network configured in the
     * supplicant. Network ids are generated when the supplicant reads
     * the configuration file at start and can thus change for networks.
     * We store the IP configuration for networks along with a unique id
     * that is generated from SSID and security type of the network. A mapping
     * from the generated unique id to network id of the network is needed to
     * map supplicant config to IP configuration. */
    private HashMap<Integer, Integer> mNetworkIds =
            new HashMap<Integer, Integer>();

    /* Tracks the highest priority of configured networks */
    private int mLastPriority = -1;

    private static final String ipConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/ipconfig.txt";

    private static final String networkHistoryConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/networkHistory.txt";

    /* Network History Keys */
    private static final String SSID_KEY = "SSID:  ";
    private static final String CONFIG_KEY = "CONFIG:  ";
    private static final String CHOICE_KEY = "CHOICE:  ";
    private static final String LINK_KEY = "LINK:  ";
    private static final String BSSID_KEY = "BSSID:  ";
    private static final String BSSID_KEY_END = "/BSSID:  ";
    private static final String RSSI_KEY = "RSSI:  ";
    private static final String FREQ_KEY = "FREQ:  ";
    private static final String DATE_KEY = "DATE:  ";
    private static final String MILLI_KEY = "MILLI:  ";
    private static final String NETWORK_ID_KEY = "ID:  ";
    private static final String PRIORITY_KEY = "PRIORITY:  ";
    private static final String DEFAULT_GW_KEY = "DEFAULT_GW:  ";
    private static final String AUTH_KEY = "AUTH:  ";
    private static final String SEPARATOR_KEY = "\n";
    private static final String STATUS_KEY = "AUTO_JOIN_STATUS:  ";

    /* Enterprise configuration keys */
    /**
     * In old configurations, the "private_key" field was used. However, newer
     * configurations use the key_id field with the engine_id set to "keystore".
     * If this field is found in the configuration, the migration code is
     * triggered.
     */
    public static final String OLD_PRIVATE_KEY_NAME = "private_key";

    /** This represents an empty value of an enterprise field.
     * NULL is used at wpa_supplicant to indicate an empty value
     */
    static final String EMPTY_VALUE = "NULL";

    /** Internal use only */
    private static final String[] ENTERPRISE_CONFIG_SUPPLICANT_KEYS = new String[] {
            WifiEnterpriseConfig.EAP_KEY, WifiEnterpriseConfig.PHASE2_KEY,
            WifiEnterpriseConfig.IDENTITY_KEY, WifiEnterpriseConfig.ANON_IDENTITY_KEY,
            WifiEnterpriseConfig.PASSWORD_KEY, WifiEnterpriseConfig.CLIENT_CERT_KEY,
            WifiEnterpriseConfig.CA_CERT_KEY, WifiEnterpriseConfig.SUBJECT_MATCH_KEY,
            WifiEnterpriseConfig.ENGINE_KEY, WifiEnterpriseConfig.ENGINE_ID_KEY,
            WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY };

    private final LocalLog mLocalLog;
    private final WpaConfigFileObserver mFileObserver;

    private WifiNative mWifiNative;
    private final KeyStore mKeyStore = KeyStore.getInstance();

    /*
     * lastSelectedConfiguration is used to remember which network was selected last by the user.
     * The connection to this network may not be successful, as well
     * the selection (i.e. network priority) might not be persisted.
     * WiFi state machine is the only object that sets this variable.
     */
    private String lastSelectedConfiguration = null;

    WifiConfigStore(Context c, WifiNative wn) {
        mContext = c;
        mWifiNative = wn;

        if (VDBG) {
            mLocalLog = mWifiNative.getLocalLog();
            mFileObserver = new WpaConfigFileObserver();
            mFileObserver.startWatching();
        } else {
            mLocalLog = null;
            mFileObserver = null;
        }
    }

    class WpaConfigFileObserver extends FileObserver {

        public WpaConfigFileObserver() {
            super(SUPPLICANT_CONFIG_FILE, CLOSE_WRITE);
        }

        @Override
        public void onEvent(int event, String path) {
            if (event == CLOSE_WRITE) {
                File file = new File(SUPPLICANT_CONFIG_FILE);
                if (VDBG) localLog("wpa_supplicant.conf changed; new size = " + file.length());
            }
        }
    }


    /**
     * Fetch the list of configured networks
     * and enable all stored networks in supplicant.
     */
    void loadAndEnableAllNetworks() {
        if (DBG) log("Loading config and enabling all networks");
        loadConfiguredNetworks();
        enableAllNetworks();
    }

    /**
     * Fetch the list of currently configured networks
     * @return List of networks
     */
    List<WifiConfiguration> getConfiguredNetworks() {
        List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            networks.add(new WifiConfiguration(config));
        }
        return networks;
    }

    /**
     * Fetch the list of currently configured networks that were recently seen
     *
     * @return List of networks
     */
    List<WifiConfiguration> getRecentConfiguredNetworks(int milli, boolean copy) {
        List<WifiConfiguration> networks = null;

        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            // calculate the RSSI for scan results that are more recent than milli
            config.setVisibility(milli);

            if (config.visibility == null) {
                continue;
            }
            if (config.visibility.rssi5 == WifiConfiguration.INVALID_RSSI &&
                    config.visibility.rssi24 == WifiConfiguration.INVALID_RSSI) {
                continue;
            }
            if (networks == null)
                networks = new ArrayList<WifiConfiguration>();
            if (copy) {
                networks.add(new WifiConfiguration(config));
            } else {
                networks.add(config);
            }
        }
        return networks;
    }

    /**
     * get the Wificonfiguration for this netId
     *
     * @return Wificonfiguration
     */

    WifiConfiguration getWifiConfiguration(int netId) {
        if (mConfiguredNetworks == null)
            return null;
        return mConfiguredNetworks.get(netId);
    }


    /**
     * get the Wificonfiguration for this key
     *
     * @return Wificonfiguration
     */

    WifiConfiguration getWifiConfiguration(String key) {
        if (key == null)
            return null;
        int hash = key.hashCode();
        if (mNetworkIds == null)
            return null;
        Integer n = mNetworkIds.get(hash);
        if (n == null)
            return null;
        int netId = n.intValue();
        return getWifiConfiguration(netId);
    }

    /**
     * enable all networks and save config. This will be a no-op if the list
     * of configured networks indicates all networks as being enabled
     */
    void enableAllNetworks() {
        boolean networkEnabledStateChanged = false;
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            if(config != null && config.status == Status.DISABLED
                    && (config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)) {
                if(mWifiNative.enableNetwork(config.networkId, false)) {
                    networkEnabledStateChanged = true;
                    config.status = Status.ENABLED;
                } else {
                    loge("Enable network failed on " + config.networkId);
                }
            }
        }

        if (networkEnabledStateChanged) {
            mWifiNative.saveConfig();
            sendConfiguredNetworksChangedBroadcast();
        }
    }


    /**
     * Selects the specified network for connection. This involves
     * updating the priority of all the networks and enabling the given
     * network while disabling others.
     *
     * Selecting a network will leave the other networks disabled and
     * a call to enableAllNetworks() needs to be issued upon a connection
     * or a failure event from supplicant
     *
     * @param netId network to select for connection
     * @return false if the network id is invalid
     */
    boolean selectNetwork(int netId) {
        if (VDBG) localLog("selectNetwork", netId);
        if (netId == INVALID_NETWORK_ID) return false;

        // Reset the priority of each network at start or if it goes too high.
        if (mLastPriority == -1 || mLastPriority > 1000000) {
            for(WifiConfiguration config : mConfiguredNetworks.values()) {
                if (config.networkId != INVALID_NETWORK_ID) {
                    config.priority = 0;
                    addOrUpdateNetworkNative(config);
                }
            }
            mLastPriority = 0;
        }

        // Set to the highest priority and save the configuration.
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = netId;
        config.priority = ++mLastPriority;

        addOrUpdateNetworkNative(config);
        mWifiNative.saveConfig();

        /* Enable the given network while disabling all other networks */
        enableNetworkWithoutBroadcast(netId, true);

       /* Avoid saving the config & sending a broadcast to prevent settings
        * from displaying a disabled list of networks */
        return true;
    }

    /**
     * Add/update the specified configuration and save config
     *
     * @param config WifiConfiguration to be saved
     * @return network update result
     */
    NetworkUpdateResult saveNetwork(WifiConfiguration config) {
        WifiConfiguration conf;

        // A new network cannot have null SSID
        if (config == null || (config.networkId == INVALID_NETWORK_ID &&
                config.SSID == null)) {
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }
        if (VDBG) localLog("WifiConfigStore: saveNetwork netId", config.networkId);
        if (VDBG) {
            loge("WifiConfigStore saveNetwork, size=" + mConfiguredNetworks.size()
                    + " SSID=" + config.SSID);
        }
        boolean newNetwork = (config.networkId == INVALID_NETWORK_ID);
        NetworkUpdateResult result = addOrUpdateNetworkNative(config);
        int netId = result.getNetworkId();

        if (VDBG) localLog("WifiConfigStore: saveNetwork got it back netId=", netId);

        /* enable a new network */
        if (newNetwork && netId != INVALID_NETWORK_ID) {
            if (VDBG) localLog("WifiConfigStore: will enable netId=", netId);

            mWifiNative.enableNetwork(netId, false);
            conf = mConfiguredNetworks.get(netId);
            if (conf != null)
                conf.status = Status.ENABLED;
        }

        conf = mConfiguredNetworks.get(netId);
        if (conf != null) {
            if (conf.autoJoinStatus != WifiConfiguration.AUTO_JOIN_ENABLED) {
                if (VDBG) localLog("WifiConfigStore: re-enabling: " + conf.SSID);

                // reenable autojoin, since new information has been provided
                conf.autoJoinStatus = WifiConfiguration.AUTO_JOIN_ENABLED;
                enableNetworkWithoutBroadcast(conf.networkId, false);
            }
        }

        mWifiNative.saveConfig();
        sendConfiguredNetworksChangedBroadcast(config, result.isNewNetwork() ?
                WifiManager.CHANGE_REASON_ADDED : WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        return result;
    }

    void updateStatus(int netId, DetailedState state) {
        if (netId != INVALID_NETWORK_ID) {
            WifiConfiguration config = mConfiguredNetworks.get(netId);
            if (config == null) return;
            switch (state) {
                case CONNECTED:
                    config.status = Status.CURRENT;
                    break;
                case DISCONNECTED:
                    //If network is already disabled, keep the status
                    if (config.status == Status.CURRENT) {
                        config.status = Status.ENABLED;
                    }
                    break;
                default:
                    //do nothing, retain the existing state
                    break;
            }
        }
    }

    /**
     * Forget the specified network and save config
     *
     * @param netId network to forget
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean forgetNetwork(int netId) {
        if (VDBG) localLog("forgetNetwork", netId);
        if (mWifiNative.removeNetwork(netId)) {
            mWifiNative.saveConfig();
            removeConfigAndSendBroadcastIfNeeded(netId);
            return true;
        } else {
            loge("Failed to remove network " + netId);
            return false;
        }
    }

    /**
     * Add/update a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful saveNetwork() is used by the
     * state machine
     *
     * @param config wifi configuration to add/update
     * @return network Id
     */
    int addOrUpdateNetwork(WifiConfiguration config) {
        if (VDBG) localLog("addOrUpdateNetwork", config.networkId);
        //adding unconditional message to chase b/15111865
        Log.e(TAG, " key=" + config.configKey() + " netId=" + Integer.toString(config.networkId));
        NetworkUpdateResult result = addOrUpdateNetworkNative(config);
        if (result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
            WifiConfiguration conf = mConfiguredNetworks.get(result.getNetworkId());
            if (conf != null) {
                sendConfiguredNetworksChangedBroadcast(conf,
                    result.isNewNetwork ? WifiManager.CHANGE_REASON_ADDED :
                            WifiManager.CHANGE_REASON_CONFIG_CHANGE);
            }
        }
        return result.getNetworkId();
    }

    /**
     * Remove a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful forgetNetwork() is used by the
     * state machine for network removal
     *
     * @param netId network to be removed
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean removeNetwork(int netId) {
        if (VDBG) localLog("removeNetwork", netId);
        boolean ret = mWifiNative.removeNetwork(netId);
        if (ret) {
            removeConfigAndSendBroadcastIfNeeded(netId);
        }
        return ret;
    }

    private void removeConfigAndSendBroadcastIfNeeded(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            if (VDBG) {
                loge("removeNetwork " + Integer.toString(netId) + " key=" +
                        config.configKey() + " config.id=" + Integer.toString(config.networkId));
            }

            // cancel the last user choice
            if (config.configKey().equals(lastSelectedConfiguration)) {
                lastSelectedConfiguration = null;
            }

            // Remove any associated keys
            if (config.enterpriseConfig != null) {
                removeKeys(config.enterpriseConfig);
            }
            mConfiguredNetworks.remove(netId);
            mNetworkIds.remove(configKey(config));
            if (VDBG) {
                loge("removeNetwork -> num " + mConfiguredNetworks.size());
                for (WifiConfiguration c : mConfiguredNetworks.values()) {
                    loge(" has got " + c.configKey() + " id=" + Integer.toString(c.networkId));

                }
            }

            writeIpAndProxyConfigurations();
            sendConfiguredNetworksChangedBroadcast(config, WifiManager.CHANGE_REASON_REMOVED);
            writeKnownNetworkHistory();
        }
    }

    /**
     * Enable a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful selectNetwork()/saveNetwork() is used by the
     * state machine for connecting to a network
     *
     * @param netId network to be enabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean enableNetwork(int netId, boolean disableOthers) {
        boolean ret = enableNetworkWithoutBroadcast(netId, disableOthers);
        if (disableOthers) {
            if (VDBG) localLog("enableNetwork(disableOthers=true) ", netId);
            sendConfiguredNetworksChangedBroadcast();
        } else {
            if (VDBG) localLog("enableNetwork(disableOthers=false) ", netId);
            WifiConfiguration enabledNetwork = null;
            synchronized(mConfiguredNetworks) {
                enabledNetwork = mConfiguredNetworks.get(netId);
            }
            // check just in case the network was removed by someone else.
            if (enabledNetwork != null) {
                sendConfiguredNetworksChangedBroadcast(enabledNetwork,
                        WifiManager.CHANGE_REASON_CONFIG_CHANGE);
            }
        }
        return ret;
    }

    boolean enableNetworkWithoutBroadcast(int netId, boolean disableOthers) {
        boolean ret = mWifiNative.enableNetwork(netId, disableOthers);

        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) config.status = Status.ENABLED;

        if (disableOthers) {
            markAllNetworksDisabledExcept(netId);
        }
        return ret;
    }

    void disableAllNetworks() {
        if (VDBG) localLog("disableAllNetworks");
        boolean networkDisabled = false;
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            if(config != null && config.status != Status.DISABLED) {
                if(mWifiNative.disableNetwork(config.networkId)) {
                    networkDisabled = true;
                    config.status = Status.DISABLED;
                } else {
                    loge("Disable network failed on " + config.networkId);
                }
            }
        }

        if (networkDisabled) {
            sendConfiguredNetworksChangedBroadcast();
        }
    }
    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean disableNetwork(int netId) {
        return disableNetwork(netId, WifiConfiguration.DISABLED_UNKNOWN_REASON);
    }

    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     * @param reason reason code network was disabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean disableNetwork(int netId, int reason) {
        if (VDBG) localLog("disableNetwork", netId);
        boolean ret = mWifiNative.disableNetwork(netId);
        WifiConfiguration network = null;
        WifiConfiguration config = mConfiguredNetworks.get(netId);

        if (VDBG) {
            if (config != null) {
                loge("disableNetwork netId=" + Integer.toString(netId)
                        + " SSID=" + config.SSID
                        + " disabled=" + (config.status == Status.DISABLED)
                        + " reason=" + Integer.toString(config.disableReason));
            }
        }
        /* Only change the reason if the network was not previously disabled */
        if (config != null && config.status != Status.DISABLED) {
            config.status = Status.DISABLED;
            config.disableReason = reason;
            network = config;
        }
        if (network != null) {
            sendConfiguredNetworksChangedBroadcast(network,
                    WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        }
        return ret;
    }

    /**
     * Save the configured networks in supplicant to disk
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean saveConfig() {
        return mWifiNative.saveConfig();
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the access point
     * @param config WPS configuration
     * @return Wps result containing status and pin
     */
    WpsResult startWpsWithPinFromAccessPoint(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsRegistrar(config.BSSID, config.pin)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the device
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsWithPinFromDevice(WpsInfo config) {
        WpsResult result = new WpsResult();
        result.pin = mWifiNative.startWpsPinDisplay(config.BSSID);
        /* WPS leaves all networks disabled */
        if (!TextUtils.isEmpty(result.pin)) {
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS push button configuration
     * @param config WPS configuration
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsPbc(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsPbc(config.BSSID)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS push button configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Fetch the link properties for a given network id
     *
     * @return LinkProperties for the given network id
     */
    LinkProperties getLinkProperties(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) return new LinkProperties(config.getLinkProperties());
        return null;
    }

    /**
     * set IP configuration for a given network id
     */
    void setLinkProperties(int netId, LinkProperties linkProperties) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            // add old proxy details - TODO - is this still needed?
            if(config.getLinkProperties() != null) {
                linkProperties.setHttpProxy(config.getLinkProperties().getHttpProxy());
            }
            config.setLinkProperties(linkProperties);
        }
    }

    /**
     * set default GW MAC address
     */
    void setDefaultGwMacAddress(int netId, String macAddress) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            //update defaultGwMacAddress
            config.defaultGwMacAddress = macAddress;
        }
    }


    /**
     * clear IP configuration for a given network id
     * @param network id
     */
    void clearLinkProperties(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null && config.getLinkProperties() != null) {
            // Clear everything except proxy
            ProxyInfo proxy = config.getLinkProperties().getHttpProxy();
            config.getLinkProperties().clear();
            config.getLinkProperties().setHttpProxy(proxy);
        }
    }


    /**
     * Fetch the proxy properties for a given network id
     * @param network id
     * @return ProxyInfo for the network id
     */
    ProxyInfo getProxyProperties(int netId) {
        LinkProperties linkProperties = getLinkProperties(netId);
        if (linkProperties != null) {
            return new ProxyInfo(linkProperties.getHttpProxy());
        }
        return null;
    }

    /**
     * Return if the specified network is using static IP
     * @param network id
     * @return {@code true} if using static ip for netId
     */
    boolean isUsingStaticIp(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null && config.getIpAssignment() == IpAssignment.STATIC) {
            return true;
        }
        return false;
    }

    /**
     * Should be called when a single network configuration is made.
     * @param network The network configuration that changed.
     * @param reason The reason for the change, should be one of WifiManager.CHANGE_REASON_ADDED,
     * WifiManager.CHANGE_REASON_REMOVED, or WifiManager.CHANGE_REASON_CHANGE.
     */
    private void sendConfiguredNetworksChangedBroadcast(WifiConfiguration network,
            int reason) {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, false);
        intent.putExtra(WifiManager.EXTRA_WIFI_CONFIGURATION, network);
        intent.putExtra(WifiManager.EXTRA_CHANGE_REASON, reason);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Should be called when multiple network configuration changes are made.
     */
    private void sendConfiguredNetworksChangedBroadcast() {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, true);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    void loadConfiguredNetworks() {
        String listStr = mWifiNative.listNetworks();
        mLastPriority = 0;

        mConfiguredNetworks.clear();
        mNetworkIds.clear();

        if (listStr == null)
            return;

        String[] lines = listStr.split("\n");
        // Skip the first line, which is a header
        for (int i = 1; i < lines.length; i++) {
            String[] result = lines[i].split("\t");
            // network-id | ssid | bssid | flags
            WifiConfiguration config = new WifiConfiguration();
            try {
                config.networkId = Integer.parseInt(result[0]);
            } catch(NumberFormatException e) {
                loge("Failed to read network-id '" + result[0] + "'");
                continue;
            }
            if (result.length > 3) {
                if (result[3].indexOf("[CURRENT]") != -1)
                    config.status = WifiConfiguration.Status.CURRENT;
                else if (result[3].indexOf("[DISABLED]") != -1)
                    config.status = WifiConfiguration.Status.DISABLED;
                else
                    config.status = WifiConfiguration.Status.ENABLED;
            } else {
                config.status = WifiConfiguration.Status.ENABLED;
            }
            readNetworkVariables(config);
            if (config.priority > mLastPriority) {
                mLastPriority = config.priority;
            }

            config.setIpAssignment(IpAssignment.DHCP);
            config.setProxySettings(ProxySettings.NONE);

            if (mNetworkIds.containsKey(configKey(config))) {
                // That SSID is already known, just ignore this duplicate entry
                if (VDBG) localLog("discarded duplicate network", config.networkId);
            } else if(config.isValid()){
                mConfiguredNetworks.put(config.networkId, config);
                mNetworkIds.put(configKey(config), config.networkId);
                if (VDBG) localLog("loaded configured network", config.networkId);
            } else {
                if (DBG) log("Ingnoring loaded configured for network " + config.networkId
                    + " because config are not vaild");
            }
        }

        readIpAndProxyConfigurations();
        readNetworkHistory();

        sendConfiguredNetworksChangedBroadcast();

        if (VDBG) localLog("loadConfiguredNetworks loaded " + mNetworkIds.size() + " networks");

        if (mNetworkIds.size() == 0) {
            // no networks? Lets log if the wpa_supplicant.conf file contents
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(SUPPLICANT_CONFIG_FILE));
                if (VDBG) localLog("--- Begin wpa_supplicant.conf Contents ---");
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (VDBG) localLog(line);
                }
                if (VDBG) localLog("--- End wpa_supplicant.conf Contents ---");
            } catch (FileNotFoundException e) {
                if (VDBG) localLog("Could not open " + SUPPLICANT_CONFIG_FILE + ", " + e);
            } catch (IOException e) {
                if (VDBG) localLog("Could not read " + SUPPLICANT_CONFIG_FILE + ", " + e);
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (IOException e) {
                    // Just ignore the fact that we couldn't close
                }
            }
        }
    }

    private String readNetworkVariableFromSupplicantFile(String ssid, String key) {
        BufferedReader reader = null;
        if (VDBG) loge("readNetworkVariableFromSupplicantFile ssid=[" + ssid + "] key=" + key);

        String value = null;
        try {
            reader = new BufferedReader(new FileReader(SUPPLICANT_CONFIG_FILE));
            boolean found = false;
            boolean networkMatched = false;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (VDBG) loge(line);

                if (line.matches("[ \\t]*network=\\{")) {
                    found = true;
                    networkMatched = false;
                } else if (line.matches("[ \\t]*\\{")) {
                    found = false;
                    networkMatched = false;
                }

                if (found) {
                    int index;
                    if ((index = line.indexOf("ssid=")) >= 0) {
                        String networkSSid = line.substring(index + 5);
                        if (networkSSid.regionMatches(0, ssid, 0, ssid.length())) {
                            networkMatched = true;
                        } else {
                            networkMatched = false;
                        }
                    }

                    if (networkMatched) {
                        if ((index = line.indexOf(key + "=")) >= 0) {

                            value = line.substring(index + key.length() + 1);
                            if (VDBG) loge("found key " + value);

                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            if (VDBG) loge("Could not open " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } catch (IOException e) {
            if (VDBG) loge("Could not read " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Just ignore the fact that we couldn't close
            }
        }

        return value;
    }

    /* Mark all networks except specified netId as disabled */
    private void markAllNetworksDisabledExcept(int netId) {
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            if(config != null && config.networkId != netId) {
                if (config.status != Status.DISABLED) {
                    config.status = Status.DISABLED;
                    config.disableReason = WifiConfiguration.DISABLED_UNKNOWN_REASON;
                }
            }
        }
    }

    private void markAllNetworksDisabled() {
        markAllNetworksDisabledExcept(INVALID_NETWORK_ID);
    }

    boolean needsUnlockedKeyStore() {

        // Any network using certificates to authenticate access requires
        // unlocked key store; unless the certificates can be stored with
        // hardware encryption

        for(WifiConfiguration config : mConfiguredNetworks.values()) {

            if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP)
                    && config.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {

                if (needsSoftwareBackedKeyStore(config.enterpriseConfig)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void writeKnownNetworkHistory() {
        if (VDBG) {
            loge(" writeKnownNetworkHistory() num networks:" +
                    Integer.toString(mConfiguredNetworks.size()) );
        }

        /* Make a copy */
        final List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            networks.add(new WifiConfiguration(config));
        }

        mWriter.write(networkHistoryConfigFile, new DelayedDiskWrite.Writer() {
            public void onWriteCalled(DataOutputStream out) throws IOException {
                for (WifiConfiguration config : networks) {
                    //loge("onWriteCalled write SSID: " + config.SSID);
                   /* if (config.getLinkProperties() != null)
                        loge(" lp " + config.getLinkProperties().toString());
                    else
                        loge("attempt config w/o lp");
                    */

                    if (DBG) {
                        loge("saving network history: " + config.SSID  + " gw: " +
                                config.defaultGwMacAddress + " autojoin status: " +
                                config.autoJoinStatus + " ephemeral=" + config.ephemeral);
                    }
                    if (config.ephemeral == true)
                        continue;

                    out.writeChars(CONFIG_KEY);
                    out.writeChars(config.configKey());
                    out.writeChars(SEPARATOR_KEY);

                    out.writeChars(SSID_KEY);
                    out.writeChars(config.SSID);
                    out.writeChars(SEPARATOR_KEY);

                    out.writeChars(PRIORITY_KEY);
                    out.writeChars(Integer.toString(config.priority));
                    out.writeChars(SEPARATOR_KEY);
                    out.writeChars(STATUS_KEY);
                    out.writeChars(Integer.toString(config.autoJoinStatus));
                    out.writeChars(SEPARATOR_KEY);
                    out.writeChars(NETWORK_ID_KEY);
                    out.writeChars(Integer.toString(config.networkId));
                    out.writeChars(SEPARATOR_KEY);

                    String allowedKeyManagementString =
                            makeString(config.allowedKeyManagement,
                                    WifiConfiguration.KeyMgmt.strings);
                    out.writeChars(AUTH_KEY);
                    out.writeChars(allowedKeyManagementString);
                    out.writeChars(SEPARATOR_KEY);

                    if (config.connectChoices != null) {
                        for (String key : config.connectChoices.keySet()) {
                            out.writeChars(CHOICE_KEY);
                            out.writeChars(key);
                            out.writeChars(SEPARATOR_KEY);
                        }
                    }
                    if (config.linkedConfigurations != null) {
                        for (String key : config.linkedConfigurations.keySet()) {
                            out.writeChars(LINK_KEY);
                            out.writeChars(key);
                            out.writeChars(SEPARATOR_KEY);
                        }
                    }

                    if (config.getLinkProperties() != null) {
                        String macAddress = config.defaultGwMacAddress;
                        if (macAddress != null) {
                            out.writeChars(DEFAULT_GW_KEY);
                            out.writeChars(macAddress);
                            out.writeChars(SEPARATOR_KEY);
                        }
                    }

                    if (config.scanResultCache != null) {
                        for (ScanResult result : config.scanResultCache.values()) {
                            out.writeChars(BSSID_KEY);
                            out.writeChars(result.BSSID);
                            out.writeChars(SEPARATOR_KEY);

                            out.writeChars(FREQ_KEY);
                            out.writeChars(Integer.toString(result.frequency));
                            out.writeChars(SEPARATOR_KEY);

                            out.writeChars(RSSI_KEY);
                            out.writeChars(Integer.toString(result.level));
                            out.writeChars(SEPARATOR_KEY);

                            if (result.seen != 0) {
                                out.writeChars(MILLI_KEY);
                                out.writeChars(Long.toString(result.seen));
                                out.writeChars(SEPARATOR_KEY);
                            }
                            out.writeChars(BSSID_KEY_END);
                            out.writeChars(SEPARATOR_KEY);
                        }
                    }
                    out.writeChars(SEPARATOR_KEY);
                }
            }

        });
    }

    public void setLastSelectedConfiguration(int netId) {
        if (DBG) {
            loge("setLastSelectedConfiguration " + Integer.toString(netId));
        }
        if (netId == WifiConfiguration.INVALID_NETWORK_ID) {
            lastSelectedConfiguration = null;
        } else {
            WifiConfiguration selected = getWifiConfiguration(netId);
            if (selected == null) {
                lastSelectedConfiguration = null;
            } else {
                lastSelectedConfiguration = selected.configKey();
                loge("setLastSelectedConfiguration found it " + lastSelectedConfiguration);
            }
        }
    }

    public String getLastSelectedConfiguration() {
        return lastSelectedConfiguration;
    }


        private void readNetworkHistory() {
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(
                    networkHistoryConfigFile)));
            WifiConfiguration config = null;
            while (true) {
                int id = -1;
                String key = in.readUTF();

                String bssid = null;
                String ssid = null;

                int freq = 0;
                long seen = 0;
                int rssi = -128;
                String caps = null;
                if (key.startsWith(CONFIG_KEY)) {

                    if (config != null) {
                        config = null;
                    }
                    String configKey = key.replace(CONFIG_KEY, "");
                    // get the networkId for that config Key
                    Integer n = mNetworkIds.get(configKey.hashCode());
                    // skip reading that configuration data
                    // since we don't have a corresponding network ID
                    if (n == null) continue;
                    config = mConfiguredNetworks.get(n);
                    ssid = null;
                    bssid = null;
                    freq = 0;
                    seen = 0;
                    rssi = -128;
                    caps = null;

                } else if (config != null) {
                    if (key.startsWith(SSID_KEY)) {
                        ssid = key.replace(SSID_KEY, "");
                        if (config.SSID != ssid) {
                            loge("Error parsing network history file");
                            config = null; //error
                            ssid = null;
                        }
                    }

                    if (key.startsWith(DEFAULT_GW_KEY)) {
                        String gateway = key.replace(DEFAULT_GW_KEY, "");
                        config.defaultGwMacAddress = gateway;

                    }

                    if (key.startsWith(STATUS_KEY)) {
                        String status = key.replace(STATUS_KEY, "");
                        config.autoJoinStatus = Integer.getInteger(status);
                    }

                    if (key.startsWith(CHOICE_KEY)) {
                        String configKey = key.replace(CHOICE_KEY, "");
                        if (config.connectChoices == null) {
                            config.connectChoices = new HashMap<String, Integer>();
                        }
                        if (config.connectChoices != null) {
                            config.connectChoices.put(configKey, -1);
                        }
                    }

                    if (key.startsWith(LINK_KEY)) {
                        String configKey = key.replace(LINK_KEY, "");
                        if (config.linkedConfigurations == null) {
                            config.linkedConfigurations = new HashMap<String, Integer>();
                        }
                        if (config.linkedConfigurations != null) {
                            config.linkedConfigurations.put(configKey, -1);
                        }
                    }

                    if (key.startsWith(BSSID_KEY)) {
                        if (key.startsWith(BSSID_KEY)) {
                            bssid = key.replace(BSSID_KEY, "");
                            freq = 0;
                            seen = 0;
                            rssi = -127;
                            caps = "";
                        }

                        if (key.startsWith(RSSI_KEY)) {
                            String lvl = key.replace(RSSI_KEY, "");
                            rssi = Integer.getInteger(lvl);
                        }
                        if (key.startsWith(FREQ_KEY)) {
                            String lvl = key.replace(FREQ_KEY, "");
                            freq = Integer.getInteger(lvl);
                        }
                        if (key.startsWith(DATE_KEY)) {
                        /*
                         * when reading the configuration from file we don't update the date
                         * so as to avoid reading back stale or non-sensical data that would
                         * depend on network time.
                         * The date of a WifiConfiguration should only come from actual scan result.
                         *
                        String s = key.replace(FREQ_KEY, "");
                        seen = Integer.getInteger(s);
                        */
                        }

                        if (key.startsWith(BSSID_KEY_END)) {

                            if ((bssid != null) && (ssid != null)) {

                                if (config.scanResultCache == null) {
                                    config.scanResultCache = new HashMap<String, ScanResult>();
                                }

                                WifiSsid wssid = WifiSsid.createFromAsciiEncoded(ssid);
                                ScanResult result = new ScanResult(wssid, bssid,
                                        caps, rssi, freq, (long) 0);
                                result.seen = seen;
                                config.scanResultCache.put(bssid, result);
                            }
                        }
                    }
                }
            }
        } catch (EOFException ignore) {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                }
            }
        } catch (IOException e) {
            loge("Error parsing configuration" + e);
        }

        if(in!=null) {
            try {
                in.close();
            } catch (Exception e) {
            }
        }
    }

    private void writeIpAndProxyConfigurations() {
        final SparseArray<IpConfiguration> networks = new SparseArray<IpConfiguration>();
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            if (!config.ephemeral) {
                networks.put(configKey(config), config.getIpConfiguration());
            }
        }

        super.writeIpAndProxyConfigurations(ipConfigFile, networks);
    }

    private void readIpAndProxyConfigurations() {
        SparseArray<IpConfiguration> networks = super.readIpAndProxyConfigurations(ipConfigFile);

        if (networks == null) {
            // IpConfigStore.readIpAndProxyConfigurations has already logged an error.
            return;
        }

        for (int i = 0; i < networks.size(); i++) {
            Integer id = (Integer) i;
            WifiConfiguration config = mConfiguredNetworks.get(mNetworkIds.get(id));

            if (config == null) {
                loge("configuration found for missing network, ignored");
            } else {
                config.setIpConfiguration(networks.valueAt(i));
            }
        }
    }

    /*
     * Convert string to Hexadecimal before passing to wifi native layer
     * In native function "doCommand()" have trouble in converting Unicode character string to UTF8
     * conversion to hex is required because SSIDs can have space characters in them;
     * and that can confuses the supplicant because it uses space charaters as delimiters
     */

    private String encodeSSID(String str){
        String tmp = removeDoubleQuotes(str);
        return String.format("%x", new BigInteger(1, tmp.getBytes(Charset.forName("UTF-8"))));
    }

    private NetworkUpdateResult addOrUpdateNetworkNative(WifiConfiguration config) {
        /*
         * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */

        if (VDBG) localLog("addOrUpdateNetworkNative " + config.getPrintableSsid());

        int netId = config.networkId;
        boolean newNetwork = false;
        // networkId of INVALID_NETWORK_ID means we want to create a new network
        if (netId == INVALID_NETWORK_ID) {
            Integer savedNetId = mNetworkIds.get(configKey(config));
            if (savedNetId != null) {
                netId = savedNetId;
            } else {
                newNetwork = true;
                netId = mWifiNative.addNetwork();
                if (netId < 0) {
                    loge("Failed to add a network!");
                    return new NetworkUpdateResult(INVALID_NETWORK_ID);
                } else {
                    loge("addOrUpdateNetworkNative created netId=" + netId);
                }
            }
        }

        boolean updateFailed = true;

        setVariables: {

            if (config.SSID != null &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.ssidVarName,
                        encodeSSID(config.SSID))) {
                loge("failed to set SSID: "+config.SSID);
                break setVariables;
            }

            if (config.BSSID != null &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.bssidVarName,
                        config.BSSID)) {
                loge("failed to set BSSID: "+config.BSSID);
                break setVariables;
            }

            String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
            if (config.allowedKeyManagement.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.KeyMgmt.varName,
                        allowedKeyManagementString)) {
                loge("failed to set key_mgmt: "+
                        allowedKeyManagementString);
                break setVariables;
            }

            String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
            if (config.allowedProtocols.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.Protocol.varName,
                        allowedProtocolsString)) {
                loge("failed to set proto: "+
                        allowedProtocolsString);
                break setVariables;
            }

            String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms, WifiConfiguration.AuthAlgorithm.strings);
            if (config.allowedAuthAlgorithms.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.AuthAlgorithm.varName,
                        allowedAuthAlgorithmsString)) {
                loge("failed to set auth_alg: "+
                        allowedAuthAlgorithmsString);
                break setVariables;
            }

            String allowedPairwiseCiphersString =
                    makeString(config.allowedPairwiseCiphers,
                    WifiConfiguration.PairwiseCipher.strings);
            if (config.allowedPairwiseCiphers.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.PairwiseCipher.varName,
                        allowedPairwiseCiphersString)) {
                loge("failed to set pairwise: "+
                        allowedPairwiseCiphersString);
                break setVariables;
            }

            String allowedGroupCiphersString =
                makeString(config.allowedGroupCiphers, WifiConfiguration.GroupCipher.strings);
            if (config.allowedGroupCiphers.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.GroupCipher.varName,
                        allowedGroupCiphersString)) {
                loge("failed to set group: "+
                        allowedGroupCiphersString);
                break setVariables;
            }

            // Prevent client screw-up by passing in a WifiConfiguration we gave it
            // by preventing "*" as a key.
            if (config.preSharedKey != null && !config.preSharedKey.equals("*") &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.pskVarName,
                        config.preSharedKey)) {
                loge("failed to set psk");
                break setVariables;
            }

            boolean hasSetKey = false;
            if (config.wepKeys != null) {
                for (int i = 0; i < config.wepKeys.length; i++) {
                    // Prevent client screw-up by passing in a WifiConfiguration we gave it
                    // by preventing "*" as a key.
                    if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                        if (!mWifiNative.setNetworkVariable(
                                    netId,
                                    WifiConfiguration.wepKeyVarNames[i],
                                    config.wepKeys[i])) {
                            loge("failed to set wep_key" + i + ": " + config.wepKeys[i]);
                            break setVariables;
                        }
                        hasSetKey = true;
                    }
                }
            }

            if (hasSetKey) {
                if (!mWifiNative.setNetworkVariable(
                            netId,
                            WifiConfiguration.wepTxKeyIdxVarName,
                            Integer.toString(config.wepTxKeyIndex))) {
                    loge("failed to set wep_tx_keyidx: " + config.wepTxKeyIndex);
                    break setVariables;
                }
            }

            if (!mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.priorityVarName,
                        Integer.toString(config.priority))) {
                loge(config.SSID + ": failed to set priority: "
                        +config.priority);
                break setVariables;
            }

            if (config.hiddenSSID && !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.hiddenSSIDVarName,
                        Integer.toString(config.hiddenSSID ? 1 : 0))) {
                loge(config.SSID + ": failed to set hiddenSSID: "+
                        config.hiddenSSID);
                break setVariables;
            }

            if (config.enterpriseConfig != null &&
                    config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {

                WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;

                if (needsKeyStore(enterpriseConfig)) {
                    /**
                     * Keyguard settings may eventually be controlled by device policy.
                     * We check here if keystore is unlocked before installing
                     * credentials.
                     * TODO: Do we need a dialog here ?
                     */
                    if (mKeyStore.state() != KeyStore.State.UNLOCKED) {
                        loge(config.SSID + ": key store is locked");
                        break setVariables;
                    }

                    try {
                        /* config passed may include only fields being updated.
                         * In order to generate the key id, fetch uninitialized
                         * fields from the currently tracked configuration
                         */
                        WifiConfiguration currentConfig = mConfiguredNetworks.get(netId);
                        String keyId = config.getKeyIdForCredentials(currentConfig);

                        if (!installKeys(enterpriseConfig, keyId)) {
                            loge(config.SSID + ": failed to install keys");
                            break setVariables;
                        }
                    } catch (IllegalStateException e) {
                        loge(config.SSID + " invalid config for key installation");
                        break setVariables;
                    }
                }

                HashMap<String, String> enterpriseFields = enterpriseConfig.getFields();
                for (String key : enterpriseFields.keySet()) {
                        String value = enterpriseFields.get(key);
                        if (!mWifiNative.setNetworkVariable(
                                    netId,
                                    key,
                                    value)) {
                            removeKeys(enterpriseConfig);
                            loge(config.SSID + ": failed to set " + key +
                                    ": " + value);
                            break setVariables;
                        }
                }
            }
            updateFailed = false;
        } //end of setVariables

        if (updateFailed) {
            if (newNetwork) {
                mWifiNative.removeNetwork(netId);
                loge("Failed to set a network variable, removed network: " + netId);
            }
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        /* An update of the network variables requires reading them
         * back from the supplicant to update mConfiguredNetworks.
         * This is because some of the variables (SSID, wep keys &
         * passphrases) reflect different values when read back than
         * when written. For example, wep key is stored as * irrespective
         * of the value sent to the supplicant
         */
        WifiConfiguration currentConfig = mConfiguredNetworks.get(netId);
        if (currentConfig == null) {
            currentConfig = new WifiConfiguration();
            currentConfig.setIpAssignment(IpAssignment.DHCP);
            currentConfig.setProxySettings(ProxySettings.NONE);
            currentConfig.networkId = netId;
        }

        if (DBG) loge("will read network variables netId=" + Integer.toString(netId));

        readNetworkVariables(currentConfig);

        mConfiguredNetworks.put(netId, currentConfig);
        mNetworkIds.put(configKey(currentConfig), netId);

        NetworkUpdateResult result = writeIpAndProxyConfigurationsOnChange(currentConfig, config);
        result.setIsNewNetwork(newNetwork);
        result.setNetworkId(netId);
        return result;
    }


    public void linkConfiguration(WifiConfiguration config) {
        for (WifiConfiguration link : mConfiguredNetworks.values()) {
            boolean doLink = false;

            if (link.configKey().equals(config.configKey())) {
                continue;
            }

            //autojoin will be abllowed to dynamically jump from a linked configuration
            //to another, hence only link configurations that have equivalent level of security
            if (!link.allowedKeyManagement.equals(config.allowedKeyManagement)) {
                continue;
            }

            if (config.defaultGwMacAddress != null && link.defaultGwMacAddress != null) {
                //if both default GW are known, compare based on RSSI only if the GW is equal
                if (config.defaultGwMacAddress.equals(link.defaultGwMacAddress)) {

                    if (VDBG) {
                        loge("linkConfiguration link due to same gw" + link.SSID +
                                " and " + config.SSID + " GW " + config.defaultGwMacAddress);
                    }
                    doLink = true;
                }
            } else {
                // we do not know BOTH default gateways hence we will try to link
                // hoping that they are indeed behind the same gateway
                // once both WifiConfiguration will have been tried we will know
                // the default gateway and revisit the choice of linking them
                if ((config.scanResultCache != null) && (config.scanResultCache.size() <= 5)
                        && (link.scanResultCache != null) && (link.scanResultCache.size() <= 5)) {
                    String abssid = "";
                    String bbssid = "";
                    for (String key : config.scanResultCache.keySet()) {
                        abssid = key;
                    }
                    for (String key : link.scanResultCache.keySet()) {
                        bbssid = key;
                    }
                    if (VDBG) {
                        loge("linkConfiguration link due to DBDC BSSID match " + link.SSID +
                                " and " + config.SSID + " bssida " + abssid + " bssidb " + bbssid);
                    }
                    if (abssid.regionMatches(true, 0, bbssid, 0, 16)) {
                        //if first 16 ascii characters of BSSID matches, we assume this is a DBDC
                        doLink = true;
                    }
                }
            }

            if (doLink) {
                if (VDBG)
                    loge("linkConfiguration: will link " + link.SSID + " and " + config.SSID);
                if (link.linkedConfigurations == null) {
                    link.linkedConfigurations = new HashMap<String, Integer>();
                }
                if (config.linkedConfigurations == null) {
                    config.linkedConfigurations = new HashMap<String, Integer>();
                }
                link.linkedConfigurations.put(config.configKey(), Integer.valueOf(1));

                config.linkedConfigurations.put(link.configKey(), Integer.valueOf(1));

            } else {
                //todo if they are linked, break the link
            }
        }
    }

    /*
     * We try to link a scan result with a WifiConfiguration for which SSID and ket management dont match,
     * for instance, we try identify the 5GHz SSID of a DBDC AP, even though we know only of the 2.4GHz
     *
     * Obviously, this function is not optimal since it is used to compare every scan
     * result with every Saved WifiConfiguration, with a string.equals operation.
     * As a speed up, might be better to implement the mConfiguredNetworks store as a
     * <String, WifiConfiguration> object instead of a <Integer, WifiConfiguration> object
     * so as to speed this up. Also to prevent the tiny probability of hash collision.
     *
     */
    public WifiConfiguration associateWithConfiguration(ScanResult result) {
        String configKey = WifiConfiguration.configKey(result);
        if (configKey == null) {
            if (DBG) loge("associateWithConfiguration(): no config key " );
            return null;
        }

        //need to compare with quoted string
        String SSID = "\"" + result.SSID + "\"";

        WifiConfiguration config = null;
        for (WifiConfiguration link : mConfiguredNetworks.values()) {
            boolean doLink = false;

            if (configKey.equals(link.configKey())) {
                if (VDBG) loge("associateWithConfiguration(): found it!!! " + configKey );
                return link; //found it exactly
            }

            if ((link.scanResultCache != null) && (link.scanResultCache.size() <= 4)) {
                String bssid = "";
                for (String key : link.scanResultCache.keySet()) {
                    bssid = key;
                }

                if (result.BSSID.regionMatches(true, 0, bssid, 0, 16)
                        && SSID.regionMatches(false, 0, link.SSID, 0, 3)) {
                    // if first 16 ascii characters of BSSID matches, and first 3
                    // characters of SSID match, we assume this is a home setup
                    // and thus we will try to transfer the password from the known
                    // BSSID/SSID to the recently found BSSID/SSID

                    //if (VDBG)
                    //    loge("associateWithConfiguration OK " );
                    doLink = true;
                }
            }

            if (doLink) {
                //try to make a non verified WifiConfiguration
                if (VDBG) {
                    loge("associateWithConfiguration: will create " +
                            result.SSID + " and associate it with: " + link.SSID);
                }
                config = wifiConfigurationFromScanResult(result);
                config.selfAdded = true;
                if (config != null) {
                    if (config.allowedKeyManagement.equals(link.allowedKeyManagement) &&
                            config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {

                        //transfer the credentials

                        String psk = readNetworkVariableFromSupplicantFile(link.SSID, "psk");
                        if (psk != null) {


                            config.preSharedKey = psk;
                            if (VDBG) {
                                if (config.preSharedKey != null)
                                    loge(" transfer PSK : " + config.preSharedKey);
                            }

                            //link configurations
                            if (link.linkedConfigurations == null) {
                                link.linkedConfigurations = new HashMap<String, Integer>();
                            }
                            if (config.linkedConfigurations == null) {
                                config.linkedConfigurations = new HashMap<String, Integer>();
                            }
                            link.linkedConfigurations.put(config.configKey(), Integer.valueOf(1));
                            config.linkedConfigurations.put(link.configKey(), Integer.valueOf(1));
                        } else {
                            config = null;
                        }
                    }
                }
            } else {
                //todo if they are linked, break the link
            }
        }
        return config;
    }


    public WifiConfiguration updateSavedNetworkHistory(ScanResult scanResult) {
        WifiConfiguration found = null;
        if (scanResult == null)
            return found;

        //first step, look for this scan Result by SSID + Key Management
        String key = WifiConfiguration.configKey(scanResult);
        int hash = key.hashCode();

        Integer netId = mNetworkIds.get(hash);
        if (netId == null) return null;
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
           if (config.scanResultCache == null) {
                config.scanResultCache = new HashMap<String, ScanResult>();
           }
           if (config.scanResultCache == null) {
                return null;
           }
           //add the scan result to this WifiConfiguration
           config.scanResultCache.put(scanResult.BSSID, scanResult);
           mConfiguredNetworks.put(netId, config);
           linkConfiguration(config);
           found = config;
        }

        if (VDBG) {
            config = mConfiguredNetworks.get(netId);
            if (config != null) {
                if (config.scanResultCache != null) {
                    loge("                    tested " + scanResult.SSID + " " +
                            scanResult.BSSID + " key : " + key + " num: " +
                            Integer.toString(config.scanResultCache.size()));
                } else {
                    loge("                    tested " + scanResult.SSID + " " +
                            scanResult.BSSID + " key : " + key);
                }
            }
        }
        return found;

    }

    /* Compare current and new configuration and write to file on change */
    private NetworkUpdateResult writeIpAndProxyConfigurationsOnChange(
            WifiConfiguration currentConfig,
            WifiConfiguration newConfig) {
        boolean ipChanged = false;
        boolean proxyChanged = false;
        LinkProperties linkProperties = null;

        if (VDBG) {
            loge("writeIpAndProxyConfigurationsOnChange: " + currentConfig.SSID + " -> " +
                    newConfig.SSID + " path: " + ipConfigFile);
        }


        switch (newConfig.getIpAssignment()) {
            case STATIC:
                Collection<LinkAddress> currentLinkAddresses = currentConfig.getLinkProperties()
                        .getLinkAddresses();
                Collection<LinkAddress> newLinkAddresses = newConfig.getLinkProperties()
                        .getLinkAddresses();
                Collection<InetAddress> currentDnses = currentConfig.getLinkProperties().getDnses();
                Collection<InetAddress> newDnses = newConfig.getLinkProperties().getDnses();
                Collection<RouteInfo> currentRoutes = currentConfig.getLinkProperties().getRoutes();
                Collection<RouteInfo> newRoutes = newConfig.getLinkProperties().getRoutes();

                boolean linkAddressesDiffer =
                        (currentLinkAddresses.size() != newLinkAddresses.size()) ||
                        !currentLinkAddresses.containsAll(newLinkAddresses);
                boolean dnsesDiffer = (currentDnses.size() != newDnses.size()) ||
                        !currentDnses.containsAll(newDnses);
                boolean routesDiffer = (currentRoutes.size() != newRoutes.size()) ||
                        !currentRoutes.containsAll(newRoutes);

                if ((currentConfig.getIpAssignment() != newConfig.getIpAssignment()) ||
                        linkAddressesDiffer ||
                        dnsesDiffer ||
                        routesDiffer) {
                    ipChanged = true;
                }
                break;
            case DHCP:
                if (currentConfig.getIpAssignment() != newConfig.getIpAssignment()) {
                    ipChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                loge("Ignore invalid ip assignment during write");
                break;
        }

        switch (newConfig.getProxySettings()) {
            case STATIC:
            case PAC:
                ProxyInfo newHttpProxy = newConfig.getLinkProperties().getHttpProxy();
                ProxyInfo currentHttpProxy = currentConfig.getLinkProperties().getHttpProxy();

                if (newHttpProxy != null) {
                    proxyChanged = !newHttpProxy.equals(currentHttpProxy);
                } else {
                    proxyChanged = (currentHttpProxy != null);
                }
                break;
            case NONE:
                if (currentConfig.getProxySettings() != newConfig.getProxySettings()) {
                    proxyChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                loge("Ignore invalid proxy configuration during write");
                break;
        }

        if (!ipChanged) {
            linkProperties = copyIpSettingsFromConfig(currentConfig);
        } else {
            currentConfig.setIpAssignment(newConfig.getIpAssignment());
            linkProperties = copyIpSettingsFromConfig(newConfig);
            log("IP config changed SSID = " + currentConfig.SSID + " linkProperties: " +
                    linkProperties.toString());
        }


        if (!proxyChanged) {
            linkProperties.setHttpProxy(currentConfig.getLinkProperties().getHttpProxy());
        } else {
            currentConfig.setProxySettings(newConfig.getProxySettings());
            linkProperties.setHttpProxy(newConfig.getLinkProperties().getHttpProxy());
            log("proxy changed SSID = " + currentConfig.SSID);
            if (linkProperties.getHttpProxy() != null) {
                log(" proxyProperties: " + linkProperties.getHttpProxy().toString());
            }
        }

        if (ipChanged || proxyChanged) {
            currentConfig.setLinkProperties(linkProperties);
            writeIpAndProxyConfigurations();
            sendConfiguredNetworksChangedBroadcast(currentConfig,
                    WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        }
        return new NetworkUpdateResult(ipChanged, proxyChanged);
    }

    private LinkProperties copyIpSettingsFromConfig(WifiConfiguration config) {
        LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(config.getLinkProperties().getInterfaceName());
        for (LinkAddress linkAddr : config.getLinkProperties().getLinkAddresses()) {
            linkProperties.addLinkAddress(linkAddr);
        }
        for (RouteInfo route : config.getLinkProperties().getRoutes()) {
            linkProperties.addRoute(route);
        }
        for (InetAddress dns : config.getLinkProperties().getDnses()) {
            linkProperties.addDns(dns);
        }
        return linkProperties;
    }

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    private void readNetworkVariables(WifiConfiguration config) {

        int netId = config.networkId;
        if (netId < 0)
            return;

        /*
         * TODO: maybe should have a native method that takes an array of
         * variable names and returns an array of values. But we'd still
         * be doing a round trip to the supplicant daemon for each variable.
         */
        String value;

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.ssidVarName);
        if (!TextUtils.isEmpty(value)) {
            if (value.charAt(0) != '"') {
                config.SSID = "\"" + WifiSsid.createFromHex(value).toString() + "\"";
                //TODO: convert a hex string that is not UTF-8 decodable to a P-formatted
                //supplicant string
            } else {
                config.SSID = value;
            }
        } else {
            config.SSID = null;
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.bssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.BSSID = value;
        } else {
            config.BSSID = null;
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.priorityVarName);
        config.priority = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.priority = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.hiddenSSIDVarName);
        config.hiddenSSID = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.hiddenSSID = Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.wepTxKeyIdxVarName);
        config.wepTxKeyIndex = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.wepTxKeyIndex = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        for (int i = 0; i < 4; i++) {
            value = mWifiNative.getNetworkVariable(netId,
                    WifiConfiguration.wepKeyVarNames[i]);
            if (!TextUtils.isEmpty(value)) {
                config.wepKeys[i] = value;
            } else {
                config.wepKeys[i] = null;
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.pskVarName);
        if (!TextUtils.isEmpty(value)) {
            config.preSharedKey = value;
        } else {
            config.preSharedKey = null;
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.Protocol.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.Protocol.strings);
                if (0 <= index) {
                    config.allowedProtocols.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.KeyMgmt.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.KeyMgmt.strings);
                if (0 <= index) {
                    config.allowedKeyManagement.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.AuthAlgorithm.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.AuthAlgorithm.strings);
                if (0 <= index) {
                    config.allowedAuthAlgorithms.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.PairwiseCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.PairwiseCipher.strings);
                if (0 <= index) {
                    config.allowedPairwiseCiphers.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.GroupCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.GroupCipher.strings);
                if (0 <= index) {
                    config.allowedGroupCiphers.set(index);
                }
            }
        }

        if (config.enterpriseConfig == null) {
            config.enterpriseConfig = new WifiEnterpriseConfig();
        }
        HashMap<String, String> enterpriseFields = config.enterpriseConfig.getFields();
        for (String key : ENTERPRISE_CONFIG_SUPPLICANT_KEYS) {
            value = mWifiNative.getNetworkVariable(netId, key);
            if (!TextUtils.isEmpty(value)) {
                enterpriseFields.put(key, removeDoubleQuotes(value));
            } else {
                enterpriseFields.put(key, EMPTY_VALUE);
            }
        }

        if (migrateOldEapTlsNative(config.enterpriseConfig, netId)) {
            saveConfig();
        }

        migrateCerts(config.enterpriseConfig);
        // initializeSoftwareKeystoreFlag(config.enterpriseConfig, mKeyStore);
    }

    private static String removeDoubleQuotes(String string) {
        int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"')
                && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private static String makeString(BitSet set, String[] strings) {
        StringBuffer buf = new StringBuffer();
        int nextSetBit = -1;

        /* Make sure all set bits are in [0, strings.length) to avoid
         * going out of bounds on strings.  (Shouldn't happen, but...) */
        set = set.get(0, strings.length);

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            buf.append(strings[nextSetBit].replace('_', '-')).append(' ');
        }

        // remove trailing space
        if (set.cardinality() > 0) {
            buf.setLength(buf.length() - 1);
        }

        return buf.toString();
    }

    private int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++)
            if (string.equals(strings[i]))
                return i;

        // if we ever get here, we should probably add the
        // value to WifiConfiguration to reflect that it's
        // supported by the WPA supplicant
        loge("Failed to look-up a string: " + string);

        return -1;
    }

    /* return the allowed key management based on a scan result */

    public WifiConfiguration wifiConfigurationFromScanResult(ScanResult result) {
        WifiConfiguration config = new WifiConfiguration();

        config.SSID = "\"" + result.SSID + "\"";

        if (VDBG) {
            loge("WifiConfiguration from scan results " +
                    config.SSID + " cap " + result.capabilities);
        }
        if (result.capabilities.contains("WEP")) {
            config.allowedKeyManagement.set(KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN); //?
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        }

        if (result.capabilities.contains("PSK")) {
            config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        }

        if (result.capabilities.contains("EAP")) {
            //this is probably wrong, as we don't have a way to enter the enterprise config
            config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
            config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        }

        config.scanResultCache = new HashMap<String, ScanResult>();
        if (config.scanResultCache == null)
            return null;
        config.scanResultCache.put(result.BSSID, result);

        return config;
    }


    /* Returns a unique for a given configuration */
    private static int configKey(WifiConfiguration config) {
        String key = config.configKey();
        return key.hashCode();
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiConfigStore");
        pw.println("mLastPriority " + mLastPriority);
        pw.println("Configured networks");
        for (WifiConfiguration conf : getConfiguredNetworks()) {
            pw.println(conf);
        }
        pw.println();

        if (mLocalLog != null) {
            pw.println("WifiConfigStore - Log Begin ----");
            mLocalLog.dump(fd, pw, args);
            pw.println("WifiConfigStore - Log End ----");
        }
    }

    public String getConfigFile() {
        return ipConfigFile;
    }

    protected void loge(String s) {
        long now = SystemClock.elapsedRealtimeNanos();
        String ts = String.format("[%,d us] ", now/1000);
        Log.e(TAG, ts+s+ " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[3].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[4].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[5].getMethodName());
    }

    protected void log(String s) {
        Log.d(TAG, s);
    }

    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(s);
        }
    }

    private void localLog(String s, int netId) {
        if (mLocalLog == null) {
            return;
        }

        WifiConfiguration config;
        synchronized(mConfiguredNetworks) {
            config = mConfiguredNetworks.get(netId);
        }

        if (config != null) {
            mLocalLog.log(s + " " + config.getPrintableSsid());
        } else {
            mLocalLog.log(s + " " + netId);
        }
    }

    // Certificate and private key management for EnterpriseConfig
    static boolean needsKeyStore(WifiEnterpriseConfig config) {
        // Has no keys to be installed
        if (config.getClientCertificate() == null && config.getCaCertificate() == null)
            return false;
        return true;
    }

    static boolean isHardwareBackedKey(PrivateKey key) {
        return KeyChain.isBoundKeyAlgorithm(key.getAlgorithm());
    }

    static boolean hasHardwareBackedKey(Certificate certificate) {
        return KeyChain.isBoundKeyAlgorithm(certificate.getPublicKey().getAlgorithm());
    }

    static boolean needsSoftwareBackedKeyStore(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        if (!TextUtils.isEmpty(client)) {
            // a valid client certificate is configured

            // BUGBUG: keyStore.get() never returns certBytes; because it is not
            // taking WIFI_UID as a parameter. It always looks for certificate
            // with SYSTEM_UID, and never finds any Wifi certificates. Assuming that
            // all certificates need software keystore until we get the get() API
            // fixed.

            return true;
        }

        /*
        try {

            if (DBG) Slog.d(TAG, "Loading client certificate " + Credentials
                    .USER_CERTIFICATE + client);

            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            if (factory == null) {
                Slog.e(TAG, "Error getting certificate factory");
                return;
            }

            byte[] certBytes = keyStore.get(Credentials.USER_CERTIFICATE + client);
            if (certBytes != null) {
                Certificate cert = (X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(certBytes));

                if (cert != null) {
                    mNeedsSoftwareKeystore = hasHardwareBackedKey(cert);

                    if (DBG) Slog.d(TAG, "Loaded client certificate " + Credentials
                            .USER_CERTIFICATE + client);
                    if (DBG) Slog.d(TAG, "It " + (mNeedsSoftwareKeystore ? "needs" :
                            "does not need" ) + " software key store");
                } else {
                    Slog.d(TAG, "could not generate certificate");
                }
            } else {
                Slog.e(TAG, "Could not load client certificate " + Credentials
                        .USER_CERTIFICATE + client);
                mNeedsSoftwareKeystore = true;
            }

        } catch(CertificateException e) {
            Slog.e(TAG, "Could not read certificates");
            mCaCert = null;
            mClientCertificate = null;
        }
        */

        return false;
    }

    void handleSSIDStateChange(int netId, boolean enabled) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null && config.selfAdded && !enabled) {
            loge("SSID temp disabled for  " + config.configKey() +
                    " had autoJoinstatus=" + Integer.toString(config.autoJoinStatus)
                    + " self added " + config.selfAdded + " ephemeral " + config.ephemeral);
            if (config.selfAdded) {
                disableNetwork(config.networkId, WifiConfiguration.DISABLED_AUTH_FAILURE);
                config.autoJoinStatus = WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE;
            }
        }
    }

    boolean installKeys(WifiEnterpriseConfig config, String name) {
        boolean ret = true;
        String privKeyName = Credentials.USER_PRIVATE_KEY + name;
        String userCertName = Credentials.USER_CERTIFICATE + name;
        String caCertName = Credentials.CA_CERTIFICATE + name;
        if (config.getClientCertificate() != null) {
            byte[] privKeyData = config.getClientPrivateKey().getEncoded();
            if (isHardwareBackedKey(config.getClientPrivateKey())) {
                // Hardware backed key store is secure enough to store keys un-encrypted, this
                // removes the need for user to punch a PIN to get access to these keys
                if (DBG) Log.d(TAG, "importing keys " + name + " in hardware backed store");
                ret = mKeyStore.importKey(privKeyName, privKeyData, android.os.Process.WIFI_UID,
                        KeyStore.FLAG_NONE);
            } else {
                // Software backed key store is NOT secure enough to store keys un-encrypted.
                // Save keys encrypted so they are protected with user's PIN. User will
                // have to unlock phone before being able to use these keys and connect to
                // networks.
                if (DBG) Log.d(TAG, "importing keys " + name + " in software backed store");
                ret = mKeyStore.importKey(privKeyName, privKeyData, Process.WIFI_UID,
                        KeyStore.FLAG_ENCRYPTED);
            }
            if (ret == false) {
                return ret;
            }

            ret = putCertInKeyStore(userCertName, config.getClientCertificate());
            if (ret == false) {
                // Remove private key installed
                mKeyStore.delKey(privKeyName, Process.WIFI_UID);
                return ret;
            }
        }

        if (config.getCaCertificate() != null) {
            ret = putCertInKeyStore(caCertName, config.getCaCertificate());
            if (ret == false) {
                if (config.getClientCertificate() != null) {
                    // Remove client key+cert
                    mKeyStore.delKey(privKeyName, Process.WIFI_UID);
                    mKeyStore.delete(userCertName, Process.WIFI_UID);
                }
                return ret;
            }
        }

        // Set alias names
        if (config.getClientCertificate() != null) {
            config.setClientCertificateAlias(name);
            config.resetClientKeyEntry();
        }

        if (config.getCaCertificate() != null) {
            config.setCaCertificateAlias(name);
            config.resetCaCertificate();
        }

        return ret;
    }

    private boolean putCertInKeyStore(String name, Certificate cert) {
        try {
            byte[] certData = Credentials.convertToPem(cert);
            if (DBG) Log.d(TAG, "putting certificate " + name + " in keystore");
            return mKeyStore.put(name, certData, Process.WIFI_UID, KeyStore.FLAG_NONE);

        } catch (IOException e1) {
            return false;
        } catch (CertificateException e2) {
            return false;
        }
    }

    void removeKeys(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (DBG) Log.d(TAG, "removing client private key and user cert");
            mKeyStore.delKey(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
            mKeyStore.delete(Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
        }

        String ca = config.getCaCertificateAlias();
        // a valid ca certificate is configured
        if (!TextUtils.isEmpty(ca)) {
            if (DBG) Log.d(TAG, "removing CA cert");
            mKeyStore.delete(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
        }
    }


    /** Migrates the old style TLS config to the new config style. This should only be used
     * when restoring an old wpa_supplicant.conf or upgrading from a previous
     * platform version.
     * @return true if the config was updated
     * @hide
     */
    boolean migrateOldEapTlsNative(WifiEnterpriseConfig config, int netId) {
        String oldPrivateKey = mWifiNative.getNetworkVariable(netId, OLD_PRIVATE_KEY_NAME);
        /*
         * If the old configuration value is not present, then there is nothing
         * to do.
         */
        if (TextUtils.isEmpty(oldPrivateKey)) {
            return false;
        } else {
            // Also ignore it if it's empty quotes.
            oldPrivateKey = removeDoubleQuotes(oldPrivateKey);
            if (TextUtils.isEmpty(oldPrivateKey)) {
                return false;
            }
        }

        config.setFieldValue(WifiEnterpriseConfig.ENGINE_KEY, WifiEnterpriseConfig.ENGINE_ENABLE);
        config.setFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY,
                WifiEnterpriseConfig.ENGINE_ID_KEYSTORE);

        /*
        * The old key started with the keystore:// URI prefix, but we don't
        * need that anymore. Trim it off if it exists.
        */
        final String keyName;
        if (oldPrivateKey.startsWith(WifiEnterpriseConfig.KEYSTORE_URI)) {
            keyName = new String(
                    oldPrivateKey.substring(WifiEnterpriseConfig.KEYSTORE_URI.length()));
        } else {
            keyName = oldPrivateKey;
        }
        config.setFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, keyName);

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.ENGINE_KEY,
                config.getFieldValue(WifiEnterpriseConfig.ENGINE_KEY, ""));

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.ENGINE_ID_KEY,
                config.getFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY, ""));

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY,
                config.getFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, ""));

        // Remove old private_key string so we don't run this again.
        mWifiNative.setNetworkVariable(netId, OLD_PRIVATE_KEY_NAME, EMPTY_VALUE);

        return true;
    }

    /** Migrate certs from global pool to wifi UID if not already done */
    void migrateCerts(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (!mKeyStore.contains(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID)) {
                mKeyStore.duplicate(Credentials.USER_PRIVATE_KEY + client, -1,
                        Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
                mKeyStore.duplicate(Credentials.USER_CERTIFICATE + client, -1,
                        Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
            }
        }

        String ca = config.getCaCertificateAlias();
        // a valid ca certificate is configured
        if (!TextUtils.isEmpty(ca)) {
            if (!mKeyStore.contains(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID)) {
                mKeyStore.duplicate(Credentials.CA_CERTIFICATE + ca, -1,
                        Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
            }
        }
    }

}

