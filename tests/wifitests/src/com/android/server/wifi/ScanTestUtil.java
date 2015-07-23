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

import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.WifiSsid;

import com.android.server.wifi.WifiNative.BucketSettings;

import java.lang.reflect.Field;

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
