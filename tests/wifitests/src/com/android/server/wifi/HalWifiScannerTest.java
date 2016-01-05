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

import static com.android.server.wifi.ScanTestUtil.NativeScanSettingsBuilder;
import static com.android.server.wifi.ScanTestUtil.assertScanDataEquals;
import static com.android.server.wifi.ScanTestUtil.createFreqSet;
import static com.android.server.wifi.ScanTestUtil.installWlanWifiNative;
import static com.android.server.wifi.ScanTestUtil.setupMockChannels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.HalWifiScannerImpl}.
 */
@SmallTest
@Ignore // TODO enable these tests once HalWifiScannerImpl wifi monitor registration is enabled
public class HalWifiScannerTest {
    MockWifiMonitor mWifiMonitor;
    MockLooper mLooper;
    @Mock WifiNative mWifiNative;

    HalWifiScannerImpl mScanner;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mLooper = new MockLooper();
        mWifiMonitor = new MockWifiMonitor();

        // Setup WifiNative
        setupMockChannels(mWifiNative,
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650});
        when(mWifiNative.getInterfaceName()).thenReturn("a_test_interface_name");
        installWlanWifiNative(mWifiNative);

        mScanner = new HalWifiScannerImpl(WifiNative.getWlanNativeInterface(), mLooper.getLooper());
    }

    @Test
    public void singleScanSuccess() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        doSuccessfulSingleTest(settings, createFreqSet(2400, 2450),
                ScanResults.create(0, 2400, 2450, 2455), false);
    }

    @Test
    public void singleScanSuccessWithChannels() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithChannels(20000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        5650)
                .build();

        doSuccessfulSingleTest(settings, createFreqSet(5650),
                ScanResults.create(0, 5650, 5650), false);
    }

    @Test
    public void singleScanSuccessWithFullResults() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000,
                        WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN
                        | WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        doSuccessfulSingleTest(settings, createFreqSet(2400, 2450),
                ScanResults.create(0, 2400, 2450, 2455), true);
    }

    @Test
    public void singleScanFailure() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);

        ScanResults results = ScanResults.create(0, 2400, 2450, 2455);
        Set<Integer> expectedScan = createFreqSet(2400, 2450);

        InOrder order = inOrder(eventHandler, mWifiNative);

        // scan fails
        when(mWifiNative.scan(anyInt(), any(Set.class))).thenReturn(false);

        // start scan
        assertFalse(mScanner.startSingleScan(settings, eventHandler));

        // TODO expect failure callback once implemented

        verifyNoMoreInteractions(eventHandler);
    }

    @Test
    public void multipleSingleScanSuccess() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();
        WifiNative.ScanSettings settings2 = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_5_GHZ)
                .build();

        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);
        InOrder order = inOrder(eventHandler, mWifiNative);

        // scans succeed
        when(mWifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // start first scan
        assertTrue(mScanner.startSingleScan(settings, eventHandler));

        expectSuccessfulSingleScan(order, eventHandler, createFreqSet(2400, 2450),
                ScanResults.create(0, 2400, 2450, 2455), false);

        // start second scan
        assertTrue(mScanner.startSingleScan(settings2, eventHandler));

        expectSuccessfulSingleScan(order, eventHandler, createFreqSet(5150, 5175),
                ScanResults.create(0, 5150, 5175), false);

        verifyNoMoreInteractions(eventHandler);
    }

    private void doSuccessfulSingleTest(WifiNative.ScanSettings settings, Set<Integer> expectedScan,
            ScanResults results, boolean expectFullResults) {
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);

        InOrder order = inOrder(eventHandler, mWifiNative);

        // scan succeeds
        when(mWifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // start scan
        assertTrue(mScanner.startSingleScan(settings, eventHandler));

        expectSuccessfulSingleScan(order, eventHandler, expectedScan, results, expectFullResults);

        verifyNoMoreInteractions(eventHandler);
    }

    private void expectSuccessfulSingleScan(InOrder order, WifiNative.ScanEventHandler eventHandler,
            Set<Integer> expectedScan, ScanResults results, boolean expectFullResults) {
        order.verify(mWifiNative).scan(eq(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP),
                eq(expectedScan));

        when(mWifiNative.getScanResults()).thenReturn(results.getScanDetailArrayList());

        // Notify scan has finished
        mWifiMonitor.sendMessage(mWifiNative.getInterfaceName(), WifiMonitor.SCAN_RESULTS_EVENT);

        mLooper.dispatchAll();

        if (expectFullResults) {
            for (ScanResult result : results.getRawScanResults()) {
                order.verify(eventHandler).onFullScanResult(eq(result));
            }
        }

        order.verify(eventHandler).onScanResultsAvailable();
        assertScanDataEquals(results.getScanData(), mScanner.getLatestSingleScanResults());
    }
}
