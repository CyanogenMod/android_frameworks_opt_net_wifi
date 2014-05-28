/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.wifi.passpoint;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.passpoint.WifiPasspointCredential;
import android.net.wifi.passpoint.WifiPasspointInfo;
import android.net.wifi.passpoint.WifiPasspointPolicy;
import android.net.wifi.passpoint.WifiPasspointOsuProvider;
import android.net.wifi.passpoint.WifiPasspointManager;
import android.net.wifi.passpoint.WifiPasspointDmTree;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.security.KeyStore;
import android.security.Credentials;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.net.wifi.IWifiManager;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiStateMachine;
import com.android.server.wifi.WifiServiceImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Queue;

/**
 * TODO: doc
 */
public class WifiPasspointStateMachine extends StateMachine {
    private static final String TAG = "WifiPasspointStateMachine";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private static final int BASE = Protocol.BASE_WIFI_PASSPOINT_SERVICE;

    protected static final int CMD_ENABLE_PASSPOINT = BASE + 1;
    protected static final int CMD_DISABLE_PASSPOINT = BASE + 2;
    protected static final int CMD_GAS_QUERY_TIMEOUT = BASE + 3;
    protected static final int CMD_START_OSU = BASE + 4;
    protected static final int CMD_START_REMEDIATION = BASE + 5;
    protected static final int CMD_START_POLICY_UPDATE = BASE + 6;
    protected static final int CMD_LAUNCH_BROWSER = BASE + 7;
    protected static final int CMD_ENROLL_CERTIFICATE = BASE + 8;
    protected static final int CMD_OSU_DONE = BASE + 9;
    protected static final int CMD_REMEDIATION_DONE = BASE + 10;
    protected static final int CMD_POLICY_UPDATE_DONE = BASE + 11;
    protected static final int CMD_SIM_PROVISION_DONE = BASE + 12;
    protected static final int CMD_BROWSER_REDIRECTED = BASE + 13;
    protected static final int CMD_ENROLLMENT_DONE = BASE + 14;
    private static final int CMD_WIFI_CONNECTED = BASE + 15;
    private static final int CMD_WIFI_DISCONNECTED = BASE + 16;

    private static final int ANQP_TIMEOUT_MS = 5000;
    public static final String ACTION_NETWORK_POLICY_POLL = "com.android.intent.action.PASSPOINT_POLICY_POLL";
    public static final String ACTION_NETWORK_REMEDIATION_POLL = "com.android.intent.action.PASSPOINT_REMEDIATION_POLL";

    private static final String DEFAULT_LANGUAGE_CODE = "zxx";

    private String mInterface;
    private WifiNative mWifiNative;
    private int mState = WifiPasspointManager.PASSPOINT_STATE_UNKNOWN;
    private Object mStateLock = new Object();
    private Object mPolicyLock = new Object();
    private ArrayList<WifiPasspointCredential> mCredentialList = new ArrayList<WifiPasspointCredential>();
    private TreeSet<WifiPasspointPolicy> mNetworkPolicy = new TreeSet<WifiPasspointPolicy>();

    private AsyncChannel mReplyChannel = new AsyncChannel();

    private Context mContext;
    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;

    private WifiServiceImpl mWifiServiceImpl;

    private String mLanguageCode; // TODO: update this when language changes

    private Queue<Message> mAnqpRequestQueue = new LinkedList<Message>();
    private Message mCurrentAnqpRequest;
    private boolean mIsAnqpOngoing = false;
    private int mAnqpTimeoutToken = 0;
    private WifiPasspointDmTree mWifiTree;
    private WifiPasspointDmTreeHelper mTreeHelper = new WifiPasspointDmTreeHelper();
    private WifiManager mWifiMgr;
    private TelephonyManager mTeleMgr;
    private AlarmManager mAlarmManager;
    private WifiPasspointClient.SoapClient mSoapClient;
    private WifiPasspointClient.DmClient mDmClient;
    private WifiPasspointPolicy mCurrentUsedPolicy;
    private String mMcc;
    private String mMnc;
    private ServerSocket mServerSocketForOSU;
    private ServerSocket mServerSocketForREM;
    private String mUpdateMethod;
    private PendingIntent mPolicyPollIntent;

    private DefaultState mDefaultState = new DefaultState();
    private DisabledState mDisabledState = new DisabledState();
    private EnabledState mEnabledState = new EnabledState();
    private DiscoveryState mDiscoveryState = new DiscoveryState();
    private ProvisionState mProvisionState = new ProvisionState();
    private AccessState mAccessState = new AccessState();
    private final String[] mIANA_EAPmethod = {
            "Reserved",//0
            "Identity",// 1
            "Notification",// 2
            "Legacy_Nak",// 3
            "MD5-Challenge",// 4
            "OTP",// 5
            "GTC",// 6
            "Allocated",// 7
            "Allocated",// 8
            "RSA_Public_Key_Authentication",// 9
            "DSS_Unilateral",// 10
            "KEA",// 11
            "KEA-VALIDATE",// 12
            "TLS",// 13
            "AXENT",// 14
            "RSA_Security_SecurID_EAP",// 15
            "Arcot_Systems_EAP",// 16
            "EAP-Cisco_Wireless",// 17
            "SIM",// 18
            "SRP-SHA1",// 19
            "Unassigned",// 20
            "TTLS",// 21
            "Remote_Access_Service",//22
            "AKA",//23
            "3Com_Wireless",//24
            "PEAP",//25
            "MS-EAP-Authentication",//26
            "MAKE",//27
            "CRYPTOCard",//28
            "MSCHAPv2",//29
            "DynamID",//30
            "Rob_EAP",//31
            "Protected_One-Time_Password",//32
            "MS-Authentication-TLV",//33
            "SentriNET",//34
            "EAP-Actiontec_Wireless",//35
            "Cogent_Systems_Biometrics_Authentication_EAP",//36
            "AirFortress_EAP",//37
            "EAP-HTTP_Digest",//38
            "SecureSuite_EAP",//39
            "DeviceConnect_EAP",//40
            "SPEKE",//41
            "MOBAC",//42
            "EAP-FAST",//43
            "ZLXEAP",//44
            "Link",//45
            "PAX",//46
            "PSK",//47
            "SAKE",//48
            "IKEv2",//49
            "AKA2",//50
            "GPSK",//51
            "pwd",//52
            "EKE_Version_1",//53
            "Unassigned"//54
    };

