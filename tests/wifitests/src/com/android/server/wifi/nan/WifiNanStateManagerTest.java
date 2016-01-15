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
 * limitations under the License.
 */

package com.android.server.wifi.nan;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventListener;
import android.net.wifi.nan.IWifiNanSessionListener;
import android.net.wifi.nan.PublishData;
import android.net.wifi.nan.PublishSettings;
import android.net.wifi.nan.SubscribeData;
import android.net.wifi.nan.SubscribeSettings;
import android.net.wifi.nan.WifiNanEventListener;
import android.net.wifi.nan.WifiNanSessionListener;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.MockLooper;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Unit test harness for WifiNanStateManager.
 */
@SmallTest
public class WifiNanStateManagerTest {
    private MockLooper mMockLooper;
    private WifiNanStateManager mDut;
    @Mock private WifiNanNative mMockNative;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mMockLooper = new MockLooper();

        mDut = installNewNanStateManager();
        mDut.start(mMockLooper.getLooper());

        installMockWifiNanNative(mMockNative);
    }

    @Test
    public void testNanEventsDelivered() throws Exception {
        final short transactionId = 1024;
        final int uid = 1005;
        final int reason = WifiNanSessionListener.FAIL_REASON_NO_RESOURCES;
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);

        IWifiNanEventListener mockListener = mock(IWifiNanEventListener.class);
        InOrder inOrder = inOrder(mockListener);

        mDut.connect(uid, mockListener,
                WifiNanEventListener.LISTEN_CONFIG_COMPLETED
                        | WifiNanEventListener.LISTEN_CONFIG_FAILED
                        | WifiNanEventListener.LISTEN_IDENTITY_CHANGED
                        | WifiNanEventListener.LISTEN_NAN_DOWN);
        mDut.onClusterChange(WifiNanClientState.CLUSTER_CHANGE_EVENT_STARTED, someMac);
        mDut.onConfigCompleted(transactionId);
        mDut.onConfigFailed(reason);
        mDut.onInterfaceAddressChange(someMac);
        mDut.onNanDown(reason);
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onIdentityChanged();
        inOrder.verify(mockListener).onConfigCompleted(any(ConfigRequest.class));
        inOrder.verify(mockListener).onConfigFailed(reason);
        inOrder.verify(mockListener).onIdentityChanged();
        inOrder.verify(mockListener).onNanDown(reason);
        verifyNoMoreInteractions(mockListener);
        verifyZeroInteractions(mMockNative);
    }

    @Test
    public void testNanEventsNotDelivered() throws Exception {
        final short transactionId = 1024;
        final int uid = 1005;
        final int reason = WifiNanSessionListener.FAIL_REASON_NO_RESOURCES;
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);

        IWifiNanEventListener mockListener = mock(IWifiNanEventListener.class);
        InOrder inOrder = inOrder(mockListener);

        mDut.connect(uid, mockListener, 0);
        mDut.onClusterChange(WifiNanClientState.CLUSTER_CHANGE_EVENT_JOINED, someMac);
        mDut.onConfigCompleted(transactionId);
        mDut.onConfigFailed(reason);
        mDut.onInterfaceAddressChange(someMac);
        mDut.onNanDown(reason);
        mMockLooper.dispatchAll();

        verifyZeroInteractions(mockListener, mMockNative);
    }

    @Test
    public void testPublish() throws Exception {
        final int uid = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 7;
        final int reasonFail = WifiNanSessionListener.FAIL_REASON_NO_RESOURCES;
        final int reasonTerminate = WifiNanSessionListener.TERMINATE_REASON_DONE;
        final int publishId = 15;

        PublishData.Builder dataBuilder = new PublishData.Builder();
        dataBuilder.setServiceName(serviceName).setServiceSpecificInfo(ssi);
        PublishData publishData = dataBuilder.build();

        PublishSettings.Builder settingsBuilder = new PublishSettings.Builder();
        settingsBuilder.setPublishType(PublishSettings.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount);
        PublishSettings publishSettings = settingsBuilder.build();

        IWifiNanSessionListener mockListener = mock(IWifiNanSessionListener.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockListener, mMockNative);

        int allEvents = WifiNanSessionListener.LISTEN_PUBLISH_FAIL
                | WifiNanSessionListener.LISTEN_PUBLISH_TERMINATED
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_FAIL
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_TERMINATED
                | WifiNanSessionListener.LISTEN_MATCH
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_SUCCESS
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_FAIL
                | WifiNanSessionListener.LISTEN_MESSAGE_RECEIVED;

        mDut.connect(uid, null, 0);
        mDut.createSession(uid, sessionId, mockListener, allEvents);

        // publish - fail
        mDut.publish(uid, sessionId, publishData, publishSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishData),
                eq(publishSettings));

        mDut.onPublishFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onPublishFail(reasonFail);

        // publish - success/terminate
        mDut.publish(uid, sessionId, publishData, publishSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishData),
                eq(publishSettings));

        mDut.onPublishSuccess(transactionId.getValue(), publishId);
        mDut.onPublishTerminated(publishId, reasonTerminate);
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onPublishTerminated(reasonTerminate);

        // re-publish
        mDut.publish(uid, sessionId, publishData, publishSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishData),
                eq(publishSettings));

        mDut.onPublishSuccess(transactionId.getValue(), publishId);
        mDut.publish(uid, sessionId, publishData, publishSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(publishId),
                eq(publishData), eq(publishSettings));
        verifyNoMoreInteractions(mockListener, mMockNative);
    }

    @Test
    public void testPublishNoCallbacks() throws Exception {
        final int uid = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 7;
        final int reasonFail = WifiNanSessionListener.FAIL_REASON_NO_RESOURCES;
        final int reasonTerminate = WifiNanSessionListener.TERMINATE_REASON_DONE;
        final int publishId = 15;

        PublishData.Builder dataBuilder = new PublishData.Builder();
        dataBuilder.setServiceName(serviceName).setServiceSpecificInfo(ssi);
        PublishData publishData = dataBuilder.build();

        PublishSettings.Builder settingsBuilder = new PublishSettings.Builder();
        settingsBuilder.setPublishType(PublishSettings.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount);
        PublishSettings publishSettings = settingsBuilder.build();

        IWifiNanSessionListener mockListener = mock(IWifiNanSessionListener.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockListener, mMockNative);

        int allEvents = WifiNanSessionListener.LISTEN_PUBLISH_FAIL
                | WifiNanSessionListener.LISTEN_PUBLISH_TERMINATED
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_FAIL
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_TERMINATED
                | WifiNanSessionListener.LISTEN_MATCH
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_SUCCESS
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_FAIL
                | WifiNanSessionListener.LISTEN_MESSAGE_RECEIVED;

        mDut.connect(uid, null, 0);
        mDut.createSession(uid, sessionId, mockListener,
                allEvents & ~WifiNanSessionListener.LISTEN_PUBLISH_FAIL
                        & ~WifiNanSessionListener.LISTEN_PUBLISH_TERMINATED);

        // publish - fail
        mDut.publish(uid, sessionId, publishData, publishSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishData),
                eq(publishSettings));

        mDut.onPublishFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        // publish - success/terminate
        mDut.publish(uid, sessionId, publishData, publishSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishData),
                eq(publishSettings));

        mDut.onPublishSuccess(transactionId.getValue(), publishId);
        mDut.onPublishTerminated(publishId, reasonTerminate);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockListener, mMockNative);
    }

    @Test
    public void testSubscribe() throws Exception {
        final int uid = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int reasonFail = WifiNanSessionListener.FAIL_REASON_NO_RESOURCES;
        final int reasonTerminate = WifiNanSessionListener.TERMINATE_REASON_DONE;
        final int subscribeId = 15;

        SubscribeData.Builder dataBuilder = new SubscribeData.Builder();
        dataBuilder.setServiceName(serviceName).setServiceSpecificInfo(ssi);
        SubscribeData subscribeData = dataBuilder.build();

        SubscribeSettings.Builder settingsBuilder = new SubscribeSettings.Builder();
        settingsBuilder.setSubscribeType(SubscribeSettings.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount);
        SubscribeSettings subscribeSettings = settingsBuilder.build();

        IWifiNanSessionListener mockListener = mock(IWifiNanSessionListener.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockListener, mMockNative);

        int allEvents = WifiNanSessionListener.LISTEN_PUBLISH_FAIL
                | WifiNanSessionListener.LISTEN_PUBLISH_TERMINATED
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_FAIL
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_TERMINATED
                | WifiNanSessionListener.LISTEN_MATCH
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_SUCCESS
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_FAIL
                | WifiNanSessionListener.LISTEN_MESSAGE_RECEIVED;

        mDut.connect(uid, null, 0);
        mDut.createSession(uid, sessionId, mockListener, allEvents);

        // subscribe - fail
        mDut.subscribe(uid, sessionId, subscribeData, subscribeSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeData),
                eq(subscribeSettings));

        mDut.onSubscribeFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onSubscribeFail(reasonFail);

        // subscribe - success/terminate
        mDut.subscribe(uid, sessionId, subscribeData, subscribeSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeData),
                eq(subscribeSettings));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId);
        mDut.onSubscribeTerminated(subscribeId, reasonTerminate);
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onSubscribeTerminated(reasonTerminate);

        // re-subscribe
        mDut.subscribe(uid, sessionId, subscribeData, subscribeSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeData),
                eq(subscribeSettings));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId);
        mDut.subscribe(uid, sessionId, subscribeData, subscribeSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(subscribeId),
                eq(subscribeData), eq(subscribeSettings));
        verifyNoMoreInteractions(mockListener, mMockNative);
    }

    @Test
    public void testSubscribeNoCallbacks() throws Exception {
        final int uid = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int reasonFail = WifiNanSessionListener.FAIL_REASON_NO_RESOURCES;
        final int reasonTerminate = WifiNanSessionListener.TERMINATE_REASON_DONE;
        final int subscribeId = 15;

        SubscribeData.Builder dataBuilder = new SubscribeData.Builder();
        dataBuilder.setServiceName(serviceName).setServiceSpecificInfo(ssi);
        SubscribeData subscribeData = dataBuilder.build();

        SubscribeSettings.Builder settingsBuilder = new SubscribeSettings.Builder();
        settingsBuilder.setSubscribeType(SubscribeSettings.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount);
        SubscribeSettings subscribeSettings = settingsBuilder.build();

        IWifiNanSessionListener mockListener = mock(IWifiNanSessionListener.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockListener, mMockNative);

        int allEvents = WifiNanSessionListener.LISTEN_PUBLISH_FAIL
                | WifiNanSessionListener.LISTEN_PUBLISH_TERMINATED
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_FAIL
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_TERMINATED
                | WifiNanSessionListener.LISTEN_MATCH
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_SUCCESS
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_FAIL
                | WifiNanSessionListener.LISTEN_MESSAGE_RECEIVED;

        mDut.connect(uid, null, 0);
        mDut.createSession(uid, sessionId, mockListener,
                allEvents & ~WifiNanSessionListener.LISTEN_SUBSCRIBE_FAIL
                        & ~WifiNanSessionListener.LISTEN_SUBSCRIBE_TERMINATED);

        // subscribe - fail
        mDut.subscribe(uid, sessionId, subscribeData, subscribeSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeData),
                eq(subscribeSettings));

        mDut.onSubscribeFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        // subscribe - success/terminate
        mDut.subscribe(uid, sessionId, subscribeData, subscribeSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeData),
                eq(subscribeSettings));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId);
        mDut.onSubscribeTerminated(subscribeId, reasonTerminate);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockListener, mMockNative);
    }

    @Test
    public void testMatchAndMessages() throws Exception {
        final int uid = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int reasonFail = WifiNanSessionListener.FAIL_REASON_NO_RESOURCES;
        final int reasonTerminate = WifiNanSessionListener.TERMINATE_REASON_DONE;
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final String peerMsg = "some message from peer";

        SubscribeData.Builder dataBuilder = new SubscribeData.Builder();
        dataBuilder.setServiceName(serviceName).setServiceSpecificInfo(ssi);
        SubscribeData subscribeData = dataBuilder.build();

        SubscribeSettings.Builder settingsBuilder = new SubscribeSettings.Builder();
        settingsBuilder.setSubscribeType(SubscribeSettings.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount);
        SubscribeSettings subscribeSettings = settingsBuilder.build();

        IWifiNanSessionListener mockListener = mock(IWifiNanSessionListener.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockListener, mMockNative);

        int allEvents = WifiNanSessionListener.LISTEN_PUBLISH_FAIL
                | WifiNanSessionListener.LISTEN_PUBLISH_TERMINATED
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_FAIL
                | WifiNanSessionListener.LISTEN_SUBSCRIBE_TERMINATED
                | WifiNanSessionListener.LISTEN_MATCH
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_SUCCESS
                | WifiNanSessionListener.LISTEN_MESSAGE_SEND_FAIL
                | WifiNanSessionListener.LISTEN_MESSAGE_RECEIVED;

        mDut.connect(uid, null, 0);
        mDut.createSession(uid, sessionId, mockListener, allEvents);
        mDut.subscribe(uid, sessionId, subscribeData, subscribeSettings);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeData),
                eq(subscribeSettings));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId);
        mDut.onMatch(subscribeId, requestorId, peerMac, peerSsi.getBytes(), peerSsi.length(),
                peerMatchFilter.getBytes(), peerMatchFilter.length());
        mDut.onMessageReceived(subscribeId, requestorId, peerMac, peerMsg.getBytes(),
                peerMsg.length());
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onMatch(requestorId, peerSsi.getBytes(), peerSsi.length(),
                peerMatchFilter.getBytes(), peerMatchFilter.length());
        inOrder.verify(mockListener).onMessageReceived(requestorId, peerMsg.getBytes(),
                peerMsg.length());

        mDut.sendMessage(uid, sessionId, requestorId, ssi.getBytes(), ssi.length());
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()));

        mDut.onMessageSendFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onMessageSendFail(reasonFail);

        mDut.sendMessage(uid, sessionId, requestorId, ssi.getBytes(), ssi.length());
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()));

        mDut.onMessageSendSuccess(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener).onMessageSendSuccess();

        verifyNoMoreInteractions(mockListener, mMockNative);
    }

    @Test
    public void testConfigs() throws Exception {
        final int uid1 = 9999;
        final int clusterLow1 = 5;
        final int clusterHigh1 = 100;
        final int masterPref1 = 111;
        final int uid2 = 1001;
        final boolean support5g2 = true;
        final int clusterLow2 = 7;
        final int clusterHigh2 = 155;
        final int masterPref2 = 0;
        final int uid3 = 55;

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<ConfigRequest> crCapture = ArgumentCaptor.forClass(ConfigRequest.class);

        ConfigRequest.Builder builder1 = new ConfigRequest.Builder();
        builder1.setClusterLow(clusterLow1).setClusterHigh(clusterHigh1)
                .setMasterPreference(masterPref1);
        ConfigRequest configRequest1 = builder1.build();

        ConfigRequest.Builder builder2 = new ConfigRequest.Builder();
        builder2.setSupport5gBand(support5g2).setClusterLow(clusterLow2)
                .setClusterHigh(clusterHigh2).setMasterPreference(masterPref2);
        ConfigRequest configRequest2 = builder2.build();

        ConfigRequest.Builder builder3 = new ConfigRequest.Builder();
        ConfigRequest configRequest3 = builder3.build();

        IWifiNanEventListener mockListener1 = mock(IWifiNanEventListener.class);
        IWifiNanEventListener mockListener2 = mock(IWifiNanEventListener.class);
        IWifiNanEventListener mockListener3 = mock(IWifiNanEventListener.class);

        InOrder inOrder = inOrder(mMockNative, mockListener1, mockListener2, mockListener3);

        mDut.connect(uid1, mockListener1, WifiNanEventListener.LISTEN_CONFIG_COMPLETED);
        mDut.requestConfig(uid1, configRequest1);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 0", configRequest1, equalTo(crCapture.getValue()));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener1).onConfigCompleted(configRequest1);

        mDut.connect(uid2, mockListener2, WifiNanEventListener.LISTEN_CONFIG_COMPLETED);
        mDut.requestConfig(uid2, configRequest2);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 1: support 5g", crCapture.getValue().mSupport5gBand,
                equalTo(true));
        collector.checkThat("merge: stage 1: master pref", crCapture.getValue().mMasterPreference,
                equalTo(Math.max(masterPref1, masterPref2)));
        collector.checkThat("merge: stage 1: cluster low", crCapture.getValue().mClusterLow,
                equalTo(Math.min(clusterLow1, clusterLow2)));
        collector.checkThat("merge: stage 1: cluster high", crCapture.getValue().mClusterHigh,
                equalTo(Math.max(clusterHigh1, clusterHigh2)));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener1).onConfigCompleted(crCapture.getValue());

        mDut.connect(uid3, mockListener3, WifiNanEventListener.LISTEN_CONFIG_COMPLETED);
        mDut.requestConfig(uid3, configRequest3);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 2: support 5g", crCapture.getValue().mSupport5gBand,
                equalTo(true));
        collector.checkThat("merge: stage 2: master pref", crCapture.getValue().mMasterPreference,
                equalTo(Math.max(masterPref1, masterPref2)));
        collector.checkThat("merge: stage 2: cluster low", crCapture.getValue().mClusterLow,
                equalTo(Math.min(clusterLow1, clusterLow2)));
        collector.checkThat("merge: stage 2: cluster high", crCapture.getValue().mClusterHigh,
                equalTo(Math.max(clusterHigh1, clusterHigh2)));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener1).onConfigCompleted(crCapture.getValue());

        mDut.disconnect(uid2);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 3", configRequest1, equalTo(crCapture.getValue()));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener1).onConfigCompleted(crCapture.getValue());

        mDut.disconnect(uid1);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 4", configRequest3, equalTo(crCapture.getValue()));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockListener3).onConfigCompleted(crCapture.getValue());

        mDut.disconnect(uid3);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).disable(anyShort());

        verifyNoMoreInteractions(mMockNative);
    }

    /*
     * Utilities
     */

    private static WifiNanStateManager installNewNanStateManager() throws Exception {
        Constructor<WifiNanStateManager> ctr = WifiNanStateManager.class.getDeclaredConstructor();
        ctr.setAccessible(true);
        WifiNanStateManager nanStateManager = ctr.newInstance();

        Field field = WifiNanStateManager.class.getDeclaredField("sNanStatemanagerSingleton");
        field.setAccessible(true);
        field.set(null, nanStateManager);

        return WifiNanStateManager.getInstance();
    }

    private static void installMockWifiNanNative(WifiNanNative obj) throws Exception {
        Field field = WifiNanNative.class.getDeclaredField("sWifiNanNativeSingleton");
        field.setAccessible(true);
        field.set(null, obj);
    }
}
