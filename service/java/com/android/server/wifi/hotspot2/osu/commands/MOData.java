package com.android.server.wifi.hotspot2.osu.commands;

import com.android.server.wifi.hotspot2.omadm.MOTree;
import com.android.server.wifi.hotspot2.omadm.XMLNode;

public class MOData implements OSUCommandData {
    private final String mBaseURI;
    private final String mURN;
    private final MOTree mMOTree;

    public MOData(XMLNode root) {
        mBaseURI = root.getAttributeValue("spp:managementTreeURI");
        mURN = root.getAttributeValue("spp:moURN");
        mMOTree = root.getMOTree();
    }

    public String getBaseURI() {
        return mBaseURI;
    }

    public String getURN() {
        return mURN;
    }

    public MOTree getMOTree() {
        return mMOTree;
    }

    @Override
    public String toString() {
        return "Base URI: " + mBaseURI + ", MO: " + mMOTree;
    }
}
