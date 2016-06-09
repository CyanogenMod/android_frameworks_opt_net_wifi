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

import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;
import static android.system.OsConstants.ARPHRD_ETHER;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.dhcp.DhcpClient;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConnectionStatistics;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * Wi-Fi now supports three modes of operation: Client, SoftAp and p2p
 * In the current implementation, we support concurrent wifi p2p and wifi operation.
 * The WifiStateMachine handles SoftAp and Client operations while WifiP2pService
 * handles p2p operation.
 *
 * @hide
 */
public class SoftApStateMachine extends StateMachine {

    private static boolean DBG = false;
    private static final String TAG = "SoftApStateMachine";

    private WifiMonitor mWifiMonitor;
    private WifiNative mWifiNative;
    private WifiConfigManager mWifiConfigManager;
    private INetworkManagementService mNwService;
    private ConnectivityManager mCm;
    private BaseWifiLogger mWifiLogger;
    private WifiApConfigStore mWifiApConfigStore;
    private final Clock mClock = new Clock();
    private final WifiCountryCode mCountryCode;

    private String mInterfaceName = "softap0";
    private int mSoftApChannel = 0;
    /* Tethering interface could be separate from wlan interface */
    private String mTetherInterfaceName;
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    private WifiStateMachine mWifiStateMachine  = null;

    /**
     * Tether state change notification time out
     */
    private static final int TETHER_NOTIFICATION_TIME_OUT_MSECS = 5000;

    /* Tracks sequence number on a tether notification time out */
    private int mTetherToken = 0;

    /*Wakelock held during wifi start/stop and driver load/unload */
    private PowerManager.WakeLock mWakeLock;

    private Context mContext;
    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;
    /* Start the soft ap  */
    static final int CMD_START_AP                                 = BASE + 21;
    /* Indicates soft ap start failed */
    static final int CMD_START_AP_FAILURE                         = BASE + 22;
    /* Stop the soft ap */
    static final int CMD_STOP_AP                                  = BASE + 23;
    /* Soft access point teardown is completed. */
    static final int CMD_AP_STOPPED                               = BASE + 24;

    public static final int CMD_BOOT_COMPLETED                    = BASE + 134;


    /**
     * One of  {@link WifiManager#WIFI_AP_STATE_DISABLED},
     * {@link WifiManager#WIFI_AP_STATE_DISABLING},
     * {@link WifiManager#WIFI_AP_STATE_ENABLED},
     * {@link WifiManager#WIFI_AP_STATE_ENABLING},
     * {@link WifiManager#WIFI_AP_STATE_FAILED}
     */
    private final AtomicInteger mWifiApState
            = new AtomicInteger(WIFI_AP_STATE_DISABLED);
    private final IBatteryStats mBatteryStats;

    /* Temporary initial state */
    private State mInitialState = new InitialState();
    /* Soft ap state */
    private State mSoftApState = new SoftApState();

    private FrameworkFacade mFacade;
    private final BackupManagerProxy mBackupManagerProxy;


