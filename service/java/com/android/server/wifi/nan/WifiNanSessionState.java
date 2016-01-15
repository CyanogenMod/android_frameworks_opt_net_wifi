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

import android.net.wifi.nan.IWifiNanSessionListener;
import android.net.wifi.nan.PublishData;
import android.net.wifi.nan.PublishSettings;
import android.net.wifi.nan.SubscribeData;
import android.net.wifi.nan.SubscribeSettings;
import android.net.wifi.nan.WifiNanSessionListener;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class WifiNanSessionState {
    private static final String TAG = "WifiNanSessionState";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final SparseArray<WifiNanSessionState> sSessionsByPubSubId = new SparseArray<>();

    private static final SparseArray<String> sMacByRequestorInstanceId = new SparseArray<>();

    private int mUid;
    private int mSessionId;
    private IWifiNanSessionListener mListener;
    private int mEvents;

    private boolean mPubSubIdValid = false;
    private int mPubSubId;

    private static final int SESSION_TYPE_NOT_INIT = 0;
    private static final int SESSION_TYPE_PUBLISH = 1;
    private static final int SESSION_TYPE_SUBSCRIBE = 2;
    private int mSessionType = SESSION_TYPE_NOT_INIT;

    public static WifiNanSessionState getNanSessionStateForPubSubId(int pubSubId) {
        return sSessionsByPubSubId.get(pubSubId);
    }

    public WifiNanSessionState(int uid, int sessionId, IWifiNanSessionListener listener,
            int events) {
        mUid = uid;
        mSessionId = sessionId;
        mListener = listener;
        mEvents = events;
    }

    public void destroy() {
        stop(WifiNanStateManager.getInstance().getTransactionId());
        if (mPubSubIdValid) {
            WifiNanSessionState.sSessionsByPubSubId.delete(mPubSubId);
            sMacByRequestorInstanceId.clear();
            mListener = null;
            mPubSubIdValid = false;
        }
    }

    public int getUid() {
        return mUid;
    }

    public int getSessionId() {
        return mSessionId;
    }

    public void publish(short transactionId, PublishData data, PublishSettings settings) {
        if (mSessionType == SESSION_TYPE_SUBSCRIBE) {
            throw new IllegalStateException("A SUBSCRIBE session is being used for publish");
        }
        mSessionType = SESSION_TYPE_PUBLISH;

        WifiNanNative.getInstance().publish(transactionId, mPubSubIdValid ? mPubSubId : 0, data,
                settings);
    }

    public void subscribe(short transactionId, SubscribeData data, SubscribeSettings settings) {
        if (mSessionType == SESSION_TYPE_PUBLISH) {
            throw new IllegalStateException("A PUBLISH session is being used for publish");
        }
        mSessionType = SESSION_TYPE_SUBSCRIBE;

        WifiNanNative.getInstance().subscribe(transactionId, mPubSubIdValid ? mPubSubId : 0, data,
                settings);
    }

    public void sendMessage(short transactionId, int peerId, byte[] message,
            int messageLength) {
        if (!mPubSubIdValid) {
            Log.e(TAG, "sendMessage: attempting to send a message on a non-live session "
                    + "(no successful publish or subscribe");
            onMessageSendFail(WifiNanSessionListener.FAIL_REASON_NO_MATCH_SESSION);
            return;
        }

        String peerMacStr = sMacByRequestorInstanceId.get(peerId);
        if (peerMacStr == null) {
            Log.e(TAG, "sendMessage: attempting to send a message to an address which didn't "
                    + "match/contact us");
            onMessageSendFail(WifiNanSessionListener.FAIL_REASON_NO_MATCH_SESSION);
            return;
        }
        byte[] peerMac = HexEncoding.decode(peerMacStr.toCharArray(), false);

        WifiNanNative.getInstance().sendMessage(transactionId, mPubSubId, peerId, peerMac, message,
                messageLength);
    }

    public void stop(short transactionId) {
        if (!mPubSubIdValid || mSessionType == SESSION_TYPE_NOT_INIT) {
            Log.e(TAG, "sendMessage: attempting to stop pub/sub on a non-live session (no "
                    + "successful publish or subscribe");
            return;
        }

        if (mSessionType == SESSION_TYPE_PUBLISH) {
            WifiNanNative.getInstance().stopPublish(transactionId, mPubSubId);
        } else if (mSessionType == SESSION_TYPE_SUBSCRIBE) {
            WifiNanNative.getInstance().stopSubscribe(transactionId, mPubSubId);
        }
    }

    public void onPublishSuccess(int publishId) {
        if (mPubSubIdValid) {
            WifiNanSessionState.sSessionsByPubSubId.delete(mPubSubId);
        }

        mPubSubId = publishId;
        mPubSubIdValid = true;
        WifiNanSessionState.sSessionsByPubSubId.put(mPubSubId, this);
    }

    public void onPublishFail(int status) {
        mPubSubIdValid = false;
        try {
            if (mListener != null && (mEvents & WifiNanSessionListener.LISTEN_PUBLISH_FAIL) != 0) {
                mListener.onPublishFail(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onPublishFail: RemoteException (FYI): " + e);
        }
    }

    public void onPublishTerminated(int status) {
        mPubSubIdValid = false;
        try {
            if (mListener != null
                    && (mEvents & WifiNanSessionListener.LISTEN_PUBLISH_TERMINATED) != 0) {
                mListener.onPublishTerminated(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onPublishTerminated: RemoteException (FYI): " + e);
        }
    }

    public void onSubscribeSuccess(int subscribeId) {
        if (mPubSubIdValid) {
            WifiNanSessionState.sSessionsByPubSubId.delete(mPubSubId);
        }

        mPubSubId = subscribeId;
        mPubSubIdValid = true;
        WifiNanSessionState.sSessionsByPubSubId.put(mPubSubId, this);
    }

    public void onSubscribeFail(int status) {
        mPubSubIdValid = false;
        try {
            if (mListener != null
                    && (mEvents & WifiNanSessionListener.LISTEN_SUBSCRIBE_FAIL) != 0) {
                mListener.onSubscribeFail(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onSubscribeFail: RemoteException (FYI): " + e);
        }
    }

    public void onSubscribeTerminated(int status) {
        mPubSubIdValid = false;
        try {
            if (mListener != null
                    && (mEvents & WifiNanSessionListener.LISTEN_SUBSCRIBE_TERMINATED) != 0) {
                mListener.onSubscribeTerminated(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onSubscribeTerminated: RemoteException (FYI): " + e);
        }
    }

    public void onMessageSendSuccess() {
        try {
            if (mListener != null
                    && (mEvents & WifiNanSessionListener.LISTEN_MESSAGE_SEND_SUCCESS) != 0) {
                mListener.onMessageSendSuccess();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageSendSuccess: RemoteException (FYI): " + e);
        }
    }

    public void onMessageSendFail(int status) {
        try {
            if (mListener != null
                    && (mEvents & WifiNanSessionListener.LISTEN_MESSAGE_SEND_FAIL) != 0) {
                mListener.onMessageSendFail(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageSendFail: RemoteException (FYI): " + e);
        }
    }

    public void onMatch(int requestorInstanceId, byte[] peerMac, byte[] serviceSpecificInfo,
            int serviceSpecificInfoLength, byte[] matchFilter, int matchFilterLength) {
        String prevMac = sMacByRequestorInstanceId.get(requestorInstanceId);
        sMacByRequestorInstanceId.put(requestorInstanceId, new String(HexEncoding.encode(peerMac)));

        if (DBG) Log.d(TAG, "onMatch: previous peer MAC replaced - " + prevMac);

        try {
            if (mListener != null && (mEvents & WifiNanSessionListener.LISTEN_MATCH) != 0) {
                mListener.onMatch(requestorInstanceId, serviceSpecificInfo,
                        serviceSpecificInfoLength, matchFilter, matchFilterLength);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMatch: RemoteException (FYI): " + e);
        }
    }

    public void onMessageReceived(int requestorInstanceId, byte[] peerMac, byte[] message,
            int messageLength) {
        String prevMac = sMacByRequestorInstanceId.get(requestorInstanceId);
        sMacByRequestorInstanceId.put(requestorInstanceId, new String(HexEncoding.encode(peerMac)));

        if (DBG) {
            Log.d(TAG, "onMessageReceived: previous peer MAC replaced - " + prevMac);
        }

        try {
            if (mListener != null
                    && (mEvents & WifiNanSessionListener.LISTEN_MESSAGE_RECEIVED) != 0) {
                mListener.onMessageReceived(requestorInstanceId, message, messageLength);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageReceived: RemoteException (FYI): " + e);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NanSessionState:");
        pw.println("  mUid: " + mUid);
        pw.println("  mSessionId: " + mSessionId);
        pw.println("  mSessionType: " + mSessionType);
        pw.println("  mEvents: " + mEvents);
        pw.println("  mPubSubId: " + (mPubSubIdValid ? Integer.toString(mPubSubId) : "not valid"));
        pw.println("  sMacByRequestorInstanceId: [" + sMacByRequestorInstanceId + "]");
    }

    public static void dumpS(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NanSessionState Static:");
        pw.println("  sSessionsByPubSubId: " + WifiNanSessionState.sSessionsByPubSubId);
    }
}
