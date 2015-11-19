package com.android.server.wifi.hotspot2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Network;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiStateMachine;
import com.android.server.wifi.configparse.ConfigBuilder;
import com.android.server.wifi.hotspot2.osu.OSUInfo;
import com.android.server.wifi.hotspot2.osu.OSUManager;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

public class WifiNetworkAdapter {
    private final Context mContext;
    private final OSUManager mOSUManager;
    private final WifiStateMachine mWifiStateMachine;
    private WifiConfigStore mWifiConfigStore;
    private OSUInfo mCurrentOSUInfo;

    public WifiNetworkAdapter(Context context, OSUManager osuManager,
                              WifiStateMachine wifiStateMachine, WifiConfigStore wifiConfigStore) {
        mOSUManager = osuManager;
        mContext = context;
        mWifiStateMachine = wifiStateMachine;
        mWifiConfigStore = wifiConfigStore;
    }

    public void initialize() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // This is a current mock implementation that blindly starts an OSU flow as soon
                // as an SSID associated with an OSU is connected to.

                if (OSUManager.R2_TEST) {
                    OSUInfo osuInfo = getCurrentOSU();

                    if (!compareOSUs(mCurrentOSUInfo, osuInfo)) {
                        mCurrentOSUInfo = osuInfo;
                        Network network = osuInfo != null ? getCurrentNetwork() : null;
                        Log.d(OSUManager.TAG, "OSU changed to " + osuInfo + ", network " + network);
                        try {
                            mOSUManager.initiateProvisioning(osuInfo, network);
                        } catch (Throwable t) {
                            Log.e(OSUManager.TAG, "Failed to initiate provisioning on " +
                                    osuInfo + ": " + t, t);
                        }
                    }
                }
            }
        }, filter);
    }

    public Network getCurrentNetwork() {
        return mWifiStateMachine.getCurrentNetwork();
    }

    public WifiInfo getConnectionInfo() {
        return mWifiStateMachine.getWifiInfo();
    }

    public WifiConfiguration getWifiConfig(HomeSP homeSP) {
        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
        for (WifiConfiguration current : networks) {
            if (current.isPasspoint() && current.FQDN.equals(homeSP.getFQDN())) {
                return current;
            }
        }
        return null;
    }

    public void updateNetwork(HomeSP homeSP, X509Certificate caCert,
                              List<X509Certificate> clientCerts, PrivateKey privateKey)
            throws IOException, GeneralSecurityException {

        WifiConfiguration config = getWifiConfig(homeSP);
        if (config == null) {
            throw new IOException("Failed to find matching network config");
        }
        Log.d(OSUManager.TAG, "Found matching config " + config.networkId + ", updating");

        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;
        WifiConfiguration newConfig = ConfigBuilder.buildConfig(homeSP,
                caCert != null ? caCert : enterpriseConfig.getCaCertificate(),
                clientCerts, privateKey);
        newConfig.networkId = config.networkId;

        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wifiManager.save(newConfig, null);
        wifiManager.saveConfiguration();
    }

    public void detachOSUNetwork(Network osuNetwork, int newNwkId) {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wifiManager.disableNetwork(osuNetwork.netId);
        if (newNwkId != WifiConfiguration.INVALID_NETWORK_ID) {
            wifiManager.enableNetwork(newNwkId, true);
        }
    }

    /**
     * Set the re-authentication hold off time for the current network
     * @param holdoff hold off time in milliseconds
     * @param ess set if the hold off pertains to an ESS rather than a BSS
     */
    public void setHoldoffTime(long holdoff, boolean ess) {

    }

    private OSUInfo getCurrentOSU() {
        WifiConfiguration active = getActiveNetwork();
        mOSUManager.setActiveNetwork(active);
        if (active == null) {
            return null;
        }

        Collection<OSUInfo> osus = mOSUManager.getAvailableOSUs();

        String ssid = Utils.unquote(active.SSID);
        String bssidString = mWifiStateMachine.getWifiInfo().getBSSID();
        long bssid = bssidString != null ? Utils.parseMac(bssidString) : -1;

        Log.d(OSUManager.TAG, String.format("Connection event for %012x '%s', OSUs: %s",
                bssid, ssid, osus));

        for (OSUInfo osu : osus) {
            if (osu.getSSID().equals(ssid)) {
                //if (osu.getBSSID() == bssid)  // ??? Can BSSID be bound to AP somehow?
                Log.d(OSUManager.TAG, "OSU match: " + osu);
                return osu;
            }
        }
        return null;
    }

    private WifiConfiguration getActiveNetwork() {
        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
        for (WifiConfiguration wifiConfiguration : networks) {
            if (wifiConfiguration.status == WifiConfiguration.Status.CURRENT) {
                return wifiConfiguration;
            }
        }
        return null;
    }

    private static boolean compareOSUs(OSUInfo o1, OSUInfo o2) {
        if (o1 == null) {
            return o2 == null;
        }
        else {
            return o2 != null && o1.getBSSID() == o2.getBSSID() && o1.getSSID().equals(o2.getSSID());
        }
    }

    /*
    public void associate(String ssid, long bssid, String nai) {
        Log.d("HS2OSU", "Requesting association with '" + ssid + "'");
        NetworkRequest.Builder nwkRequestBuilder = new NetworkRequest.Builder();
        nwkRequestBuilder = nwkRequestBuilder.
                clearCapabilities().
                addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
                // setNetworkSpecifier(ssid);
        // 07-29 19:13:33.211 D/ConnectivityService( 1121): listenForNetwork for Listen from uid/pid:1000/1121 for NetworkRequest [ id=6, legacyType=-1, [ Transports: WIFI Specifier: <third>] ]
        ConnectivityManager connectivityManager = (ConnectivityManager) mContext.
                getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivityManager.registerNetworkCallback(nwkRequestBuilder.build(), this);

        WifiManager wifiMgr = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = ssid;
        configuration.BSSID = Utils.macToString(bssid);
        int id = wifiMgr.addNetwork(configuration);

        wifiMgr.enableNetwork(id, true);

        synchronized (mNetworks) {
            mNetworks.put(bssid, new NetworkInfo(id, bssid, ssid));
        }
    }

    public void dropNetwork(long bssid) {
        NetworkInfo networkInfo;
        synchronized (mNetworks) {
            networkInfo = mNetworks.remove(bssid);
            if (networkInfo == null) {
                return;
            }
        }

        WifiManager wifiMgr = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wifiMgr.disableNetwork(networkInfo.getID());
        ConnectivityManager connectivityManager = (ConnectivityManager) mContext.
                getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivityManager.unregisterNetworkCallback(this);
    }
    */
}
