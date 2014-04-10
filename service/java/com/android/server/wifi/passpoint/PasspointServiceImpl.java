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

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.passpoint.IPasspointManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Messenger;
import android.os.SystemProperties;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * TODO: doc
 * @hide
 */
public final class PasspointServiceImpl extends IPasspointManager.Stub {
    private static final String TAG = "PasspointService";
    private static final boolean DBG = true;

    private Context mContext;
    private String mInterface;

    private PasspointStateMachine mSm;

    public PasspointServiceImpl(Context context) {
        mContext = context;
        mInterface = SystemProperties.get("wifi.interface", "wlan0");

        mSm = new PasspointStateMachine(mContext, mInterface);
        mSm.start();
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE, TAG);
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE, TAG);
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL, TAG);
    }

    @Override
    public Messenger getMessenger() {
        if (DBG) Log.d(TAG, "hs20: getMessenger");
        enforceAccessPermission();
        enforceChangePermission();
//        enforceConnectivityInternalPermission();
        Log.d(TAG, "getMessenger, mSm=" + mSm.toString());
        return new Messenger(mSm.getHandler());
    }

    @Override
    public int getPasspointState() {
        return mSm.syncGetPasspointState();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump PasspointService from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        mSm.dump(fd, pw, args);
        // TODO
        pw.println("dump test");
        pw.println();
    }


}
