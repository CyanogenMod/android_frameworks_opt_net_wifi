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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiSsid;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * Utility for creating scan results from a scan
 */
public class ScanResults {
    private final ArrayList<ScanDetail> mScanDetails = new ArrayList<>();
    private final ScanData mScanData;
    private final ScanResult[] mScanResults;

    private static String generateBssid(Random r) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                r.nextInt(256), r.nextInt(256), r.nextInt(256),
                r.nextInt(256), r.nextInt(256), r.nextInt(256));
    }

    private static final Comparator<ScanResult> SCAN_RESULT_RSSI_COMPARATOR =
            new Comparator<ScanResult>() {
        public int compare(ScanResult r1, ScanResult r2) {
            return r2.level - r1.level;
        }
    };

    private static ScanDetail[] generateNativeResults(int seed, int... freqs) {
        ScanDetail[] results = new ScanDetail[freqs.length];
        Random r = new Random(seed);
        for (int i = 0; i < freqs.length; ++i) {
            int freq = freqs[i];
            String ssid = new BigInteger(128, r).toString(36);
            int rssi = r.nextInt(40) - 99; // -99 to -60
            results[i] = new ScanDetail(WifiSsid.createFromAsciiEncoded(ssid),
                    generateBssid(r), "", rssi, freq,
                    Long.MAX_VALUE /* needed so that scan results aren't rejected because
                                      there older than scan start */,
                    r.nextLong());
        }
        return results;
    }

    public static ScanResults create(int id, int... freqs) {
        return new ScanResults(id, -1, generateNativeResults(id, freqs));
    }

    public static ScanResults create(int id, ScanDetail... nativeResults) {
        return new ScanResults(id, -1, nativeResults);
    }

    /**
     * Create scan results that contain all results for the native results and
     * full scan results, but limits the number of onResults results after sorting
     * by RSSI
     */
    public static ScanResults createOverflowing(int id, int maxResults,
            ScanDetail... nativeResults) {
        return new ScanResults(id, maxResults, nativeResults);
    }

    private ScanResults(int id, int maxResults, ScanDetail... nativeResults) {
        mScanResults = new ScanResult[nativeResults.length];
        for (int i = 0; i < nativeResults.length; ++i) {
            mScanDetails.add(nativeResults[i]);
            mScanResults[i] = nativeResults[i].getScanResult();
        }
        ScanResult[] sortedScanResults = Arrays.copyOf(mScanResults, mScanResults.length);
        Arrays.sort(sortedScanResults, SCAN_RESULT_RSSI_COMPARATOR);
        if (maxResults == -1) {
            mScanData = new ScanData(id, 0, sortedScanResults);
        } else {
            ScanResult[] reducedScanResults = Arrays.copyOf(sortedScanResults,
                    Math.min(sortedScanResults.length, maxResults));
            mScanData = new ScanData(id, 0, reducedScanResults);
        }
    }

    public ArrayList<ScanDetail> getScanDetailArrayList() {
        return mScanDetails;
    }

    public ScanData getScanData() {
        return mScanData;
    }

    public ScanResult[] getRawScanResults() {
        return mScanResults;
    }
}
