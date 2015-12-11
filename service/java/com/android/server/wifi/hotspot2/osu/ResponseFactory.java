package com.android.server.wifi.hotspot2.osu;

import com.android.server.wifi.hotspot2.omadm.OMAException;
import com.android.server.wifi.hotspot2.omadm.XMLNode;

public interface ResponseFactory {
    public OSUResponse buildResponse(XMLNode root) throws OMAException;
}
