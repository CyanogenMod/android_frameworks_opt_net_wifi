package com.android.server.wifi.hotspot2.omadm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Created by jannq on 1/12/15.
 */
public class OMAConstructed extends OMANode {
    private final Map<String, OMANode> m_children;

    public OMAConstructed(OMANode parent, String name, String context) {
        super(parent, name, context);
        m_children = new HashMap<String, OMANode>();
    }

    @Override
    public OMANode addChild(String name, String context, String value, String pathString) throws IOException {
        if (pathString == null) {
            OMANode child = value != null ?
                    new OMAScalar(this, name, context, value) :
                    new OMAConstructed(this, name, context);
            m_children.put(name, child);
            return child;
        } else {
            OMANode target = this;
            while (target.getParent() != null)
                target = target.getParent();

            for (String element : pathString.split("/")) {
                target = target.getChild(element);
                if (target == null)
                    throw new IOException("No child node '" + element + "' in " + getPathString());
                else if (target.isLeaf())
                    throw new IOException("Cannot add child to leaf node: " + getPathString());
            }
            return target.addChild(name, context, value, null);
        }
    }

    public String getScalarValue(Iterator<String> path) throws OMAException {
        if (!path.hasNext()) {
            throw new OMAException("Path too short for " + getPathString());
        }
        String tag = path.next();
        OMANode child = m_children.get(tag);
        if (child != null) {
            return child.getScalarValue(path);
        } else {
            return null;
        }
    }

    @Override
    public OMAConstructed getListValue(Iterator<String> path) throws OMAException {
        if (!path.hasNext()) {
            return this;
        }
        String tag = path.next();
        OMANode child = m_children.get(tag);
        if (child != null) {
            return child.getListValue(path);
        } else {
            return null;
        }
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    @Override
    public Collection<OMANode> getChildren() {
        return Collections.unmodifiableCollection(m_children.values());
    }

    public OMANode getChild(String name) {
        return m_children.get(name);
    }

    @Override
    public String getValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toString(StringBuilder sb, int level) {
        if (getContext() != null) {
            sb.append(getPathString()).append(" (").append(getContext()).append(')').append('\n');
        }

        for (OMANode node : m_children.values())
            node.toString(sb, level + 1);
    }

    @Override
    public void marshal(OutputStream out, int level) throws IOException {
        OMAConstants.indent(level, out);
        OMAConstants.serializeString(getName(), out);
        if (getContext() != null) {
            out.write(String.format("(%s)", getContext()).getBytes(StandardCharsets.UTF_8));
        }
        out.write(new byte[] { '+', '\n' });

        for (OMANode child : m_children.values()) {
            child.marshal(out, level + 1);
        }
        OMAConstants.indent(level, out);
        out.write(".\n".getBytes(StandardCharsets.UTF_8));
    }
}
