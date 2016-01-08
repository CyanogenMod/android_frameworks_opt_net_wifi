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

package com.android.server.wifi;

import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Creates a looper whose message queue can be manipulated similarly to that of
 * MockLooper. Additionally, can handle abstract time - i.e. can specify to the
 * queue time changes to handle messages posted with a delay.
 */
public class MockLooperAbstractTime extends MockLooper {
    private static final Field MESSAGE_QUEUE_MESSAGES_FIELD;
    private static final Field MESSAGE_NEXT_FIELD;
    private static final Field MESSAGE_WHEN_FIELD;
    private static final Method MESSAGE_MARK_IN_USE_METHOD;

    static {
        try {
            MESSAGE_QUEUE_MESSAGES_FIELD = MessageQueue.class.getDeclaredField("mMessages");
            MESSAGE_QUEUE_MESSAGES_FIELD.setAccessible(true);
            MESSAGE_NEXT_FIELD = Message.class.getDeclaredField("next");
            MESSAGE_NEXT_FIELD.setAccessible(true);
            MESSAGE_WHEN_FIELD = Message.class.getDeclaredField("when");
            MESSAGE_WHEN_FIELD.setAccessible(true);
            MESSAGE_MARK_IN_USE_METHOD = Message.class.getDeclaredMethod("markInUse");
            MESSAGE_MARK_IN_USE_METHOD.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            throw new RuntimeException("Failed to initialize MockLooperAbstractTime", e);
        }
    }

    private Message getMessageLinkedList() {
        try {
            MessageQueue queue = mLooper.getQueue();
            return (Message) MESSAGE_QUEUE_MESSAGES_FIELD.get(queue);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Access failed in MockLooperAbstractTime", e);
        }
    }

    public void moveTimeForward(long milliSeconds) {
        try {
            Message msg = getMessageLinkedList();
            while (msg != null) {
                long updatedWhen = msg.getWhen() - milliSeconds;
                if (updatedWhen < 0) {
                    updatedWhen = 0;
                }
                MESSAGE_WHEN_FIELD.set(msg, updatedWhen);
                msg = (Message) MESSAGE_NEXT_FIELD.get(msg);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Access failed in MockLooperAbstractTime", e);
        }
    }

    @Override
    protected Message messageQueueNext() {
        try {
            long now = SystemClock.uptimeMillis();

            Message prevMsg = null;
            Message msg = getMessageLinkedList();
            if (msg != null && msg.getTarget() == null) {
                // Stalled by a barrier. Find the next asynchronous message in
                // the queue.
                do {
                    prevMsg = msg;
                    msg = (Message) MESSAGE_NEXT_FIELD.get(msg);
                } while (msg != null && !msg.isAsynchronous());
            }
            if (msg != null) {
                if (now >= msg.getWhen()) {
                    // Got a message.
                    if (prevMsg != null) {
                        MESSAGE_NEXT_FIELD.set(prevMsg, MESSAGE_NEXT_FIELD.get(msg));
                    } else {
                        MESSAGE_QUEUE_MESSAGES_FIELD.set(mLooper.getQueue(),
                                MESSAGE_NEXT_FIELD.get(msg));
                    }
                    MESSAGE_NEXT_FIELD.set(msg, null);
                    MESSAGE_MARK_IN_USE_METHOD.invoke(msg);
                    return msg;
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Access failed in MockLooperAbstractTime", e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Access failed in MockLooperAbstractTime", e);
        }

        return null;
    }

    /**
     * @return true if there are pending messages in the message queue
     */
    @Override
    public boolean isIdle() {
        Message messageList = getMessageLinkedList();

        return messageList != null && SystemClock.uptimeMillis() >= messageList.getWhen();
    }
}
