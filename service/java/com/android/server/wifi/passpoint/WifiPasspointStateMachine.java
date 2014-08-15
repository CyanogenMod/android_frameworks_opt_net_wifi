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
import android.net.wifi.passpoint.WifiPasspointManager.ParcelableString;
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
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Queue;

/**
 * TODO: doc
 */
public class WifiPasspointStateMachine extends StateMachine {
    private static final String TAG = "WifiPasspointStateMachine";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private static final int BASE = Protocol.BASE_WIFI_PASSPOINT_SERVICE;

    static final int CMD_ENABLE_PASSPOINT               = BASE + 1;
    static final int CMD_DISABLE_PASSPOINT              = BASE + 2;
    static final int CMD_GAS_QUERY_TIMEOUT              = BASE + 3;
    static final int CMD_START_OSU                      = BASE + 4;
    static final int CMD_START_REMEDIATION              = BASE + 5;
    static final int CMD_START_POLICY_UPDATE            = BASE + 6;
    static final int CMD_LAUNCH_BROWSER                 = BASE + 7;
    static final int CMD_ENROLL_CERTIFICATE             = BASE + 8;
//    static final int CMD_OSU_RETRY                      = BASE + 9;
    static final int CMD_OSU_DONE                       = BASE + 10;
    static final int CMD_OSU_FAIL                       = BASE + 11;
    static final int CMD_REMEDIATION_DONE               = BASE + 12;
    static final int CMD_POLICY_UPDATE_DONE             = BASE + 13;
    static final int CMD_SIM_PROVISION_DONE             = BASE + 14;
    static final int CMD_BROWSER_REDIRECTED             = BASE + 15;
    static final int CMD_ENROLLMENT_DONE                = BASE + 16;
    static final int CMD_WIFI_CONNECTED                 = BASE + 17;
    static final int CMD_WIFI_DISCONNECTED              = BASE + 18;

    private static final int ANQP_TIMEOUT_MS = 5000;

    public static final String
            ACTION_NETWORK_POLICY_POLL = "com.android.intent.action.PASSPOINT_POLICY_POLL";

    public static final
            String ACTION_NETWORK_REMEDIATION_POLL = "com.android.intent.action.PASSPOINT_REMEDIATION_POLL";

    private static final String DEFAULT_LANGUAGE_CODE = "zxx";
    private static final String R1_NODE_NAME = "WifiPasspointR1";

    private String mInterface;
    private WifiNative mWifiNative;
    private int mState = WifiPasspointManager.PASSPOINT_STATE_UNKNOWN;
    private Object mStateLock = new Object();
    private Object mPolicyLock = new Object();
    private ArrayList<WifiPasspointCredential> mCredentialList;
    private ArrayList<WifiPasspointPolicy> mNetworkPolicy = new ArrayList<WifiPasspointPolicy>();

    private AsyncChannel mReplyChannel = new AsyncChannel();

    private Context mContext;
    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;

    private String mLanguageCode; // TODO: update this when language changes

    private Queue<Message> mAnqpRequestQueue = new LinkedList<Message>();
    private Message mCurrentAnqpRequest;
    private boolean mIsAnqpOngoing = false;
    private int mAnqpTimeoutToken = 0;
    private WifiManager mWifiMgr;
    private TelephonyManager mTeleMgr;
    private AlarmManager mAlarmManager;
    private WifiPasspointPolicy mCurrentUsedPolicy;
    private String mMcc;
    private String mMnc;
    private ServerSocket mRedirectServerSocket;
    private String mUpdateMethod;
    private PendingIntent mPolicyPollIntent;

    private DefaultState mDefaultState = new DefaultState();
    private DisabledState mDisabledState = new DisabledState();
    private EnabledState mEnabledState = new EnabledState();

    private enum MatchSubscription {
        REALM, PLMN, HOMESP_FQDN, HOMESP_OTHER_HOME_PARTNER, HOME_OI;
    }

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

        addState(mDefaultState);
        addState(mDisabledState, mDefaultState);
        addState(mEnabledState, mDefaultState);

        setInitialState(mDisabledState);

