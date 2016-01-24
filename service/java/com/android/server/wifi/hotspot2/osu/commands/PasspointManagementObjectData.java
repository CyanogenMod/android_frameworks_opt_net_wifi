package com.android.server.wifi.hotspot2.osu.commands;

import android.net.wifi.PasspointManagementObjectDefinition;

import com.android.server.wifi.hotspot2.omadm.MOTree;
import com.android.server.wifi.hotspot2.omadm.OMAConstants;
import com.android.server.wifi.hotspot2.omadm.OMAParser;
import com.android.server.wifi.hotspot2.omadm.XMLNode;

import org.xml.sax.SAXException;

import java.io.IOException;

/**
 * This object describes a partial tree structure in the Hotspot 2.0 release 2 management object.
 * The object is used during subscription remediation to modify parts of an existing PPS MO
 * tree (Hotspot 2.0 specification section 9.1
 */
public class PasspointManagementObjectData implements OSUCommandData {
    private final String mBaseURI;
    private final String mURN;
    private final MOTree mMOTree;

    public PasspointManagementObjectData(XMLNode root) {
        mBaseURI = root.getAttributeValue("spp:managementTreeURI");
        mURN = root.getAttributeValue("spp:moURN");
        mMOTree = root.getMOTree();
    }

    public PasspointManagementObjectData(PasspointManagementObjectDefinition moDef)
            throws IOException, SAXException {
        mBaseURI = moDef.getmBaseUri();
        mURN = moDef.getmUrn();
        OMAParser omaParser = new OMAParser();
        mMOTree = omaParser.parse(moDef.getmMoTree(), OMAConstants.PPS_URN);
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
