package com.android.server.wifi.hotspot2;

import com.android.server.wifi.anqp.ANQPElement;

import java.util.Collections;
import java.util.List;

public class ANQPData {
    private final List<ANQPElement> mANQPElements;
    private final long mCtime;
    private volatile long mAtime;

    public ANQPData(List<ANQPElement> ANQPElements) {
        mANQPElements = Collections.unmodifiableList(ANQPElements);
        mCtime = System.currentTimeMillis();
        mAtime = mCtime;
    }

    public List<ANQPElement> getANQPElements() {
        mAtime = System.currentTimeMillis();
        return mANQPElements;
    }
}