    public WifiPasspointStateMachine(Context context, String iface) {
        super(TAG);

        mContext = context;
        mInterface = iface;
        mWifiNative = new WifiNative(mInterface);

        mLanguageCode = Locale.getDefault().getISO3Language();
        logd("mLanguageCode=" + mLanguageCode);

        setupNetworkReceiver();

        mWifiMgr = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mTeleMgr = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        mWifiTree = new WifiPasspointDmTree();

        mDmClient = new WifiPasspointDmClient();
        mDmClient.init(WifiPasspointStateMachine.this);
        mDmClient.setWifiTree(mWifiTree);

        mSoapClient = new WifiPasspointSoapClient(mDmClient);
        mSoapClient.init(WifiPasspointStateMachine.this);
        mSoapClient.setWifiTree(mWifiTree);

        addState(mDefaultState);
        addState(mDisabledState, mDefaultState);
        addState(mEnabledState, mDefaultState);
        addState(mDiscoveryState, mEnabledState);
        addState(mProvisionState, mEnabledState);
        addState(mAccessState, mEnabledState);

        setInitialState(mDisabledState);

        // STOPSHIP: temp solution before supplicant manager
        IBinder s = ServiceManager.getService(Context.WIFI_SERVICE);
        mWifiServiceImpl = (WifiServiceImpl)IWifiManager.Stub.asInterface(s);
        mWifiServiceImpl.getMonitor().setStateMachine2(WifiPasspointStateMachine.this);

        setLogRecSize(1000);
        setLogOnlyTransitions(false);
        if (DBG)
            setDbg(true);
    }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (VDBG)
                logd(getName() + message.toString());
            switch (message.what) {
                case CMD_ENABLE_PASSPOINT:
                    transitionTo(mDiscoveryState);
                    break;
                case CMD_DISABLE_PASSPOINT:
                    transitionTo(mDisabledState);
                    break;
                case CMD_GAS_QUERY_TIMEOUT:
                    break;
                case WifiPasspointManager.REQUEST_ANQP_INFO:
                    replyToMessage(message, WifiPasspointManager.REQUEST_ANQP_INFO_FAILED,
                            WifiPasspointManager.BUSY);
                    break;
                default:
                    loge("Unhandled message " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DisabledState extends State {

        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = WifiPasspointManager.PASSPOINT_STATE_DISABLED;
            }
        }
    }

    class EnabledState extends State {

