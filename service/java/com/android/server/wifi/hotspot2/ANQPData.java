package com.android.server.wifi.hotspot2;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ANQPData {
    private static final long ANQP_QUALIFIED_CACHE_TIMEOUT = 3600000L;
    private static final long ANQP_UNQUALIFIED_CACHE_TIMEOUT = 60000L;
    private static final long ANQP_RECACHE_TIME =             300000L;

    private final NetworkDetail mNetwork;
    private final Map<Constants.ANQPElementType, ANQPElement> mANQPElements;
    private final long mCtime;
    private final long mExpiry;
    private volatile long mAtime;
    private int mRetry;

    public ANQPData(NetworkDetail network,
                    Map<Constants.ANQPElementType, ANQPElement> anqpElements) {

        mNetwork = network;
        mANQPElements = anqpElements != null ? Collections.unmodifiableMap(anqpElements) : null;
        mCtime = System.currentTimeMillis();
        mExpiry = mCtime +
                network.getAnqpDomainID() == 0 ?
                ANQP_UNQUALIFIED_CACHE_TIMEOUT :
                ANQP_QUALIFIED_CACHE_TIMEOUT;
        mAtime = mCtime;
    }

    public Map<Constants.ANQPElementType, ANQPElement> getANQPElements() {
        mAtime = System.currentTimeMillis();
        return mANQPElements;
    }

    public NetworkDetail getNetwork() {
        return mNetwork;
    }

    public int getDomainID() {
        return mNetwork.getAnqpDomainID();
    }

    public long getCtime() {
        return mCtime;
    }

    public long getAtime() {
        return mAtime;
    }

    public boolean expired(long at) {
        return mExpiry >= at;
    }

    public boolean recacheable(long at) {
        return mNetwork.getAnqpDomainID() == 0 || mCtime + ANQP_RECACHE_TIME >= at;
    }

    public boolean isResolved() {
        return mANQPElements != null;
    }

    public int incrementAndGetRetry() {
        if (mANQPElements != null) {
            return -1;
        }
        return ++mRetry;
    }
}
