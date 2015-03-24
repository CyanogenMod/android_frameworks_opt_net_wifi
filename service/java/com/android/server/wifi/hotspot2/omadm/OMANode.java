package com.android.server.wifi.hotspot2.omadm;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public abstract class OMANode {
    private final OMANode mParent;
    private final String mName;
    private final String mContext;

    protected OMANode(OMANode parent, String name, String context) {
        mParent = parent;
        mName = name;
        mContext = context;
    }

    public OMANode getParent() {
        return mParent;
    }

    public String getName() {
        return mName;
    }

    public String getContext() {
        return mContext;
    }

    public List<String> getPath() {
        LinkedList<String> path = new LinkedList<String>();
        for (OMANode node = this; node.getParent() != null; node = node.getParent())
            path.addFirst(node.getName());
        return path;
    }

    public String getPathString() {
        StringBuilder sb = new StringBuilder();
        for (String element : getPath()) {
            sb.append('/').append(element);
        }
        return sb.toString();
    }

    public abstract String getScalarValue(Iterator<String> path) throws OMAException;

    public abstract OMAConstructed getListValue(Iterator<String> path) throws OMAException;

    public abstract boolean isLeaf();

    public abstract Collection<OMANode> getChildren();

    public abstract OMANode getChild(String name);

    public abstract String getValue();

    public abstract OMANode addChild(String name, String context, String value, String path)
            throws IOException;

    public abstract void marshal(OutputStream out, int level) throws IOException;

    public abstract void toString(StringBuilder sb, int level);

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb, 0);
        return sb.toString();
    }

    public static OMAConstructed unmarshal(InputStream in) throws IOException {
        OMANode node = buildNode(in, null);
        if (node == null || node.isLeaf())
            throw new IOException("Bad OMA tree");
        unmarshal(in, (OMAConstructed) node);
        return (OMAConstructed) node;
    }

    private static void unmarshal(InputStream in, OMAConstructed parent) throws IOException {
        for (; ; ) {
            OMANode node = buildNode(in, parent);
            if (node == null)
                return;
            else if (!node.isLeaf())
                unmarshal(in, (OMAConstructed) node);
        }
    }

    private static OMANode buildNode(InputStream in, OMAConstructed parent) throws IOException {
        String name = OMAConstants.deserializeString(in);
        if (name == null) {
            Log.d("PARSE-LOG", "Could not read node name");
            return null;
        }

        Log.d("PARSE-LOG", "name = " + name);

        String urn = null;
        int next = in.read();
        if (next == '(') {
            urn = OMAConstants.readURN(in);
            Log.d("PARSE-LOG", "Urn = " + urn);
            next = in.read();
        }

        if (next == '=') {
            String value = OMAConstants.deserializeString(in);
            Log.d("PARSE-LOG", "value = " + value);
            return parent.addChild(name, urn, value, null);
        } else if (next == '+') {
            if (parent != null) {
                Log.d("PARSE-LOG", "added child node " + name);
                return parent.addChild(name, urn, null, null);
            } else {
                Log.d("PARSE-LOG", "created new node " + name);
                return new OMAConstructed(null, name, urn);
            }
        }
        else {
            Log.d("PARSE-LOG", "parsing error");
            throw new IOException("Parse error: expected = or + after node name");
        }
    }
}
