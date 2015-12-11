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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiSsid;

import com.android.server.wifi.ScanDetail;

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
    public ScanResults(int id, int... freqs) {
        mScanResults = new ScanResult[freqs.length];
        Random r = new Random(id);
        for (int i = 0; i < freqs.length; ++i) {
            int freq = freqs[i];
            String ssid = new BigInteger(128, r).toString(36);
            int rssi = r.nextInt(40) - 99; // -99 to -60
            ScanDetail detail = new ScanDetail(WifiSsid.createFromAsciiEncoded(ssid),
                    generateBssid(r), "", rssi, freq,
                    Long.MAX_VALUE /* needed so that scan results aren't rejected because
                                      there older than scan start */,
                    r.nextLong());

            mScanDetails.add(detail);
            mScanResults[i] = detail.getScanResult();
        }
        ScanResult[] sortedScanResults = Arrays.copyOf(mScanResults, mScanResults.length);
        Arrays.sort(sortedScanResults, SCAN_RESULT_RSSI_COMPARATOR);
        mScanData = new ScanData(id, 0, sortedScanResults);
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
