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

import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import android.os.Handler;
import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockitoAnnotations;

/**
 * Test MockLooperAbstractTime which provides control over "time". Note that
 * real-time is being used as well. Therefore small time increments are NOT
 * reliable. All tests are in "K" units (i.e. *1000).
 */

@SmallTest
public class MockLooperTest {
    private MockLooper mMockLooper;
    private Handler mHandler;
    private Handler mHandlerSpy;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mMockLooper = new MockLooper();
        mHandler = new Handler(mMockLooper.getLooper());
        mHandlerSpy = spy(mHandler);
    }

    /**
     * Basic test with no time stamps: dispatch 4 messages, check that all 4
     * delivered (in correct order).
     */
    @Test
    public void testNoTimeMovement() {
        final int messageA = 1;
        final int messageB = 2;
        final int messageC = 3;

        InOrder inOrder = inOrder(mHandlerSpy);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageA));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageA));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageB));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageC));
        mMockLooper.dispatchAll();

        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("1: messageA", messageA, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("2: messageA", messageA, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("3: messageB", messageB, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("4: messageC", messageC, equalTo(messageCaptor.getValue().what));

        inOrder.verify(mHandlerSpy, never()).handleMessage(any(Message.class));
    }

    /**
     * Test message sequence: A, B, C@5K, A@10K. Don't move time.
     * <p>
     * Expected: only get A, B
     */
    @Test
    public void testDelayedDispatchNoTimeMove() {
        final int messageA = 1;
        final int messageB = 2;
        final int messageC = 3;

        InOrder inOrder = inOrder(mHandlerSpy);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageA));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageB));
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageC), 5000);
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageA), 10000);
        mMockLooper.dispatchAll();

        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("1: messageA", messageA, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("2: messageB", messageB, equalTo(messageCaptor.getValue().what));

        inOrder.verify(mHandlerSpy, never()).handleMessage(any(Message.class));
    }

    /**
     * Test message sequence: A, B, C@5K, A@10K, Advance time by 5K.
     * <p>
     * Expected: only get A, B, C
     */
    @Test
    public void testDelayedDispatchAdvanceTimeOnce() {
        final int messageA = 1;
        final int messageB = 2;
        final int messageC = 3;

        InOrder inOrder = inOrder(mHandlerSpy);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageA));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageB));
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageC), 5000);
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageA), 10000);
        mMockLooper.moveTimeForward(5000);
        mMockLooper.dispatchAll();

        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("1: messageA", messageA, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("2: messageB", messageB, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("3: messageC", messageC, equalTo(messageCaptor.getValue().what));

        inOrder.verify(mHandlerSpy, never()).handleMessage(any(Message.class));
    }

    /**
     * Test message sequence: A, B, C@5K, Advance time by 4K, A@1K, B@2K Advance
     * time by 1K.
     * <p>
     * Expected: get A, B, C, A
     */
    @Test
    public void testDelayedDispatchAdvanceTimeTwice() {
        final int messageA = 1;
        final int messageB = 2;
        final int messageC = 3;

        InOrder inOrder = inOrder(mHandlerSpy);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageA));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageB));
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageC), 5000);
        mMockLooper.moveTimeForward(4000);
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageA), 1000);
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageB), 2000);
        mMockLooper.moveTimeForward(1000);
        mMockLooper.dispatchAll();

        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("1: messageA", messageA, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("2: messageB", messageB, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("3: messageC", messageC, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("4: messageA", messageA, equalTo(messageCaptor.getValue().what));

        inOrder.verify(mHandlerSpy, never()).handleMessage(any(Message.class));
    }

    /**
     * Test message sequence: A, B, C@5K, Advance time by 4K, A@5K, B@2K Advance
     * time by 3K.
     * <p>
     * Expected: get A, B, C, B
     */
    @Test
    public void testDelayedDispatchReverseOrder() {
        final int messageA = 1;
        final int messageB = 2;
        final int messageC = 3;

        InOrder inOrder = inOrder(mHandlerSpy);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageA));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageB));
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageC), 5000);
        mMockLooper.moveTimeForward(4000);
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageA), 5000);
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageB), 2000);
        mMockLooper.moveTimeForward(3000);
        mMockLooper.dispatchAll();

        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("1: messageA", messageA, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("2: messageB", messageB, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("3: messageC", messageC, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("4: messageB", messageB, equalTo(messageCaptor.getValue().what));

        inOrder.verify(mHandlerSpy, never()).handleMessage(any(Message.class));
    }

    /**
     * Test message sequence: A, B, C@5K, Advance time by 4K, dispatch all,
     * A@5K, B@2K Advance time by 3K, dispatch all.
     * <p>
     * Expected: get A, B after first dispatch; then C, B after second dispatch
     */
    @Test
    public void testDelayedDispatchAllMultipleTimes() {
        final int messageA = 1;
        final int messageB = 2;
        final int messageC = 3;

        InOrder inOrder = inOrder(mHandlerSpy);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageA));
        mHandlerSpy.sendMessage(mHandler.obtainMessage(messageB));
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageC), 5000);
        mMockLooper.moveTimeForward(4000);
        mMockLooper.dispatchAll();

        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("1: messageA", messageA, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("2: messageB", messageB, equalTo(messageCaptor.getValue().what));

        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageA), 5000);
        mHandlerSpy.sendMessageDelayed(mHandler.obtainMessage(messageB), 2000);
        mMockLooper.moveTimeForward(3000);
        mMockLooper.dispatchAll();

        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("3: messageC", messageC, equalTo(messageCaptor.getValue().what));
        inOrder.verify(mHandlerSpy).handleMessage(messageCaptor.capture());
        collector.checkThat("4: messageB", messageB, equalTo(messageCaptor.getValue().what));

        inOrder.verify(mHandlerSpy, never()).handleMessage(any(Message.class));
    }
}
