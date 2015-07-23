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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiNative;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SupplicantWifiScannerImpl extends WifiScannerImpl implements Handler.Callback {
    private static final String TAG = "SupplicantWifiScannerImpl";
    private static final boolean DBG = false;

    private static final int SCAN_BUFFER_CAPACITY = 10;
    private static final int MAX_APS_PER_SCAN = 32;
    private static final int MAX_SCAN_BUCKETS = 16;

    private static final String ACTION_SCAN_PERIOD =
            "com.android.server.util.SupplicantWifiScannerImpl.action.SCAN_PERIOD";

    private final Context mContext;
    private final WifiNative mWifiNative;
    private final AlarmManager mAlarmManager;
    private final PendingIntent mScanPeriodIntent;
    private final Handler mEventHandler;

    private Object mSettingsLock = new Object();

    // Next scan settings to apply when the previous scan completes
    private WifiNative.ScanSettings mPendingScanSettings = null;
    private WifiNative.ScanEventHandler mPendingScanEventHandler = null;

    // Active scan settings
    private WifiNative.ScanSettings mScanSettings = null;
    private WifiNative.ScanEventHandler mScanEventHandler = null;
    private int mNextScanPeriod = 0;
    private int mNextScanId = 0;
    private LastScanSettings mLastScanSettings = null;
    private ScanBuffer mScanBuffer = new ScanBuffer(SCAN_BUFFER_CAPACITY);

    // Active hotlist settings
    private WifiNative.HotlistEventHandler mHotlistHandler = null;
    private ChangeBuffer mHotlistChangeBuffer = new ChangeBuffer();

    public SupplicantWifiScannerImpl(Context context, WifiNative wifiNative, Looper looper) {
        mContext = context;
        mWifiNative = wifiNative;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mScanPeriodIntent = getPrivateBroadcast(ACTION_SCAN_PERIOD, 0);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        // in case we never got the results of the previous scan
                        // make sure we start a new scan
                        synchronized (mSettingsLock) {
                            if (mLastScanSettings != null) {
                                Log.w(TAG,
                                        "Did not get a scan results/failure event from previous scan");
                                mLastScanSettings = null;
                            }
                            handleScanPeriod();
                        }
                    }
                },
                new IntentFilter(ACTION_SCAN_PERIOD));

        mEventHandler = new Handler(looper, this);
        WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_FAILED_EVENT, mEventHandler);
        WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_RESULTS_EVENT, mEventHandler);
    }

    @Override
    public boolean getScanCapabilities(WifiNative.ScanCapabilities capabilities) {
        capabilities.max_scan_cache_size = Integer.MAX_VALUE;
        capabilities.max_scan_buckets = MAX_SCAN_BUCKETS;
        capabilities.max_ap_cache_per_scan = MAX_APS_PER_SCAN;
        capabilities.max_rssi_sample_size = 8;
        capabilities.max_scan_reporting_threshold = SCAN_BUFFER_CAPACITY;
        capabilities.max_hotlist_bssids = 0;
        capabilities.max_significant_wifi_change_aps = 0;
        return true;
    }

    @Override
    public boolean startBatchedScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        if (settings == null || eventHandler == null)
            return false;

        if (settings.max_ap_per_scan < 0 || settings.max_ap_per_scan > MAX_APS_PER_SCAN)
            return false;
        if (settings.num_buckets < 0 || settings.num_buckets > MAX_SCAN_BUCKETS)
            return false;
        if (settings.report_threshold_num_scans < 0 ||
                settings.report_threshold_num_scans > SCAN_BUFFER_CAPACITY)
            return false;
        if (settings.report_threshold_percent < 0 || settings.report_threshold_percent > 100)
            return false;
        if (settings.base_period_ms <= 0)
            return false;
        for (int i = 0; i < settings.num_buckets; ++i) {
            WifiNative.BucketSettings bucket = settings.buckets[i];
            if (bucket.period_ms % settings.base_period_ms != 0)
                return false;
        }

        synchronized(mSettingsLock) {
            stopBatchedScan();
            Log.d(TAG, "Starting scan num_buckets=" + settings.num_buckets + ", base_period=" +
                    settings.base_period_ms + " ms");
            mPendingScanSettings = settings;
            mPendingScanEventHandler = eventHandler;
            handleScanPeriod(); // Try to start scan immediately
            return true;
        }
    }

    @Override
    public void stopBatchedScan() {
        synchronized(mSettingsLock) {
            if (DBG) {Log.d(TAG, "Stopping scan"); }
            mScanSettings = null;
            mScanEventHandler = null;
            mPendingScanSettings = null;
            mPendingScanEventHandler = null;
            unscheduleScansLocked();
        }
    }

    @Override
    public void pauseBatchedScan() {
        synchronized(mSettingsLock) {
            if (DBG) { Log.d(TAG, "Pausing scan"); }
            // if there isn't a pending scan then make the current scan pending
            if (mPendingScanSettings == null) {
                mPendingScanSettings = mScanSettings;
                mPendingScanEventHandler = mScanEventHandler;
            }
            mScanSettings = null;
            mScanEventHandler = null;

            unscheduleScansLocked();

            WifiScanner.ScanData[] results = getLatestBatchedScanResults(/* flush = */ true);
            if (mPendingScanEventHandler != null)
                mPendingScanEventHandler.onScanPaused(results);
        }
    }

    @Override
    public void restartBatchedScan() {
        synchronized(mSettingsLock) {
            if (DBG) { Log.d(TAG, "Restarting scan"); }
            if (mPendingScanEventHandler != null)
                mPendingScanEventHandler.onScanRestarted();
            handleScanPeriod();
        }
    }

    private void unscheduleScansLocked() {
        mAlarmManager.cancel(mScanPeriodIntent);
        mLastScanSettings = null; // make sure that a running scan is marked as ended
    }

    private void handleScanPeriod() {
        synchronized(mSettingsLock) {
            if (mLastScanSettings != null) {
                return;
            }

            // Update scan settings if there is a pending scan
            if (mPendingScanSettings != null) {
                mScanSettings = mPendingScanSettings;
                mScanEventHandler = mPendingScanEventHandler;
                mNextScanPeriod = 0;
                mPendingScanSettings = null;
                mPendingScanEventHandler = null;
            }

            if (mScanSettings != null && mScanSettings.num_buckets > 0) {
                Set<Integer> freqs = new HashSet<>();
                int reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH; // default to no batch
                for (int bucket_id = 0; bucket_id < mScanSettings.num_buckets; ++bucket_id) {
                    WifiNative.BucketSettings bucket = mScanSettings.buckets[bucket_id];
                    if (mNextScanPeriod % (bucket.period_ms / mScanSettings.base_period_ms) == 0) {
                        if ((bucket.report_events & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN)
                                != 0) {
                            reportEvents |= WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                        }
                        if ((bucket.report_events & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT)
                                != 0) {
                            reportEvents |= WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
                        }
                        // only no batch if all buckets specify it
                        if ((bucket.report_events & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                            reportEvents &= ~WifiScanner.REPORT_EVENT_NO_BATCH;
                        }

                        if (bucket.band != WifiScanner.WIFI_BAND_UNSPECIFIED) {
                            int channels[] = mWifiNative.getChannelsForBand(bucket.band);
                            for (int channel : channels) {
                                freqs.add(channel);
                            }
                        }
                        else {
                            for (int channel_id = 0; channel_id < bucket.num_channels;
                                    ++channel_id) {
                                WifiNative.ChannelSettings channel = bucket.channels[channel_id];
                                freqs.add(channel.frequency);
                            }
                        }
                    }
                }
                if (freqs.size() > 0) {
                    boolean success = mWifiNative.scan(
                            WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, freqs);
                    if (success) {
                        Log.d(TAG, "Starting wifi scan " + mNextScanId + " for " + freqs.size() +
                                " freqs");
                        mLastScanSettings = new LastScanSettings(mNextScanId++,
                                SystemClock.elapsedRealtime(), mScanSettings.max_ap_per_scan,
                                reportEvents, mScanSettings.report_threshold_num_scans,
                                mScanSettings.report_threshold_percent);
                    }
                    else {
                        Log.w(TAG, "Failed starting wifi scan for " + freqs.size() + " freqs");
                    }
                }

                mNextScanPeriod++;
                mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + mScanSettings.base_period_ms,
                        mScanPeriodIntent);
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case WifiMonitor.SCAN_FAILED_EVENT:
                Log.w(TAG, "Scan failed");
                synchronized (mSettingsLock) {
                    mLastScanSettings = null;
                }
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                pollLatestScanData();
                break;
            default:
                // ignore unknown event
        }
        return true;
    }

    private static final Comparator<ScanResult> SCAN_RESULT_RSSI_COMPARATOR =
            new Comparator<ScanResult>() {
        public int compare(ScanResult r1, ScanResult r2) {
            return r2.level - r1.level;
        }
    };
    private void pollLatestScanData() {
        synchronized(mSettingsLock) {
            if (mLastScanSettings == null || mScanSettings == null) {
                 // got a scan before we started scanning or after scan was canceled
                return;
            }

            if (DBG) { Log.d(TAG, "Polling scan data for scan: " + mLastScanSettings.scanId); }
            ArrayList<ScanDetail> nativeResults = mWifiNative.getScanResults();
            List<ScanResult> scanResults = new ArrayList<>();
            for (int i = 0; i < nativeResults.size(); ++i) {
                ScanResult result = nativeResults.get(i).getScanResult();
                long timestamp_ms = result.timestamp / 1000; // convert us -> ms
                if (timestamp_ms > mLastScanSettings.startTime) {
                    scanResults.add(result);
                }
                else {
                    // was a cached result in wpa_supplicant
                }
            }
            Collections.sort(scanResults, SCAN_RESULT_RSSI_COMPARATOR);
            ScanResult[] scanResultsArray =
                new ScanResult[Math.min(mLastScanSettings.maxAps, scanResults.size())];
            for (int i = 0; i < scanResultsArray.length; ++i) {
                scanResultsArray[i] = scanResults.get(i);
            }
            WifiScanner.ScanData scanData = new WifiScanner.ScanData(mLastScanSettings.scanId, 0,
                    scanResultsArray);

            if ((mLastScanSettings.reportEvents & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                mScanBuffer.add(scanData);
            }

            if (mScanEventHandler != null) {
                if ((mLastScanSettings.reportEvents &
                             WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                    for (ScanResult scanResult : scanResultsArray) {
                        mScanEventHandler.onFullScanResult(scanResult);
                    }
                }

                if ((mLastScanSettings.reportEvents &
                             WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0 ||
                      (mLastScanSettings.reportEvents &
                             WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0 ||
                      (mLastScanSettings.reportEvents == WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL
                       && (mScanBuffer.size() >=
                           (mScanBuffer.capacity() * mLastScanSettings.reportPercentThreshold
                            / 100) ||
                           mScanBuffer.size() >= mLastScanSettings.reportNumScansThreshold)) ) {
                    mScanEventHandler.onScanStatus();
                }
            }

            if (mHotlistHandler != null) {
                int event = mHotlistChangeBuffer.processScan(scanResultsArray);
                if ((event & ChangeBuffer.EVENT_FOUND) != 0) {
                    mHotlistHandler.onHotlistApFound(
                            mHotlistChangeBuffer.getLastResults(ChangeBuffer.EVENT_FOUND));
                }
                if ((event & ChangeBuffer.EVENT_LOST) != 0) {
                    mHotlistHandler.onHotlistApLost(
                            mHotlistChangeBuffer.getLastResults(ChangeBuffer.EVENT_LOST));
                }
            }
        }
    }


    private PendingIntent getPrivateBroadcast(String action, int requestCode) {
        Intent intent = new Intent(action, null);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.setPackage("android");
        return PendingIntent.getBroadcast(mContext, requestCode, intent, 0);
    }

    @Override
    public WifiScanner.ScanData[] getLatestBatchedScanResults(boolean flush) {
        WifiScanner.ScanData[] results = mScanBuffer.get();
        if (flush) {
            mScanBuffer.clear();
        }
        return results;
    }

    @Override
    public boolean setHotlist(WifiScanner.HotlistSettings settings,
            WifiNative.HotlistEventHandler eventHandler) {
        if (settings == null || eventHandler == null) {
            return false;
        }
        synchronized(mSettingsLock) {
            mHotlistHandler = eventHandler;
            mHotlistChangeBuffer.setSettings(settings.bssidInfos, settings.apLostThreshold, 1);
            return true;
        }
    }

    @Override
    public void resetHotlist() {
        synchronized(mSettingsLock) {
            mHotlistChangeBuffer.clearSettings();
            mHotlistHandler = null;
        }
    }

    private static class LastScanSettings {
        int scanId;
        long startTime;
        int maxAps;
        int reportEvents;
        int reportNumScansThreshold;
        int reportPercentThreshold;

        public LastScanSettings(int scanId, long startTime, int maxAps, int reportEvents,
                int reportNumScansThreshold, int reportPercentThreshold) {
            this.scanId = scanId;
            this.startTime = startTime;
            this.maxAps = maxAps;
            this.reportEvents = reportEvents;
            this.reportNumScansThreshold = reportNumScansThreshold;
            this.reportPercentThreshold = reportPercentThreshold;
        }
    }


    private static class ScanBuffer {
        private final ArrayDeque<WifiScanner.ScanData> mBuffer;
        private int capacity;

        public ScanBuffer(int capacity) {
            mBuffer = new ArrayDeque<>(capacity);
            this.capacity = capacity;
        }

        public int size() {
            return mBuffer.size();
        }

        public int capacity() {
            return capacity;
        }

        public boolean isFull() {
            return size() == capacity;
        }

        public void add(WifiScanner.ScanData scanData) {
            if (isFull()) {
                mBuffer.pollFirst();
            }
            mBuffer.offerLast(scanData);
        }

        public void clear() {
            mBuffer.clear();
        }

        public WifiScanner.ScanData[] get() {
            return mBuffer.toArray(new WifiScanner.ScanData[mBuffer.size()]);
        }
    }

    private static class ChangeBuffer {
        public static int EVENT_NONE = 0;
        public static int EVENT_LOST = 1;
        public static int EVENT_FOUND = 2;

        private static int STATE_FOUND = 0;

        private WifiScanner.BssidInfo[] mBssidInfos = null;
        private int mApLostThreshold;
        private int mMinEvents;
        private int[] mLostCount = null;
        private ScanResult[] mMostRecentResult = null;
        private int[] mPendingEvent = null;
        private boolean mFiredEvents = false;

        private static ScanResult findResult(ScanResult[] results, String bssid) {
            for (int i = 0; i < results.length; ++i) {
                if (bssid.equalsIgnoreCase(results[i].BSSID)) {
                    return results[i];
                }
            }
            return null;
        }

        public void setSettings(WifiScanner.BssidInfo[] bssidInfos, int apLostThreshold,
                                int minEvents) {
            mBssidInfos = bssidInfos;
            if (apLostThreshold <= 0) {
                mApLostThreshold = 1;
            }
            else {
                mApLostThreshold = apLostThreshold;
            }
            mMinEvents = minEvents;
            if (bssidInfos != null) {
                mLostCount = new int[bssidInfos.length];
                Arrays.fill(mLostCount, mApLostThreshold); // default to lost
                mMostRecentResult = new ScanResult[bssidInfos.length];
                mPendingEvent = new int[bssidInfos.length];
                mFiredEvents = false;
            }
            else {
                mLostCount = null;
                mMostRecentResult = null;
                mPendingEvent = null;
            }
        }

        public void clearSettings() {
            setSettings(null, 0, 0);
        }

        /**
         * Get the most recent scan results for APs that triggered the given event on the last call
         * to {@link #processScan}.
         */
        public ScanResult[] getLastResults(int event) {
            ArrayList<ScanResult> results = new ArrayList<>();
            for (int i = 0; i < mLostCount.length; ++i) {
                if (mPendingEvent[i] == event) {
                    results.add(mMostRecentResult[i]);
                }
            }
            return results.toArray(new ScanResult[results.size()]);
        }

        /**
         * Process the supplied scan results and determine if any events should be generated based
         * on the configured settings
         * @return The events that occurred
         */
        public int processScan(ScanResult[] scanResults) {
            if (mBssidInfos == null) {
                return EVENT_NONE;
            }

            // clear events from last time
            if (mFiredEvents) {
                mFiredEvents = false;
                for (int i = 0; i < mLostCount.length; ++i) {
                    mPendingEvent[i] = EVENT_NONE;
                }
            }

            int eventCount = 0;
            int eventType = EVENT_NONE;
            for (int i = 0; i < mLostCount.length; ++i) {
                ScanResult result = findResult(scanResults, mBssidInfos[i].bssid);
                int rssi = Integer.MIN_VALUE;
                if (result != null) {
                    mMostRecentResult[i] = result;
                    rssi = result.level;
                }

                if (rssi < mBssidInfos[i].low) {
                    if (mLostCount[i] < mApLostThreshold) {
                        mLostCount[i]++;

                        if (mLostCount[i] >= mApLostThreshold) {
                            if (mPendingEvent[i] == EVENT_FOUND) {
                                mPendingEvent[i] = EVENT_NONE;
                            }
                            else {
                                mPendingEvent[i] = EVENT_LOST;
                            }
                        }
                    }
                }
                else {
                    if (mLostCount[i] >= mApLostThreshold) {
                        if (mPendingEvent[i] == EVENT_LOST) {
                            mPendingEvent[i] = EVENT_NONE;
                        }
                        else {
                            mPendingEvent[i] = EVENT_FOUND;
                        }
                    }
                    mLostCount[i] = STATE_FOUND;
                }
                if (DBG) {
                    Log.d(TAG, "ChangeBuffer BSSID: " + mBssidInfos[i].bssid + "=" + mLostCount[i] +
                            ", " + mPendingEvent[i] + ", rssi=" + rssi);
                }
                if (mPendingEvent[i] != EVENT_NONE) {
                    ++eventCount;
                    eventType |= mPendingEvent[i];
                }
            }
            if (DBG) { Log.d(TAG, "ChangeBuffer events count=" + eventCount + ": " + eventType); }
            if (eventCount >= mMinEvents) {
                mFiredEvents = true;
                return eventType;
            }
            return EVENT_NONE;
        }
    }
}
