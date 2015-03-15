package com.android.server.wifi.hotspot2;

import android.util.Log;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnqpCache implements AlarmHandler {
    private static final long CACHE_RECHECK = 60000L;

    private final HashMap<NetworkDetail, ANQPData> mANQPCache;
    private final HashMap<Long, ANQPData> mHESSIDCache;
    private final Chronograph mChronograph;

    public AnqpCache(Chronograph chronograph) {
        mANQPCache = new HashMap<>();
        mHESSIDCache = new HashMap<>();
        mChronograph = chronograph;
        mChronograph.addAlarm(CACHE_RECHECK, this, null);
    }

    public boolean initiate(NetworkDetail network) {
        synchronized (mANQPCache) {
            if (!mANQPCache.containsKey(network)) {
                mANQPCache.put(network, new ANQPData(network, null));
                return true;
            }
            else {
                return false;
            }
        }
    }

    public int getRetry(NetworkDetail network) {
        ANQPData data;
        synchronized (mANQPCache) {
            data = mANQPCache.get(network);
        }
        return data != null ? data.incrementAndGetRetry() : -1;
    }

    public void update(NetworkDetail network,
                       Map<Constants.ANQPElementType, ANQPElement> anqpElements) {

        long now = System.currentTimeMillis();

        // Networks with a 0 ANQP Domain ID are still cached, but with a very short expiry, just
        // long enough to prevent excessive re-querying.
        synchronized (mANQPCache) {
            ANQPData data = mANQPCache.get(network);
            if (data == null ||
                    !data.isResolved() ||
                    data.getDomainID() != network.getAnqpDomainID() ||
                    data.recacheable(now)) {
                data = new ANQPData(network, anqpElements);
                mANQPCache.put(network, data);
            }
            // The spec really talks about caching per ESS, where an ESS is a set of APs with the
            // same SSID. Since we're presumably in HS2.0 land here I have taken the liberty to
            // tighten the definition of an ESS as the set of APs all sharing an HESSID.
            if (network.getAnqpDomainID() != 0 && network.getHESSID() != 0 ) {
                mHESSIDCache.put(network.getHESSID(), data);
            }
        }
    }

    public ANQPData getEntry(NetworkDetail network) {
        ANQPData data;

        synchronized (mANQPCache) {
            data = mANQPCache.get(network);
            if (data == null && network.getAnqpDomainID() != 0 && network.getHESSID() != 0) {
                data = mHESSIDCache.get(network.getHESSID());
            }
        }

        long now = System.currentTimeMillis();

        if (data == null ||
                !data.isResolved() ||
                data.getDomainID() != network.getAnqpDomainID() ||
                data.expired(now)) {
            return null;
        }
        return data;
    }

    @Override
    public void wake(Object token) {
        long now = System.currentTimeMillis();
        synchronized (mANQPCache) {
            List<NetworkDetail> regulars = new ArrayList<>();
            for (Map.Entry<NetworkDetail, ANQPData> entry : mANQPCache.entrySet()) {
                if (entry.getValue().expired(now)) {
                    regulars.add(entry.getKey());
                }
            }
            for (NetworkDetail key : regulars) {
                mANQPCache.remove(key);
                Log.d("HS2J", "Retired " + key.toKeyString() + "/" + key.getAnqpDomainID());
            }

            List<Long> hessids = new ArrayList<>();
            for (Map.Entry<Long, ANQPData> entry : mHESSIDCache.entrySet()) {
                if (entry.getValue().expired(now)) {
                    hessids.add(entry.getKey());
                }
            }
            for (Long key : hessids) {
                mANQPCache.remove(key);
                Log.d("HS2J", "Retired HESSID " + key);
            }
        }
        mChronograph.addAlarm(CACHE_RECHECK, this, null);
    }
}
