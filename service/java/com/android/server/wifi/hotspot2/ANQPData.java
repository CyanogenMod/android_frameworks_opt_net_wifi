package com.android.server.wifi.hotspot2;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ANQPData {
    private static final long ANQP_QUALIFIED_CACHE_TIMEOUT = 3600000L;
    private static final long ANQP_UNQUALIFIED_CACHE_TIMEOUT = 60000L;
    private static final long ANQP_HOLDOFF_TIME =              60000L;
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
                ( network.getAnqpDomainID() == 0 ?
                ANQP_UNQUALIFIED_CACHE_TIMEOUT :
                ANQP_QUALIFIED_CACHE_TIMEOUT );
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
        return mExpiry < at;
    }

    public boolean recacheable(long at) {
        return mNetwork.getAnqpDomainID() == 0 || mCtime + ANQP_RECACHE_TIME < at;
    }

    public boolean isResolved() {
        return mANQPElements != null;
    }

    public boolean expendable(long at) {
        return mANQPElements == null && mCtime + ANQP_HOLDOFF_TIME < at;
    }

    public int incrementAndGetRetry() {
        if (mANQPElements != null) {
            return -1;
        }
        return ++mRetry;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(mNetwork.toKeyString()).append(", domid ").append(mNetwork.getAnqpDomainID());
        if (mANQPElements == null) {
            sb.append(", unresolved, ");
        }
        else {
            sb.append(", ").append(mANQPElements.size()).append(" elements, ");
        }
        long now = System.currentTimeMillis();
        sb.append(Utils.toHMS(now-mCtime)).append(" old, expires in ").
                append(Utils.toHMS(mExpiry-now)).append(' ');
        sb.append(expired(now) ? 'x' : '-');
        sb.append(recacheable(now) ? 'c' : '-');
        sb.append(isResolved() ? '-' : 'u');
        return sb.toString();
    }
}
