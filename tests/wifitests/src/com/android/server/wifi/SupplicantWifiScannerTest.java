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
import static com.android.server.wifi.ScanTestUtil.assertScanDatasEquals;
import static com.android.server.wifi.ScanTestUtil.createFreqSet;
import static com.android.server.wifi.ScanTestUtil.installWlanWifiNative;
import static com.android.server.wifi.ScanTestUtil.setupMockChannels;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.WifiNative;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.SupplicantWifiScannerImpl}.
 */
@SmallTest
public class SupplicantWifiScannerTest {
    @Mock Context context;
    MockAlarmManager alarmManager;
    MockWifiMonitor wifiMonitor;
    MockLooper looper;
    @Mock WifiNative wifiNative;

    SupplicantWifiScannerImpl scanner;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        looper = new MockLooper();
        alarmManager = new MockAlarmManager();
        wifiMonitor = new MockWifiMonitor();

        // Setup WifiNative
        setupMockChannels(wifiNative,
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650});
        when(wifiNative.getInterfaceName()).thenReturn("a_test_interface_name");
        installWlanWifiNative(wifiNative);

        when(context.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(alarmManager.getAlarmManager());

        scanner = new SupplicantWifiScannerImpl(context, WifiNative.getWlanNativeInterface(),
                looper.getLooper());
    }

    @Test
    public void backgroundScanSuccessSingleBucket() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(0, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(1, 2450)},
                    new int[] {2400, 2450})
        };

        doSuccessfulTest(settings, expectedPeriods);
    }

    @Test
    public void backgroundScanMaxApExceeded() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(2)
                .addBucketWithBand(10000,
                        WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN |
                        WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);

        InOrder order = inOrder(eventHandler, wifiNative);

        Set<Integer> requestedFreqs = createFreqSet(2400, 2450);

        // All scans succeed
        when(wifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // Start scan
        scanner.startBatchedScan(settings, eventHandler);

        ArrayList<ScanDetail> nativeResults = new ArrayList<>();
        nativeResults.add(new ScanDetail(WifiSsid.createFromAsciiEncoded("TEST AP 1"),
                        "00:00:00:00:00:00", "", -70, 2450, Long.MAX_VALUE, 0));
        nativeResults.add(new ScanDetail(WifiSsid.createFromAsciiEncoded("TEST AP 2"),
                        "AA:BB:CC:DD:EE:FF", "", -66, 2400, Long.MAX_VALUE, 0));
        nativeResults.add(new ScanDetail(WifiSsid.createFromAsciiEncoded("TEST AP 3"),
                        "00:00:00:00:00:00", "", -80, 2450, Long.MAX_VALUE, 0));
        nativeResults.add(new ScanDetail(WifiSsid.createFromAsciiEncoded("TEST AP 4"),
                        "AA:BB:CC:11:22:33", "", -65, 2450, Long.MAX_VALUE, 0));

        ScanResult[] expectedResults = new ScanResult[] {
            nativeResults.get(3).getScanResult(),
            nativeResults.get(1).getScanResult()
        };
        WifiScanner.ScanData[] expectedScanDatas = new WifiScanner.ScanData[] {
            new WifiScanner.ScanData(0, 0, expectedResults)
        };
        ScanResult[] expectedFullResults = new ScanResult[] {
            nativeResults.get(0).getScanResult(),
            nativeResults.get(1).getScanResult(),
            nativeResults.get(2).getScanResult(),
            nativeResults.get(3).getScanResult()
        };


        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        expectSuccessfulScan(order, eventHandler, requestedFreqs, nativeResults, expectedScanDatas,
                expectedFullResults);
    }

    @Test
    public void backgroundScanSuccessWithFullScanResults() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000,
                        WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN |
                        WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.FULL_AND_RESULT,
                    new ScanResults[] {new ScanResults(0, 2400, 2450, 2400, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.FULL_AND_RESULT,
                    new ScanResults[] {new ScanResults(1, 2450, 2400, 2450, 2400)},
                    new int[] {2400, 2450})
        };

        doSuccessfulTest(settings, expectedPeriods);
    }

    @Test
    public void backgroundScanSuccessWithMixedFullResultsAndNot() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000,
                        WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .addBucketWithBand(20000,
                        WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN |
                        WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT,
                        WifiScanner.WIFI_BAND_5_GHZ)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.FULL_AND_RESULT,
                    new ScanResults[] {new ScanResults(0, 2400, 2450, 2400, 5175)},
                    new int[] {2400, 2450, 5150, 5175}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(1, 2450, 2400, 2450, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.FULL_AND_RESULT,
                    new ScanResults[] {new ScanResults(2, 2450, 2400, 2450, 5150)},
                    new int[] {2400, 2450, 5150, 5175})
        };

        doSuccessfulTest(settings, expectedPeriods);
    }

    @Test
    public void backgroundScanNoBatch() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000,
                        WifiScanner.REPORT_EVENT_NO_BATCH,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.NONE,
                    new ScanResults[] {new ScanResults(0, 2400, 2400, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.NONE,
                    new ScanResults[] {new ScanResults(1, 2400, 2400, 2450)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.NONE,
                    new ScanResults[] {new ScanResults(2, 2400, 2450, 2400)},
                    new int[] {2400, 2450})
        };

        doSuccessfulTest(settings, expectedPeriods);
    }

    @Test
    public void backgroundScanBatch() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .withMaxScansToCache(3)
                .addBucketWithBand(10000,
                        WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.NONE,
                    new ScanResults[] {new ScanResults(0, 2400, 2400, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.NONE,
                    new ScanResults[] {new ScanResults(1, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {
                        new ScanResults(0, 2400, 2400, 2400),
                        new ScanResults(1, 2400),
                        new ScanResults(2, 2450)
                    },
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.NONE,
                    new ScanResults[] {new ScanResults(3, 2400, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.NONE,
                    new ScanResults[] {new ScanResults(4, 2400, 2450)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {
                        new ScanResults(3, 2400, 2400),
                        new ScanResults(4, 2400, 2450),
                        new ScanResults(5, 2450)
                    },
                    new int[] {2400, 2450})
        };

        doSuccessfulTest(settings, expectedPeriods);
    }

    @Test
    public void backgroundScanSuccessWithMultipleBuckets() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .addBucketWithBand(30000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_BOTH)
                .addBucketWithChannels(20000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        5650)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(0, 2400, 5175)},
                    new int[] {2400, 2450, 5150, 5175, 5650}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(1, 2400)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(2, 2450, 5650)},
                    new int[] {2400, 2450, 5650}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(3, 2450, 5175)},
                    new int[] {2400, 2450, 5150, 5175}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(4)},
                    new int[] {2400, 2450, 5650}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(5, 2400, 2400, 2400, 2450)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(6, 5150, 5650, 5650)},
                    new int[] {2400, 2450, 5150, 5175, 5650})
        };

        doSuccessfulTest(settings, expectedPeriods);
    }

    @Test
    public void backgroundScanSuccessWithMultipleBucketsWhereAPeriodDoesNotRequireAScan() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(30000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_BOTH)
                .addBucketWithChannels(20000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        5650)
                .build();

        // expected scan frequencies
        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] { new ScanResults(0, 2400, 5175) },
                    new int[] {2400, 2450, 5150, 5175, 5650}),
            null,
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] { new ScanResults(1, 5650) },
                    new int[] {5650}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] { new ScanResults(2, 2450, 5175) },
                    new int[] {2400, 2450, 5150, 5175}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] { new ScanResults(3, 5650, 5650, 5650) },
                    new int[] {5650}),
            null,
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] { new ScanResults(4, 2400, 2400, 2400, 2450) },
                    new int[] {2400, 2450, 5150, 5175, 5650})
        };

        doSuccessfulTest(settings, expectedPeriods);
    }

    @Test
    public void backgroundScanStartFailed() {
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        Set<Integer> freqs = createFreqSet(2400, 2450); // expected scan frequencies

        InOrder order = inOrder(eventHandler, wifiNative);

        // All scans fail
        when(wifiNative.scan(anyInt(), any(Set.class))).thenReturn(false);

        // Start scan
        scanner.startBatchedScan(settings, eventHandler);

        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        expectFailedScanStart(order, eventHandler, freqs);

        // Fire alarm to start next scan
        dispatchOnlyAlarm();

        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        expectFailedScanStart(order, eventHandler, freqs);

        verifyNoMoreInteractions(eventHandler);
    }


    @Test
    public void backgroundScanEventFailed() {
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        Set<Integer> freqs = createFreqSet(2400, 2450); // expected scan frequencies

        InOrder order = inOrder(eventHandler, wifiNative);

        // All scan starts succeed
        when(wifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // Start scan
        scanner.startBatchedScan(settings, eventHandler);

        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        expectFailedEventScan(order, eventHandler, freqs);

        // Fire alarm to start next scan
        dispatchOnlyAlarm();

        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        expectFailedEventScan(order, eventHandler, freqs);

        verifyNoMoreInteractions(eventHandler);
    }

    /**
     * Run a scan and then pause after the first scan completes, but before the next one starts
     * Then resume the scan
     */
    @Test
    public void pauseWhileWaitingToStartNextScanAndResumeScan() {
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(0, 2400, 2450, 2450)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(1, 2400)},
                    new int[] {2400, 2450})
        };

        InOrder order = inOrder(eventHandler, wifiNative);

        // All scan starts succeed
        when(wifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // Start scan
        scanner.startBatchedScan(settings, eventHandler);

        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        expectSuccessfulScan(order, eventHandler, expectedPeriods[0]);

        // alarm for next period
        assertEquals(1, alarmManager.getPendingCount());

        scanner.pauseBatchedScan();

        // onPause callback (previous results were flushed)
        order.verify(eventHandler).onScanPaused(new WifiScanner.ScanData[0]);

        assertEquals("no pending alarms", 0, alarmManager.getPendingCount());

        scanner.restartBatchedScan();

        // onRestarted callback
        order.verify(eventHandler).onScanRestarted();

        expectSuccessfulScan(order, eventHandler, expectedPeriods[1]);

        verifyNoMoreInteractions(eventHandler);
    }

    /**
     * Run a scan and then pause while the first scan is running
     * Then resume the scan
     */
    @Test
    public void pauseWhileScanningAndResumeScan() {
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(0, 2400, 2450, 2450)},
                    new int[] {2400, 2450}),
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(1, 2400)},
                    new int[] {2400, 2450})
        };

        InOrder order = inOrder(eventHandler, wifiNative);

        // All scan starts succeed
        when(wifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // Start scan
        scanner.startBatchedScan(settings, eventHandler);

        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        // alarm for next period
        assertEquals(1, alarmManager.getPendingCount());

        order.verify(wifiNative).scan(eq(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP),
                eq(expectedPeriods[0].getScanFreqs()));

        scanner.pauseBatchedScan();

        // onPause callback (no pending results)
        order.verify(eventHandler).onScanPaused(new WifiScanner.ScanData[0]);

        assertEquals("no pending alarms", 0, alarmManager.getPendingCount());

        // Setup scan results
        when(wifiNative.getScanResults()).thenReturn(expectedPeriods[0].getResultsToBeDelivered()[0]
                .getScanDetailArrayList());

        // Notify scan has finished
        wifiMonitor.sendMessage(wifiNative.getInterfaceName(), WifiMonitor.SCAN_RESULTS_EVENT);
        assertEquals("dispatch message after results event", 1, looper.dispatchAll());

        // listener should not be notified

        scanner.restartBatchedScan();

        // onRestarted callback
        order.verify(eventHandler).onScanRestarted();

        expectSuccessfulScan(order, eventHandler, expectedPeriods[1]);

        verifyNoMoreInteractions(eventHandler);
    }


    /**
     * Run a scan and then pause after the first scan completes, but before the next one starts
     * Then schedule a new scan while still paused
     */
    @Test
    public void pauseWhileWaitingToStartNextScanAndStartNewScan() {
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);
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

        ScanPeriod[] expectedPeriods = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(0, 2400, 2450, 2450)},
                    new int[] {2400, 2450}),
        };

        ScanPeriod[] expectedPeriods2 = new ScanPeriod[] {
            new ScanPeriod(ScanPeriod.ReportType.RESULT,
                    new ScanResults[] {new ScanResults(1, 5150, 5175, 5175)},
                    new int[] {5150, 5175}),
        };

        InOrder order = inOrder(eventHandler, wifiNative);

        // All scan starts succeed
        when(wifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // Start scan
        scanner.startBatchedScan(settings, eventHandler);

        assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

        expectSuccessfulScan(order, eventHandler, expectedPeriods[0]);

        // alarm for next period
        assertEquals(1, alarmManager.getPendingCount());

        scanner.pauseBatchedScan();

        // onPause callback (previous results were flushed)
        order.verify(eventHandler).onScanPaused(new WifiScanner.ScanData[0]);

        assertEquals("no pending alarms", 0, alarmManager.getPendingCount());

        // Start new scan
        scanner.startBatchedScan(settings2, eventHandler);

        expectSuccessfulScan(order, eventHandler, expectedPeriods2[0]);

        verifyNoMoreInteractions(eventHandler);
    }

    /**
     * Run a test with the given settings where all native scans succeed
     * This will execute expectedPeriods.length scan periods by first
     * starting the scan settings and then dispatching the scan period alarm to start the
     * next scan.
     */
    private void doSuccessfulTest(WifiNative.ScanSettings settings, ScanPeriod[] expectedPeriods) {
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);

        InOrder order = inOrder(eventHandler, wifiNative);

        // All scans succeed
        when(wifiNative.scan(anyInt(), any(Set.class))).thenReturn(true);

        // Start scan
        scanner.startBatchedScan(settings, eventHandler);

        for (int i = 0; i < expectedPeriods.length; ++i) {
            ScanPeriod period = expectedPeriods[i];
            if (period == null) { // no scan should be scheduled
                // alarm for next period
                assertEquals(1, alarmManager.getPendingCount());
            }
            else {
                assertEquals("alarm for next period", 1, alarmManager.getPendingCount());

                expectSuccessfulScan(order, eventHandler, expectedPeriods[i]);
            }
            if (i < expectedPeriods.length - 1) {
                dispatchOnlyAlarm();
            }
        }

        verifyNoMoreInteractions(eventHandler);
    }

    /**
     * Verify the state after a scan was started either through startBatchedScan or
     * dispatching the period alarm.
     */
    private void expectSuccessfulScan(InOrder order, WifiNative.ScanEventHandler eventHandler,
            ScanPeriod period) {
        WifiScanner.ScanData[] scanDatas = null;
        ArrayList<ScanDetail> nativeResults = null;
        ScanResult[] fullResults = null;
        if (period.getResultsToBeDelivered() != null) {
            ScanResults lastPeriodResults = period.getResultsToBeDelivered()
                    [period.getResultsToBeDelivered().length - 1];
            nativeResults = lastPeriodResults.getScanDetailArrayList();
            if (period.expectResults()) {
                scanDatas =
                        new WifiScanner.ScanData[period.getResultsToBeDelivered().length];
                for (int j = 0; j < scanDatas.length; ++j) {
                    scanDatas[j] = period.getResultsToBeDelivered()[j].getScanData();
                }
            }
            if (period.expectFullResults()) {
                fullResults = lastPeriodResults.getRawScanResults();
            }
        }
        expectSuccessfulScan(order, eventHandler, period.getScanFreqs(),
                nativeResults, scanDatas, fullResults);
    }

    /**
     * Verify the state after a scan was started either through startBatchedScan or
     * dispatching the period alarm.
     */
    private void expectSuccessfulScan(InOrder order, WifiNative.ScanEventHandler eventHandler,
            Set<Integer> scanFreqs, ArrayList<ScanDetail> nativeResults,
            WifiScanner.ScanData[] expectedScanResults, ScanResult[] fullResults) {
        // Verify scan started
        order.verify(wifiNative).scan(eq(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP), eq(scanFreqs));

        // Setup scan results
        when(wifiNative.getScanResults()).thenReturn(nativeResults);

        // Notify scan has finished
        wifiMonitor.sendMessage(wifiNative.getInterfaceName(), WifiMonitor.SCAN_RESULTS_EVENT);
        assertEquals("dispatch message after results event", 1, looper.dispatchAll());

        if (fullResults != null) {
            for (ScanResult result : fullResults) {
                order.verify(eventHandler).onFullScanResult(eq(result));
            }
        }

        if (expectedScanResults != null) {
            // Verify scan results delivered
            order.verify(eventHandler).onScanStatus();
            assertScanDatasEquals(expectedScanResults, scanner.getLatestBatchedScanResults(true));
        }
    }

    private void expectFailedScanStart(InOrder order, WifiNative.ScanEventHandler eventHandler,
            Set<Integer> scanFreqs) {
        // Verify scan started
        order.verify(wifiNative).scan(eq(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP), eq(scanFreqs));
    }

    private void expectFailedEventScan(InOrder order, WifiNative.ScanEventHandler eventHandler,
            Set<Integer> scanFreqs) {
        // Verify scan started
        order.verify(wifiNative).scan(eq(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP), eq(scanFreqs));

        // Notify scan has failed
        wifiMonitor.sendMessage(wifiNative.getInterfaceName(), WifiMonitor.SCAN_FAILED_EVENT);
        assertEquals("dispatch message after results event", 1, looper.dispatchAll());
    }

    private void dispatchOnlyAlarm() {
        assertEquals("dispatch only one alarm", 1, alarmManager.dispatchAll());
        assertEquals("dispatch only one message", 1, looper.dispatchAll());
    }

    private static class ScanPeriod {
        enum ReportType {
            NONE(false, false),
            RESULT(true, false),
            FULL_AND_RESULT(true, true),
            FULL(false, true);

            public final boolean result;
            public final boolean full;
            private ReportType(boolean result, boolean full) {
                this.result = result;
                this.full = full;
            }
        };
        private final ReportType mReportType;
        private final ScanResults[] mDeliveredResults;
        private final Set<Integer> mRequestedFreqs;

        public ScanPeriod(ReportType reportType, ScanResults[] deliveredResults, int[] freqs) {
            mReportType = reportType;
            mDeliveredResults = deliveredResults;
            mRequestedFreqs = createFreqSet(freqs);
        }

        public boolean expectResults() {
            return mReportType.result;
        }
        public boolean expectFullResults() {
            return mReportType.full;
        }
        public final ScanResults[] getResultsToBeDelivered() {
            return mDeliveredResults;
        }
        public Set<Integer> getScanFreqs() {
            return mRequestedFreqs;
        }
    }
}
