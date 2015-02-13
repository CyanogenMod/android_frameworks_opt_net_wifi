package com.android.server.wifi.anqp.eap;

import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.hotspot2.AuthMatch;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An EAP Method, part of the NAI Realm ANQP element, specified in
 * IEEE802.11-2012 section 8.4.4.10, figure 8-420
 */
public class EAPMethod {
    private final EAP.EAPMethodID mEAPMethodID;
    private final Map<EAP.AuthInfoID, Set<AuthParam>> mAuthParams;

    public EAPMethod(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 3) {
            throw new ProtocolException("Runt EAP Method: " + payload.remaining());
        }

        int length = payload.get() & Constants.BYTE_MASK;
        int methodID = payload.get() & Constants.BYTE_MASK;
        int count = payload.get() & Constants.BYTE_MASK;

        mEAPMethodID = EAP.mapEAPMethod(methodID);
        mAuthParams = new EnumMap<EAP.AuthInfoID, Set<AuthParam>>(EAP.AuthInfoID.class);

        int realCount = 0;

        ByteBuffer paramPayload = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        paramPayload.limit(paramPayload.position() + length - 2);
        payload.position(payload.position() + length - 2);
        while (paramPayload.hasRemaining()) {
            int id = paramPayload.get() & Constants.BYTE_MASK;

            EAP.AuthInfoID authInfoID = EAP.mapAuthMethod(id);
            if (authInfoID == null) {
                throw new ProtocolException("Unknown auth parameter ID: " + id);
            }

            int len = paramPayload.get() & Constants.BYTE_MASK;
            if (len == 0 || len > paramPayload.remaining()) {
                throw new ProtocolException("Bad auth method length: " + len);
            }

            switch (authInfoID) {
                case ExpandedEAPMethod:
                    addAuthParam(new ExpandedEAPMethod(authInfoID, len, paramPayload));
                    break;
                case NonEAPInnerAuthType:
                    addAuthParam(new NonEAPInnerAuth(len, paramPayload));
                    break;
                case InnerAuthEAPMethodType:
                    addAuthParam(new InnerAuthEAP(len, paramPayload));
                    break;
                case ExpandedInnerEAPMethod:
                    addAuthParam(new ExpandedEAPMethod(authInfoID, len, paramPayload));
                    break;
                case CredentialType:
                    addAuthParam(new Credential(authInfoID, len, paramPayload));
                    break;
                case TunneledEAPMethodCredType:
                    addAuthParam(new Credential(authInfoID, len, paramPayload));
                    break;
                case VendorSpecific:
                    addAuthParam(new VendorSpecificAuth(len, paramPayload));
                    break;
            }

            realCount++;
        }
        if (realCount != count)
            throw new ProtocolException("Invalid parameter count: " + realCount +
                    ", expected " + count);
    }

    public EAPMethod(EAP.EAPMethodID eapMethodID, AuthParam authParam) {
        mEAPMethodID = eapMethodID;
        mAuthParams = new HashMap<EAP.AuthInfoID, Set<AuthParam>>(1);
        if (authParam != null) {
            Set<AuthParam> authParams = new HashSet<AuthParam>();
            authParams.add(authParam);
            mAuthParams.put(authParam.getAuthInfoID(), authParams);
        }
    }

    private void addAuthParam(AuthParam param) {
        Set<AuthParam> authParams = mAuthParams.get(param.getAuthInfoID());
        if (authParams == null) {
            authParams = new HashSet<AuthParam>();
            mAuthParams.put(param.getAuthInfoID(), authParams);
        }
        authParams.add(param);
    }

    public Map<EAP.AuthInfoID, Set<AuthParam>> getAuthParams() {
        return Collections.unmodifiableMap(mAuthParams);
    }

    public EAP.EAPMethodID getEAPMethodID() {
        return mEAPMethodID;
    }

    public AuthMatch matchAuthParams(EAPMethod other) {
        if (other.getAuthParams().isEmpty() || mAuthParams.isEmpty()) {
            return AuthMatch.Unqualified;
        }
        for (Map.Entry<EAP.AuthInfoID, Set<AuthParam>> entry : other.getAuthParams().entrySet()) {

            Set<AuthParam> myParams = mAuthParams.get(entry.getKey());
            if (myParams == null)
                continue;

            if (!Collections.disjoint(myParams, entry.getValue())) {
                return AuthMatch.Qualified;
            }
        }
        return AuthMatch.None;
    }

    public AuthParam getAuthParam() {
        if (mAuthParams.isEmpty()) {
            return null;
        }
        Set<AuthParam> params = mAuthParams.values().iterator().next();
        if (params.isEmpty()) {
            return null;
        }
        return params.iterator().next();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("EAP Method ").append(mEAPMethodID).append('\n');
        for (Set<AuthParam> paramSet : mAuthParams.values()) {
            for (AuthParam param : paramSet) {
                sb.append("      ").append(param.toString());
            }
        }
        return sb.toString();
    }
}