    public SoftApStateMachine(Context context, WifiStateMachine wifiStateMachine,
                            FrameworkFacade facade,String intf,
                            WifiConfigManager configManager,
                            WifiMonitor wifiMonitor,
                            BackupManagerProxy backupManagerProxy,
                            INetworkManagementService NwService,
                            IBatteryStats BatteryStats,
                            WifiCountryCode countryCode) {
        super("SoftApStateMachine");

        mContext = context;
        mWifiStateMachine = wifiStateMachine;
        mFacade = facade;
        mWifiNative = WifiNative.getWlanNativeInterface();
        mWifiNative.initContext(mContext);
        mInterfaceName = intf;
        mBackupManagerProxy = backupManagerProxy;
        mNwService = NwService;
        mBatteryStats = BatteryStats;
        mWifiConfigManager = configManager;
        mWifiMonitor = wifiMonitor;
        mCountryCode = countryCode;

        addState(mInitialState);
            addState(mSoftApState, mInitialState);
        setInitialState(mInitialState);
        start();

    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
        } else {
            DBG = false;
        }
    }

    public void setSoftApInterfaceName(String iface) {
        mInterfaceName = iface;
    }

    public void setSoftApChannel(int channel) {
        mSoftApChannel = channel;
    }


   /*  Leverage from WiFiStateMachine */
    public void setHostApRunning(WifiConfiguration wifiConfig, boolean enable) {
        if (enable) {
            sendMessage(CMD_START_AP, wifiConfig);
        } else {
            sendMessage(CMD_STOP_AP);
        }
    }

    public void setWifiApConfiguration(WifiConfiguration config) {
        mWifiApConfigStore.setApConfiguration(config);
    }

   /*  Leverage from WiFiStateMachine */
    public WifiConfiguration syncGetWifiApConfiguration() {
        return mWifiApConfigStore.getApConfiguration();
    }

   /*  Leverage from WiFiStateMachine */
    public int syncGetWifiApState() {
        return mWifiApState.get();
    }

   /*  Leverage from WiFiStateMachine */
    public String syncGetWifiApStateByName() {
        switch (mWifiApState.get()) {
            case WIFI_AP_STATE_DISABLING:
                return "disabling";
            case WIFI_AP_STATE_DISABLED:
                return "disabled";
            case WIFI_AP_STATE_ENABLING:
                return "enabling";
            case WIFI_AP_STATE_ENABLED:
                return "enabled";
            case WIFI_AP_STATE_FAILED:
                return "failed";
            default:
                return "[invalid state]";
        }
    }

   /*  Leverage from WiFiStateMachine */
    private void setWifiApState(int wifiApState, int reason) {
        final int previousWifiApState = mWifiApState.get();

        try {
            if (wifiApState == WIFI_AP_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiApState == WIFI_AP_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to note battery stats in wifi");
        }

        // Update state
        mWifiApState.set(wifiApState);

        if (DBG) Log.d(TAG,"setWifiApState: " + syncGetWifiApStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiApState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousWifiApState);
        if (wifiApState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }

        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

   /*  Leverage from WiFiStateMachine */
    private boolean setupDriverForSoftAp() {
        if (!mWifiNative.loadDriver()) {
            Log.e(TAG, "Failed to load driver for softap");
            return false;
        }
        if (mWifiStateMachine != null) {
            int wifiState = mWifiStateMachine.syncGetWifiState();
            if ((wifiState == WifiManager.WIFI_STATE_ENABLING) ||
                (wifiState == WifiManager.WIFI_STATE_ENABLED)) {
                Log.d(TAG,"Wifi is in enabled state skip firmware reload");
                return true;
            }
        }

        try {
            mNwService.wifiFirmwareReload(mInterfaceName, "AP");
            if (DBG) Log.d(TAG, "Firmware reloaded in AP mode");
        } catch (Exception e) {
            Log.e(TAG, "Failed to reload AP firmware " + e);
        }
        if (!mWifiNative.startHal()) {
            Log.e(TAG, "Failed to start HAL");
        }
        return true;
    }

   /*  Leverage from WiFiStateMachine */
    private void checkAndSetConnectivityInstance() {
        if (mCm == null) {
            mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
    }

     /*
      *  SoftApStateMAchine states
      *      (InitialState)
      *           |
      *           |
      *           V
      *      (SoftApState)
      *
      * InitialState : By default control sits in this state after
      *                SoftApStateMachine is getting initialized.
      *                It unload wlan driver if it is loaded.
      *                On request turn on softap, it movies to
      *                SoftApState.
      * SoftApState  : Once driver and firmware successfully loaded
      *                it control sits in this state.
      *                On request to stop softap, it movies back to
      *                InitialState.
      *
      */

   /*  Leverage from WiFiStateMachine */
    class InitialState extends State {
        @Override
        public void enter() {
            boolean skipUnload = false;
            if (mWifiStateMachine != null) {
                int wifiState = mWifiStateMachine.syncGetWifiState();
                if ((wifiState ==  WifiManager.WIFI_STATE_ENABLING) ||
                    (wifiState == WifiManager.WIFI_STATE_ENABLED)) {
                     Log.d(TAG, "Avoid unload driver, WIFI_STATE is enabled/enabling");
                     skipUnload = true;
                }
            }
            if (!skipUnload) {
                mWifiNative.stopHal();
                mWifiNative.unloadDriver();
            }
            if (mWifiApConfigStore == null) {
                mWifiApConfigStore =
                        mFacade.makeApConfigStore(mContext, mBackupManagerProxy);
            }
        }
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_START_AP:
                    if (setupDriverForSoftAp()) {
                        transitionTo(mSoftApState);
                    } else {
                        setWifiApState(WIFI_AP_STATE_FAILED,
                                WifiManager.SAP_START_FAILURE_GENERAL);
                        /**
                         * Transition to InitialState (current state) to reset the
                         * driver/HAL back to the initial state.
                         */
                        transitionTo(mInitialState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

   /*  Leverage from WiFiStateMachine */
    class SoftApState extends State {
        private SoftApManager mSoftApManager;

        private class SoftApListener implements SoftApManager.Listener {
            @Override
            public void onStateChanged(int state, int reason) {
                if (state == WIFI_AP_STATE_DISABLED) {
                    sendMessage(CMD_AP_STOPPED);
                } else if (state == WIFI_AP_STATE_FAILED) {
                    sendMessage(CMD_START_AP_FAILURE);
                }

                setWifiApState(state, reason);
            }
        }

        @Override
        public void enter() {
            final Message message = getCurrentMessage();
            if (message.what == CMD_START_AP) {
                WifiConfiguration config = (WifiConfiguration) message.obj;

                if (config == null) {
                    /**
                     * Configuration not provided in the command, fallback to use the current
                     * configuration.
                     */
                    config = mWifiApConfigStore.getApConfiguration();
                } else {
                    /* Update AP configuration. */
                    mWifiApConfigStore.setApConfiguration(config);
                }

                checkAndSetConnectivityInstance();
                mSoftApManager = mFacade.makeSoftApManager(
                        mContext, getHandler().getLooper(), mWifiNative, mNwService,
                        mCm, mCountryCode.getCurrentCountryCode(),
                        mWifiApConfigStore.getAllowed2GChannel(),
                        new SoftApListener());
                if (mSoftApChannel != 0) {
                    mSoftApManager.setSapChannel(mSoftApChannel);
                }
                mSoftApManager.setSapInterfaceName(mInterfaceName);
                mSoftApManager.start(config);
            } else {
                throw new RuntimeException("Illegal transition to SoftApState: " + message);
            }
        }

        @Override
        public void exit() {
            mSoftApManager = null;
        }

        @Override
        public boolean processMessage(Message message) {

            switch(message.what) {
                case CMD_START_AP:
                    /* Ignore start command when it is starting/started. */
                    break;
                case CMD_STOP_AP:
                    mSoftApManager.stop();
                    break;
                case CMD_START_AP_FAILURE:
                    transitionTo(mInitialState);
                    break;
                case CMD_AP_STOPPED:
                    transitionTo(mInitialState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    /**
     * arg2 on the source message has a unique id that needs to be retained in replies
     * to match the request
     * <p>see WifiManager for details
     */
    private Message obtainMessageWithWhatAndArg2(Message srcMsg, int what) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg2 = srcMsg.arg2;
        return msg;
    }
}
