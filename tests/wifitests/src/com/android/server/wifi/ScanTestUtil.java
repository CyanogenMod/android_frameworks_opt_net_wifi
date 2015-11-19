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
 * limitations under the License
 */

package com.android.server.wifi;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.WifiSsid;

import com.android.server.wifi.WifiNative.BucketSettings;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Utilities for testing Wifi Scanning
 */

public class ScanTestUtil {

    public static void installWlanWifiNative(WifiNative wifiNative) throws Exception {
        Field field = WifiNative.class.getDeclaredField("wlanNativeInterface");
        field.setAccessible(true);
        field.set(null, wifiNative);

        // Clear static state
        WifiChannelHelper.clearChannelCache();
    }

    public static void setupMockChannels(WifiNative wifiNative, int[] channels24, int[] channels5,
            int[] channelsDfs) throws Exception {
        when(wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ))
                .thenReturn(channels24);
        when(wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ))
                .thenReturn(channels5);
        when(wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY))
                .thenReturn(channelsDfs);
    }

    public static ScanSettings createRequest(WifiScanner.ChannelSpec[] channels, int period,
            int batch, int bssidsPerScan, int reportEvents) {
        ScanSettings request = new ScanSettings();
        request.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        request.channels = channels;
        request.periodInMs = period;
        request.numBssidsPerScan = bssidsPerScan;
        request.maxScansToCache = batch;
        request.reportEvents = reportEvents;
        return request;
    }

    public static ScanSettings createRequest(int band, int period, int batch, int bssidsPerScan,
            int reportEvents) {
        ScanSettings request = new ScanSettings();
        request.band = band;
        request.channels = null;
        request.periodInMs = period;
        request.numBssidsPerScan = bssidsPerScan;
        request.maxScansToCache = batch;
        request.reportEvents = reportEvents;
        return request;
    }

    public static class NativeScanSettingsBuilder {
        private final WifiNative.ScanSettings settings = new WifiNative.ScanSettings();
        public NativeScanSettingsBuilder() {
            settings.buckets = new WifiNative.BucketSettings[0];
            settings.num_buckets = 0;
            settings.report_threshold_percent = 100;
        }

        public NativeScanSettingsBuilder withBasePeriod(int basePeriod) {
            settings.base_period_ms = basePeriod;
            return this;
        }
        public NativeScanSettingsBuilder withMaxApPerScan(int maxAp) {
            settings.max_ap_per_scan = maxAp;
            return this;
        }
        public NativeScanSettingsBuilder withMaxScansToCache(int maxScans) {
            settings.report_threshold_num_scans = maxScans;
            return this;
        }

        public NativeScanSettingsBuilder addBucketWithBand(
                int period, int report_events, int band) {
            WifiNative.BucketSettings bucket = new WifiNative.BucketSettings();
            bucket.bucket = settings.num_buckets;
            bucket.band = band;
            bucket.period_ms = period;
            bucket.report_events = report_events;
            return addBucket(bucket);
        }

        public NativeScanSettingsBuilder addBucketWithChannels(
                int period, int report_events, int... channels) {
            WifiNative.BucketSettings bucket = new WifiNative.BucketSettings();
            bucket.bucket = settings.num_buckets;
            bucket.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            bucket.num_channels = channels.length;
            bucket.channels = new WifiNative.ChannelSettings[channels.length];
            for (int i = 0; i < channels.length; ++i) {
                bucket.channels[i] = new WifiNative.ChannelSettings();
                bucket.channels[i].frequency = channels[i];
            }
            bucket.period_ms = period;
            bucket.report_events = report_events;
            return addBucket(bucket);
        }

        public NativeScanSettingsBuilder addBucket(WifiNative.BucketSettings bucket) {
            settings.buckets = Arrays.copyOf(settings.buckets, settings.num_buckets + 1);
            settings.buckets[settings.num_buckets] = bucket;
            settings.num_buckets = settings.num_buckets + 1;
            return this;
        }

        public WifiNative.ScanSettings build() {
            return settings;
        }
    }

    public static Set<Integer> createFreqSet(int... elements) {
        Set<Integer> set = new HashSet<>();
        for (int e : elements) {
            set.add(e);
        }
        return set;
    }

    public static ScanResult createScanResult(int freq) {
        return new ScanResult(WifiSsid.createFromAsciiEncoded("AN SSID"), "00:00:00:00:00:00", "",
                0, freq, 0);
    }

    public static ScanData createScanData(int... freqs) {
        ScanResult[] results = new ScanResult[freqs.length];
        for (int i = 0; i < freqs.length; ++i) {
            results[i] = createScanResult(freqs[i]);
        }
        return new ScanData(0, 0, results);
    }

    public static ScanData[] createScanDatas(int[][] freqs) {
        ScanData[] data = new ScanData[freqs.length];
        for (int i = 0; i < freqs.length; ++i) {
            data[i] = createScanData(freqs[i]);
        }
        return data;
    }

    public static void assertScanDatasEquals(ScanData[] expected, ScanData[] actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals("ScanData.length", expected.length, actual.length);
        for (int i = 0; i < expected.length; ++i) {
            ScanData expectedData = expected[i];
            ScanData actualData = actual[i];
            assertEquals("ScanData["+i+"].id", expectedData.getId(), actualData.getId());
            assertEquals("ScanData["+i+"].flags", expectedData.getFlags(), actualData.getFlags());
            assertEquals("ScanData["+i+"].results.length",
                    expectedData.getResults().length, actualData.getResults().length);
            for (int j = 0; j < expectedData.getResults().length; ++ j) {
                ScanResult expectedResult = expectedData.getResults()[j];
                ScanResult actualResult = actualData.getResults()[j];
                assertEquals("ScanData["+i+"].results["+j+"].SSID",
                        expectedResult.SSID, actualResult.SSID);
                assertEquals("ScanData["+i+"].results["+j+"].wifiSsid",
                        expectedResult.wifiSsid.toString(), actualResult.wifiSsid.toString());
                assertEquals("ScanData["+i+"].results["+j+"].BSSID",
                        expectedResult.BSSID, actualResult.BSSID);
                assertEquals("ScanData["+i+"].results["+j+"].capabilities",
                        expectedResult.capabilities, actualResult.capabilities);
                assertEquals("ScanData["+i+"].results["+j+"].level",
                        expectedResult.level, actualResult.level);
                assertEquals("ScanData["+i+"].results["+j+"].frequency",
                        expectedResult.frequency, actualResult.frequency);
                assertEquals("ScanData["+i+"].results["+j+"].timestamp",
                        expectedResult.timestamp, actualResult.timestamp);
                assertEquals("ScanData["+i+"].results["+j+"].frequency",
                        expectedResult.seen, actualResult.seen);
            }
        }
    }

    public static WifiScanner.ChannelSpec[] channelsToSpec(int... channels) {
        WifiScanner.ChannelSpec[] channelSpecs = new WifiScanner.ChannelSpec[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            channelSpecs[i] = new WifiScanner.ChannelSpec(channels[i]);
        }
        return channelSpecs;
    }

    public static ChannelSpec[] getAllChannels(BucketSettings bucket) {
        if (bucket.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
            ChannelSpec[] channels = new ChannelSpec[bucket.num_channels];
            for (int i = 0; i < bucket.num_channels; i++) {
                channels[i] = new ChannelSpec(bucket.channels[i].frequency);
            }
            return channels;
        } else {
            return WifiChannelHelper.getChannelsForBand(bucket.band);
        }
    }
    public static ChannelSpec[] getAllChannels(ScanSettings settings) {
        if (settings.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
            ChannelSpec[] channels = new ChannelSpec[settings.channels.length];
            for (int i = 0; i < settings.channels.length; i++) {
                channels[i] = new ChannelSpec(settings.channels[i].frequency);
            }
            return channels;
        } else {
            return WifiChannelHelper.getChannelsForBand(settings.band);
        }
    }
}