        setLogRecSize(1000);
        setLogOnlyTransitions(false);
        if (VDBG) setDbg(true);
    }

    String smToString(Message message) {
        String s = "unknown";
        switch (message.what) {
            case CMD_ENABLE_PASSPOINT:
                s = "CMD_ENABLE_PASSPOINT";
                break;
            case CMD_DISABLE_PASSPOINT:
                s = "CMD_DISABLE_PASSPOINT";
                break;
            case CMD_GAS_QUERY_TIMEOUT:
                s = "CMD_GAS_QUERY_TIMEOUT";
                break;
            case CMD_START_OSU:
                s = "CMD_START_OSU";
                break;
            case CMD_START_REMEDIATION:
                s = "CMD_START_REMEDIATION";
                break;
            case CMD_START_POLICY_UPDATE:
                s = "CMD_START_POLICY_UPDATE";
                break;
            case CMD_LAUNCH_BROWSER:
                s = "CMD_LAUNCH_BROWSER";
                break;
            case CMD_ENROLL_CERTIFICATE:
                s = "CMD_ENROLL_CERTIFICATE";
                break;
            case CMD_OSU_DONE:
                s = "CMD_OSU_DONE";
                break;
            case CMD_OSU_FAIL:
                s = "CMD_OSU_FAIL";
                break;
            case CMD_REMEDIATION_DONE:
                s = "CMD_REMEDIATION_DONE";
                break;
            case CMD_POLICY_UPDATE_DONE:
                s = "CMD_POLICY_UPDATE_DONE";
                break;
            case CMD_SIM_PROVISION_DONE:
                s = "CMD_SIM_PROVISION_DONE";
                break;
            case CMD_BROWSER_REDIRECTED:
                s = "CMD_BROWSER_REDIRECTED";
                break;
            case CMD_ENROLLMENT_DONE:
                s = "CMD_ENROLLMENT_DONE";
                break;
            case CMD_WIFI_CONNECTED:
                s = "CMD_WIFI_CONNECTED";
                break;
            case CMD_WIFI_DISCONNECTED:
                s = "CMD_WIFI_DISCONNECTED";
                break;
        }
        return s;
    }


    /**
     * Return the additional string to be logged by LogRec, default
     *
     * @param msg that was processed
     * @return information to be logged as a String
     */
    protected String getLogRecString(Message msg) {
        StringBuilder sb = new StringBuilder();
        sb.append(smToString(msg));

        switch (msg.what) {
            default:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                break;
        }
        return sb.toString();
    }

    private void logStateAndMessage(Message message, String state) {
        StringBuilder sb = new StringBuilder();
        if (DBG) {
            sb.append( " " + state + " " + getLogRecString(message));
        }
        if (VDBG && message != null) {
            sb.append(" " + message.toString());
        }
        loge(sb.toString());
    }

    public void systemServiceReady() {
        mWifiMgr = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mTeleMgr = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
    }

    public int syncGetPasspointState() {
        synchronized (mStateLock) {
            return mState;
        }
    }

    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) logStateAndMessage(message, getClass().getSimpleName());
            switch (message.what) {
                case CMD_ENABLE_PASSPOINT:
                    transitionTo(mEnabledState);
                    break;
                case CMD_DISABLE_PASSPOINT:
                    transitionTo(mDisabledState);
                    break;
                case CMD_GAS_QUERY_TIMEOUT:
                    break;
                case WifiPasspointManager.REQUEST_ANQP_INFO:
                    replyToMessage(message, WifiPasspointManager.REQUEST_ANQP_INFO_FAILED,
                            WifiPasspointManager.REASON_BUSY);
                    break;
                default:
                    loge("Unhandled message " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private class DisabledState extends State {

        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = WifiPasspointManager.PASSPOINT_STATE_DISABLED;
            }
        }
    }

    private class EnabledState extends State {

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logStateAndMessage(message, getClass().getSimpleName());
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
                        if (VDBG) logd("ANQP fetch timeout");
                        finishAnqpFetch(null);
                    }
                    break;

                case WifiPasspointManager.REQUEST_ANQP_INFO:
                    // make a copy as the original message will be recycled
                    Message msg = new Message();
                    msg.copyFrom(message);
                    if (mIsAnqpOngoing) {
                        if (VDBG) logd("new anqp request buffered");
                        logd("added msg.what = " + message.what);
                        mAnqpRequestQueue.add(msg);
                    } else {
                        if (VDBG) logd("new anqp request started");
                        startAnqpFetch(msg);
                    }
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
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
            case WifiPasspointManager.REQUEST_OSU_ICON:
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
            if (current + len > bytes.length) throw new ArrayIndexOutOfBoundsException();
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
            passpoint.venueName = frame.readStrLanguage(n, mLanguageCode, DEFAULT_LANGUAGE_CODE);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseVenueName: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.venueName = null;
        }
    }

    private void parseNetworkAuthType(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseNetworkAuthType()");
        try {
            passpoint.networkAuthTypeList = new ArrayList<WifiPasspointInfo.NetworkAuthType>();
            while (frame.getLeft() > 0) {
                WifiPasspointInfo.NetworkAuthType auth = new WifiPasspointInfo.NetworkAuthType();
                auth.type = frame.readInt(1);
                int n = frame.readInt(2);
                if (n > 0) auth.redirectUrl = frame.readStr(n);
                passpoint.networkAuthTypeList.add(auth);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseNetworkAuthType: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.networkAuthTypeList = null;
        }
    }

    private void parseRoamingConsortium(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseRoamingConsortium()");
        try {
            passpoint.roamingConsortiumList = new ArrayList<String>();
            while (frame.getLeft() > 0) {
                int n = frame.readInt(1);
                String oi = "";
                for (int i = 0; i < n; i++)
                    oi += String.format("%02x", frame.readInt(1));
                passpoint.roamingConsortiumList.add(oi);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseRoamingConsortium: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.roamingConsortiumList = null;
        }
    }

    private void parseIpAddrType(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseIpAddrType()");
        try {
            passpoint.ipAddrTypeAvailability = new WifiPasspointInfo.IpAddressType();
            passpoint.ipAddrTypeAvailability.availability = frame.readInt(1);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseIpAddrType: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.ipAddrTypeAvailability = null;
        }
    }

    private void parseNaiRealm(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseNaiRealm()");
        try {
            passpoint.naiRealmList = new ArrayList<WifiPasspointInfo.NaiRealm>();
            int n = frame.readInt(2);
            for (int i = 0; i < n; i++) {
                WifiPasspointInfo.NaiRealm realm = new WifiPasspointInfo.NaiRealm();
                int m = frame.readInt(2);
                frame.setCount(m);
                realm.encoding = frame.readInt(1);
                int l = frame.readInt(1);
                realm.realm = frame.readStr(l);
                frame.clearCount();
                passpoint.naiRealmList.add(realm);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseNaiRealm: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.naiRealmList = null;
        }
    }

    private void parseCellularNetwork(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseCellularNetwork()");
        try {
            passpoint.cellularNetworkList = new ArrayList<WifiPasspointInfo.CellularNetwork>();
            int gud = frame.readInt(1);
            int udhl = frame.readInt(1);

            while (frame.getLeft() > 0) {
                int iei = frame.readInt(1);
                int plmn_length = frame.readInt(1);
                int plmn_num = frame.readInt(1);
                for (int i = 0; i < plmn_num; i++) {
                    WifiPasspointInfo.CellularNetwork plmn = new WifiPasspointInfo.CellularNetwork();

                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < 3; j++) sb.append(String.format("%02x", frame.readInt(1)));
                    String plmn_mix = sb.toString();

                    plmn.mcc = "";
                    plmn.mcc += plmn_mix.charAt(1);
                    plmn.mcc += plmn_mix.charAt(0);
                    plmn.mcc += plmn_mix.charAt(3);

                    plmn.mnc = "";
                    plmn.mnc += plmn_mix.charAt(5);
                    plmn.mnc += plmn_mix.charAt(4);
                    if (plmn_mix.charAt(2) != 'f') plmn.mnc += plmn_mix.charAt(2);

                    passpoint.cellularNetworkList.add(plmn);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseCellularNetwork: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.cellularNetworkList = null;
        }
    }

    private void parseDomainName(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseDomainName()");
        try {
            passpoint.domainNameList = new ArrayList<String>();
            while (frame.getLeft() > 0) {
                int n = frame.readInt(1);
                passpoint.domainNameList.add(frame.readStr(n));
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseDomainName: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.domainNameList = null;
        }
    }

    private void parseOperatorFriendlyName(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseOperatorFriendlyName()");
        try {
            int n = frame.getLeft();
            passpoint.operatorFriendlyName =
                    frame.readStrLanguage(n, mLanguageCode, DEFAULT_LANGUAGE_CODE);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseOperatorFriendlyName: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
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
            if (VDBG) logd("parseWanMetrics: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.wanMetrics = null;
        }
    }

    private void parseConnectionCapability(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseConnectionCapability()");
        try {
            passpoint.connectionCapabilityList = new ArrayList<WifiPasspointInfo.IpProtoPort>();
            while (frame.getLeft() > 0) {
                WifiPasspointInfo.IpProtoPort ip = new WifiPasspointInfo.IpProtoPort();
                ip.proto = frame.readInt(1);
                ip.port = frame.readInt(2);
                ip.status = frame.readInt(1);
                passpoint.connectionCapabilityList.add(ip);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseConnectionCapability: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
            passpoint.connectionCapabilityList = null;
        }
    }

    private void parseOsuProvider(WifiPasspointInfo passpoint, AnqpFrame frame) {
        if (VDBG) logd("parseOsuProvider()");
        try {
            passpoint.osuProviderList = new ArrayList<WifiPasspointOsuProvider>();

            // osu ssid
            int n = frame.readInt(1);
            String osuSSID = frame.readStr(n);

            // osu provider list
            n = frame.readInt(1);
            for (int i = 0; i < n; i++) {
                WifiPasspointOsuProvider osu = new WifiPasspointOsuProvider();
                osu.ssid = osuSSID;

                int m = frame.readInt(2);

                // osu friendly name
                m = frame.readInt(2);
                osu.friendlyName = frame.readStrLanguage(m, mLanguageCode, DEFAULT_LANGUAGE_CODE);

                // osu server uri
                m = frame.readInt(1);
                osu.serverUri = frame.readStr(m);

                // osu method
                m = frame.readInt(1);
                frame.setCount(m);
                osu.osuMethod = frame.readInt(1);
                frame.clearCount();

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

                // osu nai
                m = frame.readInt(1);
                if (m > 0) osu.osuNai = frame.readStr(m);

                // osu service
                m = frame.readInt(2);
                osu.osuService = frame.readStrLanguage(m, mLanguageCode, DEFAULT_LANGUAGE_CODE);

                passpoint.osuProviderList.add(osu);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (VDBG) logd("parseOsuProvider: ArrayIndexOutOfBoundsException");
            e.printStackTrace();
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
        if (msg == null || msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg == null || msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg == null || msg.replyTo == null) return;
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
}
