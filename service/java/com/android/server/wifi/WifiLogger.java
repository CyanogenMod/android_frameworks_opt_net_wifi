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
class WifiLogger  {

    private static final String TAG = "WifiLogger";
    private static boolean DBG = true;
    private int mSupportedLoggerFeatures;
    private RingBufferStatus[] mRingBufferStatuses;

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI_LOGGER;
    /* receive log data */
    static final int LOG_DATA                 = BASE + 1;

    public static class RingBufferStatus{
        String name;
        int flag;
        int ringBufferId;
        int ringBufferByteSize;
        int verboseLevel;
        int writtenBytes;
        int readBytes;
        int writtenRecords;

        @Override
        public String toString() {
            return "name: " + name + " flag: " + flag + " ringBufferId: " + ringBufferId +
                    " ringBufferByteSize: " +ringBufferByteSize + " verboseLevel: " +verboseLevel +
                    " writtenBytes: " + writtenBytes + " readBytes: " + readBytes +
                    " writtenRecords: " + writtenRecords;
        }
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
        } else {
            DBG = false;
        }
    }



    public  WifiLogger() {}

    public int getSupportedFeatureSet() {
        if(mSupportedLoggerFeatures == 0) {
            mSupportedLoggerFeatures = WifiNative.getSupportedLoggerFeatureSet();
        }

        if(DBG) Log.d(TAG, "Supported Logger features is: " + mSupportedLoggerFeatures);
        return mSupportedLoggerFeatures;
    }

    public String getDriverVersion() {
        String driverVersion = WifiNative.getDriverVersion();
        if(DBG) Log.d(TAG, "Driver Version is: " + driverVersion);
        return driverVersion;
    }

    public String getFirmwareVersion() {
        String firmwareVersion = WifiNative.getFirmwareVersion();
        if(DBG) Log.d(TAG, "Firmware Version is: " + firmwareVersion);
        return firmwareVersion;
    }

    public RingBufferStatus[] getRingBufferStatus() {

        mRingBufferStatuses = WifiNative.getRingBufferStatus();
        if (mRingBufferStatuses != null) {
            if(DBG) {
                for (RingBufferStatus element : mRingBufferStatuses) {
                    Log.d(TAG, "RingBufferStatus is: \n" + element);
                }
            }
        } else {
            Log.e(TAG, "get empty RingBufferStatus");
        }

        return mRingBufferStatuses;
    }

    public static final int VERBOSE_NO_LOG = 0;
    public static final int VERBOSE_NORMAL_LOG = 1;
    /** Be careful since this one can affect performance and power */
    public static final int VERBOSE_DETAILED_LOG  = 2;

    /**
     * start both logging and alert collection
     * @param verboseLevel please check the definition above
     * @param flags   TBD not used now
     * @param maxInterval maximum interval in seconds for driver to report, ignore if zero
     * @param minDataSize minimum data size in buffer for driver to report, ignore if zer0
     * @param ringName  The name of the ring you'd like to report
     * @return true -- successful false --failed
     */
    public boolean startLoggingRingBuffer(int verboseLevel, int flags, int maxInterval,
            int minDataSize, String ringName) {
        boolean result = WifiNative.startLoggingRingBuffer(verboseLevel,flags, maxInterval,
                minDataSize, ringName);
        if (DBG) Log.d("TAG", "RingBuffer- " + ringName + "'s verbose level is:" +
                verboseLevel + (result ? "Successful" : "failed"));
        return result;
    }

    public boolean startLoggingAllBuffer(int verboseLevel, int flags, int maxInterval,
                                         int minDataSize){
        Log.e(TAG, "startLoggingAllBuffer");
        if (mRingBufferStatuses == null) {
            getRingBufferStatus();
            if(mRingBufferStatuses == null) {
                Log.e(TAG, "Can not get Ring Buffer Status. Can not start Logging!");
                return false;
            }
        }

        for (RingBufferStatus element : mRingBufferStatuses){
            boolean result = startLoggingRingBuffer(verboseLevel, flags, maxInterval, minDataSize,
                    element.name);
            if(!result) {
                return false;
            }
        }

        getRingBufferStatus();
        return true;
    }

    public boolean getRingBufferData(String ringName) {
        return WifiNative.getRingBufferData(ringName);
    }

    public boolean getAllRingBufferData() {
        if (mRingBufferStatuses == null) {
            getRingBufferStatus();
            if(mRingBufferStatuses == null) {
                Log.e(TAG, "Can not get Ring Buffer Status. Can not collect data!");
                return false;
            }
        }

        for (RingBufferStatus element : mRingBufferStatuses){
            boolean result = getRingBufferData(element.name);
            if(!result) {
                Log.e(TAG, "Fail to get ring buffer data of: " + element.name);
                return false;
            }
        }
        Log.d(TAG, "getAllRingBufferData Successfully!");
        return true;
    }

    public String getFwMemoryDump() {
        return WifiNative.getFwMemoryDump();
    }

}
