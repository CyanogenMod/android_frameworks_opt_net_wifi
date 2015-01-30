package com.android.server.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.android.server.wifi.anqp.Constants.BYTE_MASK;
import static com.android.server.wifi.anqp.Constants.getLEInteger;

/**
 * The Roaming Consortium ANQP Element, IEEE802.11-2012 section 8.4.4.7
 */
public class RoamingConsortiumElement extends ANQPElement {

    private final List<Long> mOis;

    public RoamingConsortiumElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mOis = new ArrayList<Long>();

        while (payload.hasRemaining()) {
            int length = payload.get() & BYTE_MASK;
            if (length > payload.remaining()) {
                throw new ProtocolException("Bad OI length: " + length);
            }
            mOis.add(getLEInteger(payload, length));
        }
    }

    public List<Long> getOIs() {
        return Collections.unmodifiableList(mOis);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RoamingConsortiumElement{mOis=[");
        boolean first = true;
        for ( long oi : mOis ) {
            if (first) {
                first = false;
            }
            else {
                sb.append(", ");
            }
            if (Long.numberOfLeadingZeros(oi)>40) {
                sb.append(String.format("%09x", oi));
            }
            else {
                sb.append(String.format("%06x", oi));
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