        @Override
        public boolean processMessage(Message message) {
            if (VDBG)
                logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.GAS_QUERY_START_EVENT:
                    logd("got GAS_QUERY_START_EVENT");
                    break;

                case WifiMonitor.GAS_QUERY_DONE_EVENT:
                    mAnqpTimeoutToken++;
                    String bssid = (String) message.obj;
                    int success = message.arg1;
                    logd("GAS_QUERY_DONE_EVENT bssid=" + bssid + " success=" + success);
                    finishAnqpFetch(bssid);
                    break;

                case WifiMonitor.RX_HS20_ANQP_ICON_EVENT:
                    logd("RX_HS20_ANQP_ICON_EVENT~~");
                    break;

                case CMD_GAS_QUERY_TIMEOUT:
                    if (message.arg1 == mAnqpTimeoutToken) {
                        // TODO: handle timeout
                        if (VDBG)
                            logd("ANQP fetch timeout");
                        finishAnqpFetch(null);
                    }
                    break;

                case WifiPasspointManager.REQUEST_ANQP_INFO:
                    // make a copy as the original message will be recycled
                    Message msg = new Message();
                    msg.copyFrom(message);
                    if (mIsAnqpOngoing) {
                        if (VDBG)
                            logd("new anqp request buffered");
                        logd("added msg.what = " + message.what);
                        mAnqpRequestQueue.add(msg);
                    } else {
                        if (VDBG)
                            logd("new anqp request started");
                        startAnqpFetch(msg);
                    }
                    break;

                case CMD_WIFI_DISCONNECTED:
                    transitionTo(mDiscoveryState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DiscoveryState extends State {
        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = WifiPasspointManager.PASSPOINT_STATE_DISCOVERY;
            }
            createCredentialList(mWifiTree);
        }

        @Override
        public boolean processMessage(Message message) {
            if (VDBG)
                logd(getName() + message.toString());
            switch (message.what) {
                case CMD_WIFI_CONNECTED:
                    if (mCurrentUsedPolicy != null) {
                        transitionTo(mAccessState);
                    }
                    break;
                case CMD_START_OSU:
                    deferMessage(message);
                    transitionTo(mProvisionState);
                    break;
                case CMD_START_REMEDIATION:
                    deferMessage(message);
                    break;
                case CMD_START_POLICY_UPDATE:
                    deferMessage(message);
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ProvisionState extends State {
        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = WifiPasspointManager.PASSPOINT_STATE_PROVISION;
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (VDBG)
                logd(getName() + message.toString());
            switch (message.what) {
                case CMD_START_OSU:
                    String ssid = null;//TODO: get ssid form app layer
                    ConnectToPasspoint(buildPolicy(ssid, ssid, null, null,
                            WifiPasspointPolicy.UNRESTRICTED, false));
                    break;
                case CMD_WIFI_CONNECTED:
                    String serverurl = null;//TODO: get osu server url from app layer
                    String method = null;//TODO: get osu server url from app layer
                    startSubscriptionProvision(serverurl, method);
                    break;
                case CMD_OSU_DONE:
                    int result = message.arg1;
                    WifiPasspointDmTree tree = (WifiPasspointDmTree) message.obj;
                    handleProvisionDone(result, tree);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private void startSubscriptionProvision(String url, String updateMethod) {

            if (url == null || updateMethod == null) {
                return;
            }
            mUpdateMethod = updateMethod;
            WifiPasspointClient.BaseClient client = null;
            if (WifiPasspointManager.PROTOCOL_SOAP.equals(updateMethod)) {
                client = mSoapClient;
            } else if (WifiPasspointManager.PROTOCOL_DM.equals(updateMethod)) {
                client = mDmClient;
            } else {
                Log.e(TAG, "STOP, updateMethod is not mentioned");
                return;
            }

            try {
                URL osuURL = new URL(url);
                String fqdn = osuURL.getHost();
                client.init(WifiPasspointStateMachine.this);
                client.setAuthenticationElement(new WifiPasspointClient.AuthenticationElement(fqdn,
                        null, null, null));
                // enable cookie
                CookieManager cookieMan = new CookieManager(null, null);
                CookieHandler.setDefault(cookieMan);

                startHttpServer(HS_OSU_LISTEN_PORT);
                client.startSubscriptionProvision(url);
            } catch (Exception e) {
                Log.d(TAG, "startSubscriptionProvision fail:" + e);
            }
        }
    }

    class AccessState extends State {
        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = WifiPasspointManager.PASSPOINT_STATE_ACCESS;
            }
            updatePolicyUpdateAlarm();
        }

        @Override
        public boolean processMessage(Message message) {
            if (VDBG)
                logd(getName() + message.toString());
            switch (message.what) {
                case CMD_START_REMEDIATION:
                    String serverurl = null;//TODO: get osu server url from app layer
                    String method = null;//TODO: get osu server url from app layer
                    startRemediation(serverurl, method);
                    break;
                case CMD_START_POLICY_UPDATE:
                    startPolicyUpdate();
                    break;
                case CMD_REMEDIATION_DONE:
                case CMD_POLICY_UPDATE_DONE:
                case CMD_SIM_PROVISION_DONE:
                    int result = message.arg1;
                    WifiPasspointDmTree tree = (WifiPasspointDmTree) message.obj;
                    handleProvisionDone(result, tree);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private void startRemediation(String url, String updateMethod) {
            Log.d(TAG, "startRemediation");

            if (isSubscriptionUpdateRestricted()) {
                return;
            }

            if (mCurrentUsedPolicy != null && mCurrentUsedPolicy.getCredential() != null) {
                WifiPasspointCredential currentCredential = mCurrentUsedPolicy.getCredential();
                String method = currentCredential.getUpdateMethod();
                WifiPasspointClient.BaseClient client = null;

                if (method != null && !method.isEmpty()) {
                    Log.d(TAG,
                            "Subscription update method is not set in PPSMO, use method from WNM");
                    updateMethod = method;
                }
                mUpdateMethod = updateMethod;
                Log.d(TAG, "updateMethod in PPSMO is: " + updateMethod);
                if (WifiPasspointManager.PROTOCOL_SOAP.equals(updateMethod)) {
                    client = mSoapClient;
                } else if (WifiPasspointManager.PROTOCOL_DM.equals(updateMethod)) {
                    client = mDmClient;
                } else {
                    Log.d(TAG, "STOP, updateMethod is not mentioned");
                    return;
                }

                Log.d(TAG, "connecting to Reme server:" + updateMethod);

                WifiPasspointDmTree.CredentialInfo info = mTreeHelper.getCredentialInfo(mWifiTree,
                        mCurrentUsedPolicy.getCredential().getWifiSpFqdn(),
                        mCurrentUsedPolicy.getCredential().getCredName());
                if (info != null && info.subscriptionUpdate.URI != null) {
                    url = info.subscriptionUpdate.URI;
                }

                try {
                    URL remURL = new URL(url);
                    String fqdn = remURL.getHost();

                    client.setAuthenticationElement(new WifiPasspointClient.AuthenticationElement(fqdn,
                            null, null, null));
                    client.setWifiTree(mWifiTree);
                    // enable cookie
                    CookieManager cookieMan = new CookieManager(null, null);
                    CookieHandler.setDefault(cookieMan);

                    startHttpServer(HS_REM_LISTEN_PORT);
                    client.startRemediation(url, mCurrentUsedPolicy.getCredential());
                } catch (Exception e) {
                    Log.d(TAG, "startRemediation fail:" + e);
                }
            }
        }

        private void startPolicyUpdate() {
            Log.d(TAG, "startPolicyUpdate");
            if (isPolicyUpdateRestricted()) {
                return;
            }

            WifiPasspointClient.BaseClient client = null;
            WifiPasspointCredential currentCredential = mCurrentUsedPolicy.getCredential();
            String updateMethod = currentCredential.getPolicyUpdateMethod();

            if (updateMethod == null || updateMethod.isEmpty()) {
                Log.d(TAG, "Policy update method is not set in PPSMO, use mUpdateMethod");
                return;
            }
            mUpdateMethod = updateMethod;
            if (WifiPasspointManager.PROTOCOL_SOAP.equals(updateMethod)) {
                client = mSoapClient;
            } else if (WifiPasspointManager.PROTOCOL_DM.equals(updateMethod)) {
                client = mDmClient;
            } else {
                Log.d(TAG, "STOP, updateMethod is not mentioned");
                return;
            }

            try {
                String polUrl = currentCredential.getPolicyUpdateUri();
                URL policyUpdateUrl = new URL(polUrl);
                String fqdn = policyUpdateUrl.getHost();

                if (mCurrentUsedPolicy != null) {
                    client.setAuthenticationElement(new WifiPasspointClient.AuthenticationElement(fqdn,
                            null, null, null));
                    client.setWifiTree(mWifiTree);
                    // enable cookie
                    CookieManager cookieMan = new CookieManager(null, null);
                    CookieHandler.setDefault(cookieMan);

                    // start policy provision
                    client.startPolicyProvision(polUrl, mCurrentUsedPolicy.getCredential());
                } else {
                    Log.d(TAG, "handleEventPolicyUpdateStart mCurrentUsedPolicy=null");
                }

            } catch (Exception e) {
                Log.d(TAG, "startPolicyUpdate fail:" + e);
            }
        }

        private boolean isRestricted(int currentNetwork, String restriction) {
            /*
             * 1."RoamingPartner" then the mobile device can update its
             * PerProviderSubscription MO, when associated to a roaming
             * partner's HS2.0 compliant hotspot or its Home SP's HS2.0
             * compliant hotspot. 2."Unrestricted" then the mobile device can
             * update its PerProviderSubscription MO when connect to any WLAN
             * connected to the public Internet. 3."HomeSP" then the mobile
             * device can only update its policy when it is connected to a
             * hotspot operated by its Home SP.
             */
            int restrictionVal = WifiPasspointPolicy.UNRESTRICTED;
            if (restriction.isEmpty()) {
                Log.d(TAG, "checkRestriction: return false due to restriction empty");
                return false;
            } else if (restriction.toUpperCase().trim()
                    .indexOf(new String("HomeSP").toUpperCase().trim()) != -1) {
                restrictionVal = WifiPasspointPolicy.HOME_SP;
            } else if (restriction.toUpperCase().trim()
                    .indexOf(new String("RoamingPartner").toUpperCase().trim()) != -1) {
                restrictionVal = WifiPasspointPolicy.ROAMING_PARTNER;
            } else if (restriction.toUpperCase().trim()
                    .indexOf(new String("Unrestricted").toUpperCase().trim()) != -1) {
                restrictionVal = WifiPasspointPolicy.UNRESTRICTED;
            }
            Log.d(TAG, "checkRestriction: cur[" + currentNetwork + "] <= res[" + restrictionVal
                    + "]");
            if (currentNetwork <= restrictionVal) {
                // HOME_SP =0, ROAMING_PARTNER =1, UNRESTRICTED =2
                return false;
            } else {
                return true;
            }
        }

        private boolean isSubscriptionUpdateRestricted() {
            if (mCurrentUsedPolicy == null) {
                // FOO Debug only!!!
                Log.d(TAG, "isSubscriptionUpdateRestricted: false");
                return false;

            }
            WifiPasspointCredential currentCredential = mCurrentUsedPolicy.getCredential();
            String restriction = currentCredential.getSubscriptionUpdateRestriction();
            if (restriction != null) {
                boolean result = isRestricted(mCurrentUsedPolicy.getRestriction(), restriction);
                Log.d(TAG, "isSubscriptionUpdateRestricted:[" + restriction + "]:" + result);
                return result;
            }
            return false;
        }

        private boolean isPolicyUpdateRestricted() {
            if (mCurrentUsedPolicy == null) {
                Log.d(TAG, "isPolicyUpdateRestricted: false");
                return false;
            }
            WifiPasspointCredential currentCredential = mCurrentUsedPolicy.getCredential();
            String restriction = currentCredential.getPolicyUpdateRestriction();
            boolean result = isRestricted(mCurrentUsedPolicy.getRestriction(), restriction);
            Log.d(TAG, "isPolicyUpdateRestricted:[" + restriction + "]:" + result);
            return result;
        }
    }

    public int syncGetPasspointState() {
        synchronized (mStateLock) {
            return mState;
        }
    }

    private BroadcastReceiver mAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "onReceive:" + action);

            if (action.equals(ACTION_NETWORK_POLICY_POLL)) {
                sendMessage(CMD_START_POLICY_UPDATE);
            } else if (action.equals(ACTION_NETWORK_REMEDIATION_POLL)) {
                sendMessage(CMD_START_REMEDIATION);
            }
        }
    };

    private void setupNetworkReceiver() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    switch (state) {
                        case WifiManager.WIFI_STATE_ENABLED:
                            sendMessage(CMD_ENABLE_PASSPOINT);
                            break;
                        case WifiManager.WIFI_STATE_DISABLED:
                            sendMessage(CMD_DISABLE_PASSPOINT);
                            break;
                        default:
                            // ignore
                    }
                } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    NetworkInfo wifiInfo =
                            (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

                    NetworkInfo.State wifiState = NetworkInfo.State.UNKNOWN;
                    wifiState = wifiInfo.getState();

                    if (wifiState == NetworkInfo.State.CONNECTED) {
                        sendMessage(CMD_WIFI_CONNECTED);
                    } else if (wifiState == NetworkInfo.State.DISCONNECTED) {
                        sendMessage(CMD_WIFI_DISCONNECTED);
                    }
                }
            }
        };

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
    }

    private void startAnqpFetch(Message message) {
        mCurrentAnqpRequest = message;
        mIsAnqpOngoing = true;
        switch (message.what) {
            case WifiPasspointManager.REQUEST_ANQP_INFO:
                ScanResult sr = (ScanResult) message.obj;
                int mask = message.arg1;
                if (VDBG)
                    logd("wifinative fetch anqp bssid=" + sr.BSSID + " mask=" + mask);
                mWifiNative.fetchAnqp(sr.BSSID, WifiPasspointInfo.toAnqpSubtypes(mask));
                break;
            case WifiPasspointManager.REQUEST_OSU_INFO:
                // TODO
                break;
            default:
                Log.e(TAG, "startAnqpFetch got unknown message type " + message.what);
        }
        sendMessageDelayed(CMD_GAS_QUERY_TIMEOUT, mAnqpTimeoutToken, ANQP_TIMEOUT_MS);
    }

    private void finishAnqpFetch(String bssid) {
        if (mCurrentAnqpRequest != null) {
            ScanResult sr = (ScanResult) mCurrentAnqpRequest.obj;
            if (bssid == null || bssid.equals(sr.BSSID)) {
                WifiPasspointInfo result = generatePasspointInfo(sr.BSSID);
                replyToMessage(mCurrentAnqpRequest,
                        WifiPasspointManager.REQUEST_ANQP_INFO_SUCCEEDED, result);
            }
        }

        if (mAnqpRequestQueue.isEmpty()) {
            if (VDBG) logd("mAnqpRequestQueue is empty, done");
            mIsAnqpOngoing = false;
            mCurrentAnqpRequest = null;
        } else {
            if (VDBG) logd("mAnqpRequestQueue is not empty, next");
            startAnqpFetch(mAnqpRequestQueue.remove());
        }
    }

    private class AnqpFrame {
        private byte bytes[];
        private int current;
        private int pos;

        private boolean init(String hexString) {
            int len = hexString.length();
            if (len % 2 != 0) return false;
            byte hexBytes[] = hexString.getBytes();
            bytes = new byte[len / 2];
            for (int i = 0, j = 0; i < len; i += 2, j++) {
                int decimal;
                String output = new String(hexBytes, i, 2);
                try {
                    decimal = Integer.parseInt(output, 16);
                } catch (NumberFormatException e) {
                    return false;
                }
                bytes[j] = (byte) decimal;
            }
            current = 0;
            return true;
        }

        private int readInt(int len) {
            int value = 0;
            for (int i = 0, shift = 0; i < len; i++, shift += 8) {
                int b = bytes[current++] & 0xFF;    // unsigned
                value += (b << shift);              // little endian
            }
            return value;
        }

        private long readLong(int len) {
            long value = 0;
            for (int i = 0, shift = 0; i < len; i++, shift += 8) {
                long b = bytes[current++] & 0xFF;   // unsigned
                value += (b << shift);              // little endian
            }
            return value;
        }

        private String readStr(int len) {
            String str = new String(bytes, current, len);
            current += len;
            return str;
        }

        private String readStrLanguage(int len, String prefer, String backup) {
            String ret = null;
            while (len > 0) {
                int n = readInt(1);
                String lang = readStr(3);
                String name = readStr(n - 3);
                len = len - n - 1;
                if (lang.equals(prefer)) {
                    ret = name;
                    break;
                } else if (lang.equals(backup)) {
                    ret = name;
                }
            }
            current += len;
            return ret;
        }

        private int getLeft() {
            return bytes.length - current;
        }

        private void setCount(int c) {
            pos = current + c;
        }

        private int getCount() {
            return pos - current;
        }

        private void clearCount() {
            current = pos;
        }

    }

    private void parseVenueName(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseVenueName()");
        try {
            int n = frame.readInt(2); // venue info
            n = frame.getLeft(); // venue info
            passpoint.venueName =
                    frame.readStrLanguage(n, mLanguageCode, DEFAULT_LANGUAGE_CODE);
            if (VDBG) logd("passpoint.venueName" + passpoint.venueName);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.venueName = null;
        }
    }

    private void parseNetworkAuthType(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseNetworkAuthType()");
        try {
            passpoint.networkAuthType = new ArrayList<WifiPasspointInfo.NetworkAuthType>();
            while (frame.getLeft() > 0) {
                WifiPasspointInfo.NetworkAuthType auth = new WifiPasspointInfo.NetworkAuthType();
                auth.type = frame.readInt(1);
                int n = frame.readInt(2);
                if (n > 0) auth.redirectUrl = frame.readStr(n);
                passpoint.networkAuthType.add(auth);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.networkAuthType = null;
        }
    }

    private void parseRoamingConsortium(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseRoamingConsortium()");
        try {
            passpoint.roamingConsortium = new ArrayList<String>();
            while (frame.getLeft() > 0) {
                int n = frame.readInt(1);
                String oi = "";
                for (int i = 0; i < n; i++)
                    oi += String.format("%02X", frame.readInt(1));
                passpoint.roamingConsortium.add(oi);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.roamingConsortium = null;
        }
    }

    private void parseIpAddrType(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseIpAddrType()");
        try {
            passpoint.ipAddrTypeAvailability = new WifiPasspointInfo.IpAddressType();
            passpoint.ipAddrTypeAvailability.availability = frame.readInt(1);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.ipAddrTypeAvailability = null;
        }
    }

    private void parseNaiRealm(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseNaiRealm()");
        try {
            passpoint.naiRealm = new ArrayList<WifiPasspointInfo.NaiRealm>();
            int n = frame.readInt(2);
            for (int i = 0; i < n; i++) {
                WifiPasspointInfo.NaiRealm realm = new WifiPasspointInfo.NaiRealm();
                int m = frame.readInt(2);
                frame.setCount(m);
                realm.encoding = frame.readInt(1);
                int l = frame.readInt(1);
                realm.realm = frame.readStr(l);
                frame.clearCount();
                passpoint.naiRealm.add(realm);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.naiRealm = null;
        }
    }

    private void parseCellularNetwork(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseCellularNetwork()");
        try {
            passpoint.cellularNetwork = new WifiPasspointInfo.CellularNetwork();
            passpoint.cellularNetwork.rawData = frame.bytes;
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.cellularNetwork = null;
        }
    }

    private void parseDomainName(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseDomainName()");
        try {
            passpoint.domainName = new ArrayList<String>();
            while (frame.getLeft() > 0) {
                int n = frame.readInt(1);
                passpoint.domainName.add(frame.readStr(n));
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.domainName = null;
        }
    }

    private void parseOperatorFriendlyName(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseOperatorFriendlyName()");
        try {
            int n = frame.getLeft();
            passpoint.operatorFriendlyName =
                    frame.readStrLanguage(n, mLanguageCode, DEFAULT_LANGUAGE_CODE);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.operatorFriendlyName = null;
        }
    }

    private void parseWanMetrics(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseWanMetrics()");
        try {
            passpoint.wanMetrics = new WifiPasspointInfo.WanMetrics();
            passpoint.wanMetrics.wanInfo = frame.readInt(1);
            passpoint.wanMetrics.downlinkSpeed = frame.readLong(4);
            passpoint.wanMetrics.uplinkSpeed = frame.readLong(4);
            passpoint.wanMetrics.downlinkLoad = frame.readInt(1);
            passpoint.wanMetrics.uplinkLoad = frame.readInt(1);
            passpoint.wanMetrics.lmd = frame.readInt(1);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.wanMetrics = null;
        }
    }

    private void parseConnectionCapability(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseConnectionCapability()");
        try {
            passpoint.connectionCapability = new ArrayList<WifiPasspointInfo.IpProtoPort>();
            while (frame.getLeft() > 0) {
                WifiPasspointInfo.IpProtoPort ip = new WifiPasspointInfo.IpProtoPort();
                ip.proto = frame.readInt(1);
                ip.port = frame.readInt(2);
                ip.status = frame.readInt(1);
                passpoint.connectionCapability.add(ip);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.connectionCapability = null;
        }
    }

    private void parseOsuProvider(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseOsuProvider()");
        try {
            passpoint.osuProviderList = new ArrayList<WifiPasspointOsuProvider>();

            // osu ssid
            int n = frame.readInt(1);
            String osuSSID = frame.readStr(n);
            if (VDBG) logd("osu_SSID=" + osuSSID);

            // osu provider list
            n = frame.readInt(1);
            for (int i = 0; i < n; i++) {
                WifiPasspointOsuProvider osu = new WifiPasspointOsuProvider();
                osu.ssid = osuSSID;

                int m = frame.readInt(2);
                if (VDBG) logd("osu_len=" + m);

                // osu friendly name
                m = frame.readInt(2);
                osu.friendlyName = frame.readStrLanguage(m, mLanguageCode, DEFAULT_LANGUAGE_CODE);
                if (VDBG) logd("fri_name=" + osu.friendlyName);

                // osu server uri
                m = frame.readInt(1);
                osu.serverUri = frame.readStr(m);
                if (VDBG) logd("uri=" + osu.serverUri);

                // osu method
                m = frame.readInt(1);
                frame.setCount(m);
                osu.osuMethod = frame.readInt(1);
                frame.clearCount();
                if (VDBG) logd("method=" + osu.osuMethod);

                // osu icons
                m = frame.readInt(2);
                frame.setCount(m);
                for (int best = 0; frame.getCount() > 0;) {
                    int w = frame.readInt(2);
                    int h = frame.readInt(2);
                    String lang = frame.readStr(3);
                    int lentype = frame.readInt(1);
                    String type = frame.readStr(lentype);
                    int lenfn = frame.readInt(1);
                    String fn = frame.readStr(lenfn);
                    if (w * h > best) {
                        best = w * h;
                        osu.iconWidth = w;
                        osu.iconHeight = h;
                        osu.iconType = type;
                        osu.iconFileName = fn;
                    }
                }
                frame.clearCount();
                if (VDBG) logd("icon=" + osu.iconWidth + "x" + osu.iconHeight + " " + osu.iconType
                        + " " + osu.iconFileName);

                // osu nai
                m = frame.readInt(1);
                if (m > 0) osu.osuNai = frame.readStr(m);
                if (VDBG) logd("nai=" + osu.osuNai);

                // osu service
                m = frame.readInt(2);
                osu.osuService = frame.readStrLanguage(m, mLanguageCode, DEFAULT_LANGUAGE_CODE);
                if (VDBG) logd("service=" + osu.osuService);

                passpoint.osuProviderList.add(osu);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("ArrayIndexOutOfBoundsException");
            passpoint.osuProviderList = null;
        }
    }

    private WifiPasspointInfo generatePasspointInfo(String bssid) {
        WifiPasspointInfo passpoint = new WifiPasspointInfo();
        passpoint.bssid = bssid;
        String result = mWifiNative.scanResult(bssid);
        String[] lines = result.split("\n");
        for (String line : lines) {
            String[] tokens = line.split("=");
            if (tokens.length < 2) continue;
            logd("got variable: " + tokens[0]);
            AnqpFrame frame = new AnqpFrame();
            if (!frame.init(tokens[1])) continue;
            if (tokens[0].equals("anqp_venue_name")) {
                parseVenueName(passpoint, frame);
            } else if (tokens[0].equals("anqp_network_auth_type")) {
                parseNetworkAuthType(passpoint, frame);
            } else if (tokens[0].equals("anqp_roaming_consortium")) {
                parseRoamingConsortium(passpoint, frame);
            } else if (tokens[0].equals("anqp_ip_addr_type_availability")) {
                parseIpAddrType(passpoint, frame);
            } else if (tokens[0].equals("anqp_nai_realm")) {
                parseNaiRealm(passpoint, frame);
            } else if (tokens[0].equals("anqp_3gpp")) {
                parseCellularNetwork(passpoint, frame);
            } else if (tokens[0].equals("anqp_domain_name")) {
                parseDomainName(passpoint, frame);
            } else if (tokens[0].equals("hs20_operator_friendly_name")) {
                parseOperatorFriendlyName(passpoint, frame);
            } else if (tokens[0].equals("hs20_wan_metrics")) {
                parseWanMetrics(passpoint, frame);
            } else if (tokens[0].equals("hs20_connection_capability")) {
                parseConnectionCapability(passpoint, frame);
            } else if (tokens[0].equals("hs20_osu_providers_list")) {
                parseOsuProvider(passpoint, frame);
            }
        }
        return passpoint;
    }

    /* State machine initiated requests can have replyTo set to null indicating
     * there are no recipients, we ignore those reply actions */
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null)
            return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null)
            return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null)
            return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /* arg2 on the source message has a hash code that needs to be retained in replies
     * see PasspointManager for details */
    private Message obtainMessage(Message srcMsg) {
        Message msg = Message.obtain();
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    private void handleProvisionDone(int result, WifiPasspointDmTree tree) {
        if (result == 0) {
            mWifiTree = tree;
        }
        disconnectWifi();
    }

    private void disconnectWifi() {
        mCredentialList.clear();
        mNetworkPolicy.clear();
        RemoveCurrentNetwork();
        mCurrentUsedPolicy = null;
        mWifiMgr.disconnect();
    }

    private void RemoveCurrentNetwork() {
        WifiInfo info = mWifiMgr.getConnectionInfo();
        if (info != null) {
            int networkId = info.getNetworkId();
            Log.d(TAG, "RemoveCurrentNetwork from conn_info:" + networkId);
            mWifiMgr.removeNetwork(networkId);
            mWifiMgr.saveConfiguration();
            return;
        } else {
            String ssid = mCurrentUsedPolicy.getSsid();
            List<WifiConfiguration> networks = mWifiMgr.getConfiguredNetworks();
            if (networks == null) {
                Log.d(TAG, "RemoveCurrentNetwork getConnectionInfo null");
                return;
            } else {
                for (WifiConfiguration config : networks) {
                    if (ssid.equals(config.SSID)) {
                        int networkId = config.networkId;
                        Log.d(TAG, "RemoveCurrentNetwork: from configuration" + networkId);
                        mWifiMgr.removeNetwork(networkId);
                        mWifiMgr.saveConfiguration();
                        return;
                    }
                }
            }
        }
    }

    private void createCredentialList(final WifiPasspointDmTree tree) {
        Log.d(TAG, "createCredentialFromTree");
        WifiPasspointCredential pc = null;
        String digiCertType = null;
        String unpwEapType = null;
        String simEapType = null;
        String digiCredSha256FingerPrint = null;
        String aaaRootCertSha256FingerPrint = null;

        if (tree == null) {
            return;
        }

        WifiPasspointDmTree.CredentialInfo info = null;
        Set spfqdnSet = tree.spFqdn.entrySet();
        Iterator spfqdnItr = spfqdnSet.iterator();

        while (spfqdnItr.hasNext()) {
            Map.Entry entry1 = (Map.Entry) spfqdnItr.next();
            WifiPasspointDmTree.SpFqdn sp = (WifiPasspointDmTree.SpFqdn) entry1.getValue();
            Log.d(TAG, "SPFQDN:" + sp.nodeName);

            Set credInfoSet = sp.perProviderSubscription.credentialInfo.entrySet();
            Iterator credinfoItr = credInfoSet.iterator();
            boolean isUserPreferred = false;//TODO: to get User perferred cred

            while (credinfoItr.hasNext()) {
                Map.Entry entry2 = (Map.Entry) credinfoItr.next();
                info = (WifiPasspointDmTree.CredentialInfo) entry2.getValue();
                if (info == null) {
                    return;
                }
                Log.d(TAG, "Credential:" + info.nodeName);

                unpwEapType = info.credential.usernamePassword.eAPMethod.EAPType;
                digiCertType = info.credential.digitalCertificate.CertificateType;
                digiCredSha256FingerPrint = info.credential.digitalCertificate.CertSHA256Fingerprint;
                simEapType = info.credential.sim.EAPType;

                WifiPasspointDmTree.AAAServerTrustRoot aaa = null;
                Set set = info.aAAServerTrustRoot.entrySet();
                Iterator i = set.iterator();
                if (i.hasNext()) {
                    Map.Entry entry = (Map.Entry) i.next();
                    aaa = (WifiPasspointDmTree.AAAServerTrustRoot) entry.getValue();
                }

                if (aaa == null) {
                    Log.d(TAG, "AAAServerTrustRoot is empty");
                    aaaRootCertSha256FingerPrint = null;
                } else {
                    aaaRootCertSha256FingerPrint = aaa.CertSHA256Fingerprint;
                }

                Log.d(TAG, "credCertType: " + digiCertType + ", credSha256FingerPrint: "
                        + digiCredSha256FingerPrint);
                Log.d(TAG, "aaaRootCertSha256FingerPrint: " + aaaRootCertSha256FingerPrint);
                String clientCert = "";

                KeyStore mKeyStore = KeyStore.getInstance();

                if (mKeyStore == null) {
                    Log.d(TAG, "mKeyStore is null");
                    return;
                }

                // TTLS
                if ("21".equals(unpwEapType)) {
                    String aaaRootCertSha1FingerPrint = null;

                    if (!mKeyStore.contains(Credentials.WIFI + aaaRootCertSha256FingerPrint)) {
                        Log.e(TAG, "AAA trust root is not existed in keystore");
                        return;
                    } else {
                        aaaRootCertSha1FingerPrint = new String(mKeyStore.get(Credentials.WIFI
                                + aaaRootCertSha256FingerPrint));
                    }

                    pc = new WifiPasspointCredential("TTLS",
                            aaaRootCertSha1FingerPrint,
                            null,
                            null,
                            null,
                            sp,
                            info);

                    pc.setUserPreference(isUserPreferred);
                    mCredentialList.add(pc);
                    // TLS
                } else if ("x509v3".equals(digiCertType)) {
                    // get certificate credential by searching Public Key
                    // SHA-256 FingerPrint
                    if (mKeyStore.contains(Credentials.WIFI + digiCredSha256FingerPrint)) {
                        Log.d(TAG, "load client cert");

                        String creSha1FingerPrint = new String(mKeyStore.get(Credentials.WIFI
                                + digiCredSha256FingerPrint));
                        String aaaRootCertSha1FingerPrint;

                        if (aaaRootCertSha256FingerPrint != null
                                && !aaaRootCertSha256FingerPrint.isEmpty()) {
                            Log.d(TAG, "AAA trust root is exclusive");
                            if (!mKeyStore
                                    .contains(Credentials.WIFI + aaaRootCertSha256FingerPrint)) {
                                Log.e(TAG, "AAA trust root is not existed in keystore");
                                return;
                            } else {
                                aaaRootCertSha1FingerPrint = new String(
                                        mKeyStore.get(Credentials.WIFI
                                                + aaaRootCertSha256FingerPrint));
                            }
                        } else {
                            Log.d(TAG, "AAA trust root is the same as client cert");
                            aaaRootCertSha1FingerPrint = creSha1FingerPrint;
                        }

                        pc = new WifiPasspointCredential("TLS",
                                aaaRootCertSha1FingerPrint,
                                creSha1FingerPrint,
                                null,
                                null,
                                sp,
                                info);
                        pc.setUserPreference(isUserPreferred);
                        mCredentialList.add(pc);
                    } else {
                        Log.d(TAG, "client cert doesn't exist");
                    }
                    // SIM
                } else if (simEapType != null) {
                    String credSimEapType = mIANA_EAPmethod[Integer.valueOf(simEapType)];
                    Log.d(TAG, "credSimEapType: " + credSimEapType);
                    if ("SIM".equals(credSimEapType)) {

                        String mccMnc = mTeleMgr.getSimOperator();
                        Log.d(TAG, "mccMnc: " + mccMnc);
                        if (mccMnc != null && !mccMnc.isEmpty()) {
                            Log.d(TAG, "[createCredentialFromMO] real SIM");
                            if (mccMnc.length() > 3) {
                                mMcc = mccMnc.substring(0, 3);
                                mMnc = mccMnc.substring(3);
                            } else {
                                Log.d(TAG,
                                        "[createCredentialFromMO] fail due to not getting MCC MNC");
                                return;
                            }
                        } else {
                            Log.d(TAG, "[createCredentialFromMO] simulate SIM");
                            info.credential.usernamePassword.Password = "90dca4eda45b53cf0f12d7c9c3bc6a89:cb9cccc4b9258e6dca4760379fb82581";
                            String iMsi = info.credential.sim.IMSI;
                            mMcc = iMsi.substring(0, 3); //TODO: make sure MCC, MNC length
                            mMnc = iMsi.substring(3, 3 + 3);
                            Log.d(TAG, "Get PLMN from IMSI, MCC = " + mMcc + ", MNC = " + mMnc);
                        }

                        pc = new WifiPasspointCredential("SIM",
                                null,
                                null,
                                mMcc,
                                mMnc,
                                sp,
                                info);
                        pc.setUserPreference(isUserPreferred);
                        mCredentialList.add(pc);
                    }

                }

            }
        }
    }

    private WifiConfiguration CreateOpenConfig(WifiPasspointPolicy pp) {
        Log.d(TAG, "CreateOpenConfig");
        WifiConfiguration wfg = new WifiConfiguration();
        if (pp.getBssid() != null) {
            wfg.BSSID = pp.getBssid();
        } else {
            wfg.SSID = pp.getSsid();
        }
        wfg.allowedKeyManagement.clear();
        wfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);

        return wfg;
    }

    private WifiConfiguration CreateEapTlsConfig(WifiPasspointPolicy pp) {
        //TODO: create TLS WifiConfiguration
        return null;
    }

    private WifiConfiguration CreateEapTtlsConfig(WifiPasspointPolicy pp) {
        //TODO: create TTLS WifiConfiguration
        return null;
    }

    private WifiConfiguration CreateEapSimConfig(WifiPasspointPolicy pp) {
        //TODO: create SIM WifiConfiguration
        return null;
    }

    private WifiPasspointPolicy buildPolicy(String name, String ssid,
            String bssid, WifiPasspointCredential pc,
            int restriction, boolean ishomesp) {

        WifiPasspointPolicy policy = new WifiPasspointPolicy(name, ssid, bssid, pc, restriction,
                ishomesp);
        Log.d(TAG, "buildPolicy:" + policy);
        return policy;
    }

    private boolean addPolicy(WifiPasspointPolicy newpolicy) {
        boolean ret = false;
        synchronized (mPolicyLock) {
            if (!mNetworkPolicy.contains(newpolicy)) {
                ret = mNetworkPolicy.add(newpolicy);
            }
        }
        Log.d(TAG, "addPolicy:" + ret + " ssid:" + newpolicy.getSsid());
        return ret;
    }

    private boolean deletePolicy(WifiPasspointPolicy oldpolicy) {
        if (oldpolicy == null) {
            return false;
        }

        boolean ret = false;
        synchronized (mPolicyLock) {
            if (mNetworkPolicy.contains(oldpolicy)) {
                ret = mNetworkPolicy.remove(oldpolicy);
            }
        }
        Log.d(TAG, "deletePolicy:" + ret + " ssid:" + oldpolicy.getSsid());
        return ret;
    }

    private boolean updatePolicy(WifiPasspointPolicy newpolicy) {
        boolean found = false;
        synchronized (mPolicyLock) {
            Log.d(TAG, "updatePolicy:" + newpolicy);
            for (WifiPasspointPolicy policy : mNetworkPolicy) {
                if (newpolicy.getSsid() != null && policy.getSsid() != null
                        && newpolicy.getSsid().equals(policy.getSsid())) {
                    found = true;
                    // Update Restriction info
                    if (newpolicy.getRestriction() < policy.getRestriction()) {
                        policy.setRestriction(newpolicy.getRestriction());
                        Log.d(TAG,
                                "updatePolicy policy.setRestriction:" + newpolicy.getRestriction());
                    }

                    // Update Homesp info
                    policy.setHomeSp(newpolicy.getHomeSp());
                    Log.d(TAG, "updatePolicy policy.setHomeSp:" + newpolicy.getHomeSp());

                    // Force update roaming priority for PartnerList/FQDN
                    policy.setRoamingPriority(newpolicy.getRoamingPriority());
                    Log.d(TAG,
                            "updatePolicy policy.setRoamingPriority:"
                                    + newpolicy.getRoamingPriority());

                    // Replace with a higher credential
                    Log.d(TAG, "updatePolicy compareTo");
                    if (newpolicy.getCredential().compareTo(policy.getCredential()) < 0) {
                        policy.setCredential(newpolicy.getCredential());
                        Log.d(TAG, "updatePolicy policy.setCredential:" + newpolicy.getCredential());
                    }
                }
            }
        }
        Log.d(TAG, "updatePolicy found?" + found);
        return found;
    }

    private void ConnectToPasspoint(WifiPasspointPolicy pp) {
        Log.d(TAG, "ConnectToPasspoint:" + pp);

        WifiInfo info = mWifiMgr.getConnectionInfo();
        if (info != null) {
            String ssid = info.getSSID();
            if (ssid != null && ssid.equals(pp.getSsid())) {
                Log.d(TAG, "The passpoint is already connected");
                return;
            }
        }

        WifiConfiguration wfg = null;

        WifiPasspointCredential currentCredential = pp.getCredential();
        if (currentCredential != null) {
            String updateIdentifier = currentCredential.getUpdateIdentifier();
            Log.d(TAG, "[ConnectToPasspoint] updateIdentifier = " + updateIdentifier);

            if (updateIdentifier != null && !updateIdentifier.isEmpty()) {
                Log.d(TAG, "[ConnectToPasspoint] set updateidentifier to supplicant");
                //mWifiMgr.setHsUpdateIdentifier(updateIdentifier);
            } else {
                //mWifiMgr.setHsUpdateIdentifier("0");
            }
        } else {
            //mWifiMgr.setHsUpdateIdentifier("0");
        }

        List<ScanResult> results = mWifiMgr.getScanResults();
        if (results == null) {
            Log.d(TAG, "Scan result is null.");
            return;
        }

        boolean osen = false;
        /*for (ScanResult result : results) {
            if (result.SSID.equals(pp.getSsid())) {
                if (result.capabilities.contains("OSEN")) {
                    osen = true;
                }
            }
        }*/

        if (osen) {
            //Log.d(TAG, "CreateOsenConfig");
            //mWifiMgr.disableAllNetworkAtOnce();
            //wfg = CreateOsenConfig(pp);
        } else if (null == currentCredential || null == currentCredential.getType()) {
            Log.d(TAG, "CreateOpenConfig");
            wfg = CreateOpenConfig(pp);
        } else if ("TTLS".equals(currentCredential.getType())) {
            Log.d(TAG, "CreateEapTtlsConfig");
            wfg = CreateEapTtlsConfig(pp);
        } else if ("SIM".equals(currentCredential.getType())) {
            Log.d(TAG, "CreateEapSimConfig");
            wfg = CreateEapSimConfig(pp);
        } else if ("TLS".equals(currentCredential.getType())) {
            Log.d(TAG, "CreateEapTlsConfig");
            wfg = CreateEapTlsConfig(pp);
        }

        int netid = 0;
        WifiConfiguration configuredWfg = findConfiguredNetworks(wfg);
        if (configuredWfg != null) {
            netid = configuredWfg.networkId;
            Log.d(TAG, "The passpoint is configed but disconnected, netid:" + netid + " ssid:"
                    + configuredWfg.SSID);
        } else {
            netid = mWifiMgr.addNetwork(wfg);
            Log.d(TAG, "The passpoint is not configed, addNetwork:" + netid + " ssid:" + wfg.SSID);
        }

        // Set highest priority while OSU or using preferred credential
        if (pp.getCredentialPriority() == 0 ||
                (osen || null == currentCredential || null == currentCredential.getType())) {

            wfg.priority = findHighestPriorityNetwork() + 1;
            mWifiMgr.updateNetwork(wfg);
        }

        /*
         * For applying WifiStateTracker design change by Mediatek. The device
         * can't reconnect if auth. failed over max retry
         * count.("mediatek.wlan.hs20.reauth") If the network was configured and
         * disabled, don't enable it again.
         */
        /*if (configuredWfg != null && mIsEapFailureOverCount) {
            Log.d(TAG, "[ConnectToPasspoint] mIsEapFailureOverCount:true");
            List<WifiConfiguration> configuredList = mWifiMgr.getConfiguredNetworks();
            for (WifiConfiguration config : configuredList) {
                Log.d(TAG, "[ConnectToPasspoint] check configured netid:" + config.networkId
                        + " status:" + config.status);
                if (config.networkId == netid && config.status == WifiConfiguration.Status.DISABLED) {
                    Log.d(TAG, "[ConnectToPasspoint] return due to disabled");
                    return;
                }
            }
        }*/

        mWifiMgr.enableNetwork(netid, false);
    }

    private WifiConfiguration findConfiguredNetworks(WifiConfiguration wfg) {

        List<WifiConfiguration> networks = mWifiMgr.getConfiguredNetworks();
        if (wfg == null || wfg.SSID == null || networks == null) {
            return null;
        }

        for (WifiConfiguration config : networks) {
            if (wfg.SSID.equals(config.SSID)) {
                Log.d(TAG, "findConfiguredNetworks:" + config.SSID);
                return config;
            }
        }
        Log.d(TAG, "findConfiguredNetworks:empty");
        return null;
    }

    private int findHighestPriorityNetwork() {
        List<WifiConfiguration> networks = mWifiMgr.getConfiguredNetworks();
        int priority = 0;
        WifiConfiguration foundWfg = null;

        if (networks == null)
            return 0;

        for (WifiConfiguration config : networks) {
            if (config.priority > priority) {
                priority = config.priority;
            }
        }
        Log.d(TAG, "getHighestPriorityNetwork: " + priority);
        return priority;
    }

    private boolean isIntervalValided(String interval) {
        if (interval == null) {
            return false;
        } else if (interval.toUpperCase().equals("0XFFFFFFFF")
                || interval.toUpperCase().equals("4294967295") || interval.isEmpty()) {
            return false;
        }
        return true;
    }

    private void updatePolicyUpdateAlarm() {
        if (mCurrentUsedPolicy != null) {
            WifiPasspointCredential currentCredential = mCurrentUsedPolicy.getCredential();
            if (currentCredential != null) {
                String updateInterval = currentCredential.getPolicyUpdateInterval();
                if (isIntervalValided(updateInterval)) {
                    Long policyUpdateInterval = Long.decode(updateInterval) * 60;
                    Log.d(TAG, "set policy update every " + policyUpdateInterval + " seconds");
                    if (mPolicyPollIntent != null) {
                        mAlarmManager.cancel(mPolicyPollIntent);
                    }
                    mPolicyPollIntent = PendingIntent.getBroadcast(
                            mContext, 0, new Intent(ACTION_NETWORK_POLICY_POLL), 0);

                    long intervalMillis = policyUpdateInterval * 1000;
                    long triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis;
                    mAlarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME, triggerAtMillis,
                            intervalMillis, mPolicyPollIntent);
                } else {
                    Log.d(TAG, "set to not to do policy update forever");
                    if (mPolicyPollIntent != null) {
                        mAlarmManager.cancel(mPolicyPollIntent);
                    }
                }
            }
        }

    }

    private void startHttpServer(int port) {
        SimpleHttpServer httpServer = new SimpleHttpServer(port);
        httpServer.startListener();
    }

    //TODO: change to random number
    private int HS_OSU_LISTEN_PORT = 8899;
    private int HS_REM_LISTEN_PORT = 9900;

    class SimpleHttpServer {
        private int mServerPort;

        SimpleHttpServer(int port) {
            mServerPort = port;
        }

        public void startListener() {
            new Thread(new Runnable() {

                public void run() {
                    Log.d(TAG, "[HttpServer] >> enter");
                    if (mServerPort == HS_OSU_LISTEN_PORT) {
                        try {
                            if (mServerSocketForOSU == null) {
                                mServerSocketForOSU = new ServerSocket(mServerPort);
                                Log.d(TAG, "[HttpServer] The server is running on " + mServerPort
                                        + " mServerSocketForOSU:" + mServerSocketForOSU);
                            } else {
                                Log.d(TAG,
                                        "[HttpServer] The server is running already mServerSocketForOSU:"
                                                + mServerSocketForOSU);
                                return;
                            }
                        } catch (SocketException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        try {
                            // Accept incoming connections.
                            Log.d(TAG, "[HttpServer] accepting");
                            Socket clientSocket;
                            clientSocket = mServerSocketForOSU.accept();
                            Log.d(TAG, "[HttpServer] accepted clientSocket:" + clientSocket);

                            handleResponseToClient(clientSocket, mServerPort);
                            clientSocket.close();
                        } catch (Exception ioe) {
                            Log.d(TAG,
                                    "[HttpServer] Exception encountered on accept. Ignoring. Stack Trace :");
                            ioe.printStackTrace();
                        }

                        try {
                            mServerSocketForOSU.close();
                            Log.d(TAG, "[HttpServer] ServerSocket closed");
                        } catch (Exception ioe) {
                            Log.d(TAG, "[HttpServer] Problem stopping server socket");
                            ioe.printStackTrace();
                        }
                        mServerSocketForOSU = null;
                        Log.d(TAG, "[HttpServer] << exit");
                    } else if (mServerPort == HS_REM_LISTEN_PORT) {
                        try {
                            if (mServerSocketForREM == null) {
                                mServerSocketForREM = new ServerSocket(mServerPort);
                                Log.d(TAG, "[HttpServer] The server is running on " + mServerPort
                                        + " mServerSocketForREM:" + mServerSocketForREM);
                            } else {
                                Log.d(TAG,
                                        "[HttpServer] The server is running already mServerSocketForREM:"
                                                + mServerSocketForREM);
                                return;
                            }
                        } catch (SocketException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        try {
                            // Accept incoming connections.
                            Log.d(TAG, "[HttpServer] accepting");
                            Socket clientSocket;
                            clientSocket = mServerSocketForREM.accept();
                            Log.d(TAG, "[HttpServer] accepted clientSocket:" + clientSocket);

                            handleResponseToClient(clientSocket, mServerPort);
                            clientSocket.close();
                        } catch (Exception ioe) {
                            Log.d(TAG,
                                    "[HttpServer] Exception encountered on accept. Ignoring. Stack Trace :");
                            ioe.printStackTrace();
                        }

                        try {
                            mServerSocketForREM.close();
                            Log.d(TAG, "[HttpServer] ServerSocket closed");
                        } catch (Exception ioe) {
                            Log.d(TAG, "[HttpServer] Problem stopping server socket");
                            ioe.printStackTrace();
                        }
                        mServerSocketForREM = null;
                        Log.d(TAG, "[HttpServer] << exit");
                    }

                }// end of run()
            }).start();
        }

        private void handleResponseToClient(Socket cs, int sp) {
            final String HTTP_RESPNOSE = "<html><body>" + "redirected test" + "</body></html>";
            String redirectIntent = null;
            BufferedReader in = null;
            PrintWriter out = null;
            String updateMethod = null;
            WifiPasspointClient.BaseClient client = null;

            if (HS_OSU_LISTEN_PORT == sp) {
                updateMethod = mUpdateMethod;
            } else if (HS_REM_LISTEN_PORT == sp) {
                updateMethod = mCurrentUsedPolicy.getCredential().getUpdateMethod();
            }

            if (WifiPasspointManager.PROTOCOL_SOAP.equals(updateMethod)) {
                client = mSoapClient;
            } else if (WifiPasspointManager.PROTOCOL_DM.equals(updateMethod)) {
                client = mDmClient;
            } else {
                Log.e(TAG, "STOP, updateMethod is not mentioned");
                return;
            }

            client.setBrowserRedirected();

            Log.d(TAG, "[HttpServer] Accepted Client Address-" + cs.getInetAddress().getHostName());

            try {
                in = new BufferedReader(new InputStreamReader(cs.getInputStream()));
                out = new PrintWriter(new OutputStreamWriter(cs.getOutputStream()));

                Log.d(TAG, "[HttpServer] Start to send http response to browser");
                out.write("HTTP/1.1 200 OK\r\n");
                out.write("Content-Type: text/html; charset=UTF-8\r\n");
                out.write("Content-Length: " + HTTP_RESPNOSE.length() + "\r\n\r\n");
                out.write(HTTP_RESPNOSE);
                out.flush();
                Log.d(TAG, "[HttpServer] End to send http response to browser");

                Intent intent = new Intent(redirectIntent);
                mContext.sendBroadcast(intent);

            } catch (Exception e) {
                Log.d(TAG, "[HttpServer] write response error");
                e.printStackTrace();
            } finally {
                try {
                    if (in != null)
                        in.close();
                    if (out != null)
                        out.close();
                    Log.d(TAG, "[HttpServer] handleResponseToClient r/w closed");
                } catch (Exception ioe) {
                    Log.d(TAG, "[HttpServer] r/w close error");
                    ioe.printStackTrace();
                }
            }
        }
    }
}
