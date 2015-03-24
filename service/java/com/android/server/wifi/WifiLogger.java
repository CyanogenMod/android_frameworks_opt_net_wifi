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

import android.os.BatteryStats;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Tracks the state changes in supplicant and provides functionality
 * that is based on these state changes:
 * - detect a failed WPA handshake that loops indefinitely
 * - authentication failure handling
 */
class WifiLogger extends StateMachine {

    private static final String TAG = "WifiLogger";
    private static boolean DBG = true;

    private final WifiStateMachine mWifiStateMachine;

    private final Context mContext;

    private final State mEnabledState = new EnabledState();
    private final State mDefaultState = new DefaultState();


    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI_LOGGER;
    /* receive log data */
    static final int LOG_DATA                 = BASE + 1;

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
        } else {
            DBG = false;
        }
    }

    private WifiNative.WifiLoggerEventHandler mEventHandler
            = new WifiNative.WifiLoggerEventHandler() {
        @Override
        public void onDataAvailable(char data[], int len) {
            sendMessage(LOG_DATA, data);
        }
    };

    public WifiLogger(Context c, WifiStateMachine wsm) {
        super(TAG);

        mContext = c;
        mWifiStateMachine = wsm;
        addState(mDefaultState);
            addState(mEnabledState, mDefaultState);

        setInitialState(mEnabledState);
        setLogRecSize(50);
        setLogOnlyTransitions(true);
        //start the state machine
        start();
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends State {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
         }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case LOG_DATA:
                    break;

                default:
                    Log.e(TAG, "Ignoring " + message);
                    break;
            }
            return HANDLED;
        }
    }

    class EnabledState extends State {
        @Override
         public void enter() {
             if (DBG) Log.d(TAG, getName() + "\n");
         }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);

        pw.println();
    }
}
