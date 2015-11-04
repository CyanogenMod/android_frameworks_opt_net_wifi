/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.net.wifi.WifiScanner;
import android.os.Looper;

import com.android.server.wifi.WifiNative;

/**
 * Defines the interface to the Wifi hardware required for the WifiScanner API
 */
public abstract class WifiScannerImpl {
    /**
     * Create the implementation that is most appropriate for the system.
     * This method should only ever be called once.
     */
    public static WifiScannerImpl create(Context context, Looper looper) {
        WifiNative wifiNative = WifiNative.getWlanNativeInterface();
        if (wifiNative.getScanCapabilities(new WifiNative.ScanCapabilities())) {
            return new HalWifiScannerImpl(wifiNative, looper);
        }
        else {
            return new SupplicantWifiScannerImpl(context, wifiNative, looper);
        }
    }

    public abstract boolean getScanCapabilities(WifiNative.ScanCapabilities capabilities);

    /**
     * Start a one time scan. This method should only be called when there is no scan going on
     * (after a callback indicating that the previous scan succeeded/failed).
     * @return if the scan paramaters are valid
     * Note this may return true even if the parameters are not accepted by the chip because the
     * scan may be scheduled async.
     */
    public abstract boolean startSingleScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler);
    /**
     * Get the scan results of the most recent single scan. This should be called immediately when
     * the scan success callback is receieved.
     */
    public abstract WifiScanner.ScanData getLatestSingleScanResults();

    /**
     * Start a background scan. Calling this method while a background scan is already in process
     * will interrupt the previous scan settings and replace it with the new ones.
     * @return if the scan paramaters are valid
     * Note this may return true even if the parameters are not accepted by the chip because the
     * scan may be scheduled async.
     */
    public abstract boolean startBatchedScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler);
    /**
     * Stop the currently active background scan
     */
    public abstract void stopBatchedScan();

    public abstract void pauseBatchedScan();
    public abstract void restartBatchedScan();

    /**
     * Get the latest cached scan results from the last scan event. This should be called
     * immediately when the scan success callback is receieved.
     */
    public abstract WifiScanner.ScanData[] getLatestBatchedScanResults(boolean flush);

    public abstract boolean setHotlist(WifiScanner.HotlistSettings settings,
            WifiNative.HotlistEventHandler eventHandler);
    public abstract void resetHotlist();
}
