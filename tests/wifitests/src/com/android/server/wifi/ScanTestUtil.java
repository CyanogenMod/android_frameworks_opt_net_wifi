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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.WifiSsid;

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
        return createRequest(band, period, 0, 0, batch, bssidsPerScan, reportEvents);
    }

    /**
     * Create an exponential back off scan request if maxPeriod != period && maxPeriod != 0.
     */
    public static ScanSettings createRequest(int band, int period, int maxPeriod, int stepCount,
            int batch, int bssidsPerScan, int reportEvents) {
        ScanSettings request = new ScanSettings();
        request.band = band;
        request.channels = null;
        request.periodInMs = period;
        request.maxPeriodInMs = maxPeriod;
        request.stepCount = stepCount;
        request.numBssidsPerScan = bssidsPerScan;
        request.maxScansToCache = batch;
        request.reportEvents = reportEvents;
        return request;
    }

    /**
     * Builder to create WifiNative.ScanSettings objects for testing
     */
    public static class NativeScanSettingsBuilder {
        private final WifiNative.ScanSettings mSettings = new WifiNative.ScanSettings();
        public NativeScanSettingsBuilder() {
            mSettings.buckets = new WifiNative.BucketSettings[0];
            mSettings.num_buckets = 0;
            mSettings.report_threshold_percent = 100;
        }

        public NativeScanSettingsBuilder withBasePeriod(int basePeriod) {
            mSettings.base_period_ms = basePeriod;
            return this;
        }
        public NativeScanSettingsBuilder withMaxApPerScan(int maxAp) {
            mSettings.max_ap_per_scan = maxAp;
            return this;
        }
        public NativeScanSettingsBuilder withMaxScansToCache(int maxScans) {
            mSettings.report_threshold_num_scans = maxScans;
            return this;
        }
        public NativeScanSettingsBuilder withMaxPercentToCache(int percent) {
            mSettings.report_threshold_percent = percent;
            return this;
        }

        public NativeScanSettingsBuilder addBucketWithBand(
                int period, int reportEvents, int band) {
            WifiNative.BucketSettings bucket = new WifiNative.BucketSettings();
            bucket.bucket = mSettings.num_buckets;
            bucket.band = band;
            bucket.period_ms = period;
            bucket.report_events = reportEvents;
            return addBucket(bucket);
        }

        public NativeScanSettingsBuilder addBucketWithChannels(
                int period, int reportEvents, int... channels) {
            return addBucketWithChannels(period, reportEvents, channelsToSpec(channels));
        }

        public NativeScanSettingsBuilder addBucketWithChannels(
                int period, int reportEvents, ChannelSpec... channels) {
            WifiNative.BucketSettings bucket = new WifiNative.BucketSettings();
            bucket.bucket = mSettings.num_buckets;
            bucket.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            bucket.num_channels = channels.length;
            bucket.channels = new WifiNative.ChannelSettings[channels.length];
            for (int i = 0; i < channels.length; ++i) {
                bucket.channels[i] = new WifiNative.ChannelSettings();
                bucket.channels[i].frequency = channels[i].frequency;
            }
            bucket.period_ms = period;
            bucket.report_events = reportEvents;
            return addBucket(bucket);
        }

        public NativeScanSettingsBuilder addBucket(WifiNative.BucketSettings bucket) {
            mSettings.buckets = Arrays.copyOf(mSettings.buckets, mSettings.num_buckets + 1);
            mSettings.buckets[mSettings.num_buckets] = bucket;
            mSettings.num_buckets = mSettings.num_buckets + 1;
            return this;
        }

        public WifiNative.ScanSettings build() {
            return mSettings;
        }
    }

    public static WifiNative.ScanSettings computeSingleScanNativeSettings(
            WifiScanner.ScanSettings requestSettings) {
        return createSingleScanNativeSettings(requestSettings.reportEvents,
                getAllChannels(requestSettings));
    }

    public static WifiNative.ScanSettings createSingleScanNativeSettings(
            int reportEvents, ChannelSpec... channels) {
        // TODO requests using bands  should be scheduled using the band, but are currently
        // converted to channels before scheduling. This should probably change
        return new NativeScanSettingsBuilder()
                .withBasePeriod(0)
                .withMaxApPerScan(0)
                .withMaxPercentToCache(0)
                .withMaxScansToCache(0)
                .addBucketWithChannels(0,
                        reportEvents | WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN, channels)
                .build();
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

    private static void assertScanDataEquals(String prefix, ScanData expected, ScanData actual) {
        assertNotNull(prefix + "expected ScanData was null", expected);
        assertNotNull(prefix + "actual ScanData was null", actual);
        assertEquals(prefix + "id", expected.getId(), actual.getId());
        assertEquals(prefix + "flags", expected.getFlags(), actual.getFlags());
        assertEquals(prefix + "results.length",
                expected.getResults().length, actual.getResults().length);
        for (int j = 0; j < expected.getResults().length; ++j) {
            ScanResult expectedResult = expected.getResults()[j];
            ScanResult actualResult = actual.getResults()[j];
            assertEquals(prefix + "results[" + j + "].SSID",
                    expectedResult.SSID, actualResult.SSID);
            assertEquals(prefix + "results[" + j + "].wifiSsid",
                    expectedResult.wifiSsid.toString(), actualResult.wifiSsid.toString());
            assertEquals(prefix + "results[" + j + "].BSSID",
                    expectedResult.BSSID, actualResult.BSSID);
            assertEquals(prefix + "results[" + j + "].capabilities",
                    expectedResult.capabilities, actualResult.capabilities);
            assertEquals(prefix + "results[" + j + "].level",
                    expectedResult.level, actualResult.level);
            assertEquals(prefix + "results[" + j + "].frequency",
                    expectedResult.frequency, actualResult.frequency);
            assertEquals(prefix + "results[" + j + "].timestamp",
                    expectedResult.timestamp, actualResult.timestamp);
            assertEquals(prefix + "results[" + j + "].seen",
                    expectedResult.seen, actualResult.seen);
        }
    }

    public static void assertScanDataEquals(ScanData expected, ScanData actual) {
        assertScanDataEquals("", expected, actual);
    }

    public static void assertScanDatasEquals(String prefix, ScanData[] expected, ScanData[] actual) {
        assertNotNull("expected " + prefix + "ScanData[] was null", expected);
        assertNotNull("actaul " + prefix + "ScanData[] was null", actual);
        assertEquals(prefix + "ScanData.length", expected.length, actual.length);
        for (int i = 0; i < expected.length; ++i) {
            assertScanDataEquals(prefix + "ScanData[" + i + "].", expected[i], actual[i]);
        }
    }

    public static void assertScanDatasEquals(ScanData[] expected, ScanData[] actual) {
        assertScanDatasEquals("", expected, actual);
    }

    public static WifiScanner.ChannelSpec[] channelsToSpec(int... channels) {
        WifiScanner.ChannelSpec[] channelSpecs = new WifiScanner.ChannelSpec[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            channelSpecs[i] = new WifiScanner.ChannelSpec(channels[i]);
        }
        return channelSpecs;
    }

    public static void assertNativeScanSettingsEquals(WifiNative.ScanSettings expected,
            WifiNative.ScanSettings actual) {
        assertEquals("bssids per scan", expected.max_ap_per_scan, actual.max_ap_per_scan);
        assertEquals("scans to cache", expected.report_threshold_num_scans,
                actual.report_threshold_num_scans);
        assertEquals("percent to cache", expected.report_threshold_percent,
                actual.report_threshold_percent);
        assertEquals("base period", expected.base_period_ms, actual.base_period_ms);

        assertEquals("number of buckets", expected.num_buckets, actual.num_buckets);
        assertNotNull("buckets was null", actual.buckets);
        for (int i = 0; i < expected.buckets.length; ++i) {
            assertNotNull("buckets[" + i + "] was null", actual.buckets[i]);
            assertEquals("buckets[" + i + "].period",
                    expected.buckets[i].period_ms, actual.buckets[i].period_ms);
            assertEquals("buckets[" + i + "].reportEvents",
                    expected.buckets[i].report_events, actual.buckets[i].report_events);

            assertEquals("buckets[" + i + "].band",
                    expected.buckets[i].band, actual.buckets[i].band);
            if (expected.buckets[i].band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
                Set<Integer> expectedChannels = new HashSet<>();
                for (ChannelSpec channel : getAllChannels(expected.buckets[i])) {
                    expectedChannels.add(channel.frequency);
                }
                Set<Integer> actualChannels = new HashSet<>();
                for (ChannelSpec channel : getAllChannels(actual.buckets[i])) {
                    actualChannels.add(channel.frequency);
                }
                assertEquals("channels", expectedChannels, actualChannels);
            } else {
                assertEquals("buckets[" + i + "].num_channels", 0, actual.buckets[i].num_channels);
                assertTrue("buckets[" + i + "].channels",
                        actual.buckets[i].channels == null
                        || actual.buckets[i].channels.length == 0);
            }
        }
    }

    public static ChannelSpec[] getAllChannels(WifiNative.BucketSettings bucket) {
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
