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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.passpoint.PasspointInfo;
import android.net.wifi.passpoint.PasspointManager;
import android.os.Message;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiStateMachine;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

/**
 * TODO: doc
 */
public class PasspointStateMachine extends StateMachine {
    private static final String TAG = "PasspointStateMachine";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private static final int BASE = Protocol.BASE_WIFI_PASSPOINT_SERVICE;

    private static final int CMD_ENABLE_PASSPOINT           = BASE + 1;
    private static final int CMD_DISABLE_PASSPOINT          = BASE + 2;
    private static final int CMD_GAS_QUERY_TIMEOUT          = BASE + 3;

    private static final int ANQP_TIMEOUT_MS                = 5000;

    private String mInterface;
    private WifiNative mWifiNative;
    private int mState = PasspointManager.PASSPOINT_STATE_UNKNOWN;
    private Object mStateLock = new Object();

    private AsyncChannel mReplyChannel = new AsyncChannel();

    private Context mContext;
    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;

    private String mLanguageCode;   // TODO: update this when language changes

    private Queue<Message> mAnqpRequestQueue = new LinkedList<Message>();
    private Message mCurrentAnqpRequest;
    private boolean mIsAnqpOngoing = false;
    private int mAnqpTimeoutToken = 0;

    private DefaultState mDefaultState = new DefaultState();
    private DisabledState mDisabledState = new DisabledState();
    private EnabledState mEnabledState = new EnabledState();
    private DiscoveryState mDiscoveryState = new DiscoveryState();
    private AccessState mAccessState = new AccessState();

    public PasspointStateMachine(Context context, String iface) {
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
                addState(mDiscoveryState, mEnabledState);
                addState(mAccessState, mEnabledState);

        setInitialState(mDisabledState);

        setLogRecSize(1000);
        setLogOnlyTransitions(false);
        if (DBG) setDbg(true);
    }

    class DefaultState extends State {

        @Override
        public boolean processMessage(Message message) {
            if (VDBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiStateMachine.CMD_REGISTER_PASSPOINT:
                    WifiMonitor wm = (WifiMonitor)message.obj;
                    wm.setStateMachine2(PasspointStateMachine.this);
                    break;
                case CMD_ENABLE_PASSPOINT:
                case CMD_DISABLE_PASSPOINT:
                case CMD_GAS_QUERY_TIMEOUT:
                    break;
                case PasspointManager.REQUEST_ANQP_INFO:
                    replyToMessage(message, PasspointManager.REQUEST_ANQP_INFO_FAILED,
                            PasspointManager.BUSY);
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
                mState = PasspointManager.PASSPOINT_STATE_DISABLED;
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (VDBG) logd(getName() + message.toString());
            switch (message.what) {
                case CMD_ENABLE_PASSPOINT:
                    transitionTo(mEnabledState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class EnabledState extends State {

        @Override
        public boolean processMessage(Message message) {
            if (VDBG) logd(getName() + message.toString());
            switch (message.what) {
                case CMD_ENABLE_PASSPOINT:
                    // nothing to do
                    break;

                case CMD_DISABLE_PASSPOINT:
                    // TODO: flush ANQP request queue
                    transitionTo(mDisabledState);
                    break;

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

                case PasspointManager.REQUEST_ANQP_INFO:
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

    class DiscoveryState extends State {
        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = PasspointManager.PASSPOINT_STATE_DISCOVERY;
            }
        }
    }

    class AccessState extends State {
        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = PasspointManager.PASSPOINT_STATE_ACCESS;
            }
        }
    }

    class ProvisionState extends State {
        @Override
        public void enter() {
            synchronized (mStateLock) {
                mState = PasspointManager.PASSPOINT_STATE_PROVISION;
            }
        }
    }

    public int syncGetPasspointState() {
        synchronized (mStateLock) {
            return mState;
        }
    }

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
                        case WifiManager.WIFI_STATE_DISABLING:
                            sendMessage(CMD_DISABLE_PASSPOINT);
                            break;
                        default:
                            // ignore
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
            case PasspointManager.REQUEST_ANQP_INFO:
                ScanResult sr = (ScanResult) message.obj;
                int mask = message.arg1;
                if (VDBG) logd("wifinative fetch anqp bssid=" + sr.BSSID + " mask=" + mask);
                mWifiNative.fetchAnqp(sr.BSSID, PasspointInfo.toAnqpSubtypes(mask));
                break;
            case PasspointManager.REQUEST_OSU_INFO:
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
                PasspointInfo result = generatePasspointInfo(sr.BSSID);
                replyToMessage(mCurrentAnqpRequest,
                        PasspointManager.REQUEST_ANQP_INFO_SUCCEEDED, result);
            }
        }

        if (mAnqpRequestQueue.isEmpty()) {
            if (VDBG) logd("mAnqpRequestQueue is emptry, done");
            mIsAnqpOngoing = false;
            mCurrentAnqpRequest = null;
        } else {
            if (VDBG) logd("mAnqpRequestQueue is not emptry, next");
            startAnqpFetch(mAnqpRequestQueue.remove());
        }
    }

    private PasspointInfo generatePasspointInfo(String bssid) {
        PasspointInfo pi = new PasspointInfo();
        String result = mWifiNative.scanResult(bssid);
        String[] lines = result.split("\n");
        for (String line : lines) {
            String[] tokens = line.split("=");
            logd("got line: token1=" + tokens[0] + " token2=" +
                    (tokens.length >= 2 ? tokens[1] : "null"));

        }
        pi.bssid = bssid;
        return pi;
    }

    /* State machine initiated requests can have replyTo set to null indicating
     * there are no recipients, we ignore those reply actions */
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
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
