/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.server.wifi.ScanTestUtil.createRequest;
import static com.android.server.wifi.ScanTestUtil.installWlanWifiNative;
import static com.android.server.wifi.ScanTestUtil.setupMockChannels;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Protocol;
import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiScanningServiceImpl}.
 */
@SmallTest
public class WifiScanningServiceTest {
    public static final String TAG = "WifiScanningServiceTest";

    @Mock Context mContext;
    @Mock WifiNative mWifiNative;
    @Mock WifiScannerImpl mWifiScannerImpl;
    @Mock WifiScannerImpl.WifiScannerImplFactory mWifiScannerImplFactory;
    @Mock IBatteryStats mBatteryStats;
    MockLooper mLooper;
    WifiScanningServiceImpl mWifiScanningServiceImpl;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        setupMockChannels(mWifiNative,
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650, 5660});
        installWlanWifiNative(mWifiNative);

        mLooper = new MockLooper();
        when(mWifiScannerImplFactory.create(any(Context.class), any(Looper.class)))
                .thenReturn(mWifiScannerImpl);
        mWifiScanningServiceImpl = new WifiScanningServiceImpl(mContext, mLooper.getLooper(),
                mWifiScannerImplFactory, mBatteryStats);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
    }


    ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);

    private void sendWifiScanAvailable(int scanAvailable) {
        Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
        intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, scanAvailable);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
    }

    private WifiScanner.ScanSettings generateValidScanSettings() {
        return createRequest(WifiScanner.WIFI_BAND_BOTH, 30000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
    }

    private BidirectionalAsyncChannel connectChannel(Handler handler) {
        BidirectionalAsyncChannel controlChannel = new BidirectionalAsyncChannel();
        controlChannel.connect(mLooper.getLooper(), mWifiScanningServiceImpl.getMessenger(),
                handler);
        mLooper.dispatchAll();
        controlChannel.assertConnected();
        return controlChannel;
    }

    private Message sendRequest(BidirectionalAsyncChannel controlChannel, Handler handler,
            Message msg) {
        controlChannel.sendMessage(msg);
        mLooper.dispatchAll();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(handler).handleMessage(messageCaptor.capture());
        return messageCaptor.getValue();
    }

    private void sendAndAssertSuccessfulyBackgroundScan(BidirectionalAsyncChannel controlChannel,
            Handler handler, int scanRequestId, WifiScanner.ScanSettings settings) {
        Message response = sendRequest(controlChannel, handler,
                Message.obtain(null, WifiScanner.CMD_START_BACKGROUND_SCAN, 0, scanRequestId,
                        settings));
        assertEquals("response.what", WifiScanner.CMD_OP_SUCCEEDED, response.what);
        assertEquals("response.arg2", scanRequestId, response.arg2);
        assertEquals("response.obj", null, response.obj);
    }

    private void sendAndAssertFailedBackgroundScan(BidirectionalAsyncChannel controlChannel,
            Handler handler, int scanRequestId, WifiScanner.ScanSettings settings,
            int expectedErrorReason, String expectedErrorDescription) {
        Message response = sendRequest(controlChannel, handler,
                Message.obtain(null, WifiScanner.CMD_START_BACKGROUND_SCAN, 0, scanRequestId,
                        settings));
        assertFailedResponse(scanRequestId, expectedErrorReason, expectedErrorDescription,
                response);
    }

    private void assertFailedResponse(int arg2, int expectedErrorReason,
            String expectedErrorDescription, Message response) {
        assertEquals("response.what", WifiScanner.CMD_OP_FAILED, response.what);
        assertEquals("response.arg2", arg2, response.arg2);
        assertEquals("response.obj.reason",
                expectedErrorReason, ((WifiScanner.OperationResult) response.obj).reason);
        assertEquals("response.obj.description",
                expectedErrorDescription, ((WifiScanner.OperationResult) response.obj).description);
    }

    private void startServiceAndLoadDriver() {
        mWifiScanningServiceImpl.startService();
        when(mWifiScannerImpl.getScanCapabilities(any(WifiNative.ScanCapabilities.class)))
                .thenAnswer(new AnswerWithArguments() {
                        public boolean answer(WifiNative.ScanCapabilities capabilities) {
                            capabilities.max_scan_cache_size = Integer.MAX_VALUE;
                            capabilities.max_scan_buckets = 8;
                            capabilities.max_ap_cache_per_scan = 16;
                            capabilities.max_rssi_sample_size = 8;
                            capabilities.max_scan_reporting_threshold = 10;
                            capabilities.max_hotlist_bssids = 0;
                            capabilities.max_significant_wifi_change_aps = 0;
                            return true;
                        }
                    });
        verify(mContext)
                .registerReceiver(mBroadcastReceiverCaptor.capture(), any(IntentFilter.class));
        sendWifiScanAvailable(WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();
    }

    @Test
    public void construct() throws Exception {
        verifyNoMoreInteractions(mContext, mWifiScannerImpl, mWifiScannerImpl,
                mWifiScannerImplFactory, mBatteryStats);
    }

    @Test
    public void startService() throws Exception {
        mWifiScanningServiceImpl.startService();
        verifyNoMoreInteractions(mWifiScannerImplFactory);

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        sendAndAssertFailedBackgroundScan(controlChannel, handler, 122, generateValidScanSettings(),
                WifiScanner.REASON_UNSPECIFIED, "not available");
    }

    @Test
    public void loadDriver() throws Exception {
        startServiceAndLoadDriver();
        verify(mWifiScannerImplFactory, times(1)).create(any(Context.class), any(Looper.class));

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        when(mWifiScannerImpl.startBatchedScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);
        sendAndAssertSuccessfulyBackgroundScan(controlChannel, handler, 192,
                generateValidScanSettings());
    }

    @Test
    public void sendInvalidCommand() throws Exception {
        startServiceAndLoadDriver();

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        Message response = sendRequest(controlChannel, handler,
                Message.obtain(null, Protocol.BASE_WIFI_MANAGER));
        assertFailedResponse(0, WifiScanner.REASON_INVALID_REQUEST, "Invalid request", response);
    }

}
