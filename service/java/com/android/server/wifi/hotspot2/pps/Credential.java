package com.android.server.wifi.hotspot2.pps;

import android.net.wifi.WifiEnterpriseConfig;
import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.anqp.eap.EAP;
import com.android.server.wifi.anqp.eap.EAPMethod;
import com.android.server.wifi.anqp.eap.InnerAuthEAP;
import com.android.server.wifi.anqp.eap.AuthParam;
import com.android.server.wifi.anqp.eap.NonEAPInnerAuth;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.omadm.OMAException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Credential {
    public enum CertType {IEEE, x509v3}

    private final long mCtime;
    private final long mExpTime;
    private final String mRealm;
    private final boolean mCheckAAACert;

    private final String mUserName;
    private final String mPassword;
    private final boolean mMachineManaged;
    private final String mSTokenApp;
    private final boolean mShare;
    private final EAPMethod mEAPMethod;

    private final CertType mCertType;
    private final byte[] mFingerPrint;

    private final String mImsi;

    public Credential(long ctime, long expTime, String realm, boolean checkAAACert,
                      EAPMethod eapMethod, String userName, String password,
                      boolean machineManaged, String stApp, boolean share) {
        mCtime = ctime;
        mExpTime = expTime;
        mRealm = realm;
        mCheckAAACert = checkAAACert;
        mEAPMethod = eapMethod;
        mUserName = userName;
        byte[] pwOctets = Base64.decode(password, Base64.DEFAULT);
        mPassword = new String(pwOctets, StandardCharsets.UTF_8);
        mMachineManaged = machineManaged;
        mSTokenApp = stApp;
        mShare = share;

        mCertType = null;
        mFingerPrint = null;

        mImsi = null;
    }

    public Credential(long ctime, long expTime, String realm, boolean checkAAACert,
                      EAPMethod eapMethod, Credential.CertType certType, byte[] fingerPrint) {
        mCtime = ctime;
        mExpTime = expTime;
        mRealm = realm;
        mCheckAAACert = checkAAACert;
        mEAPMethod = eapMethod;
        mCertType = certType;
        mFingerPrint = fingerPrint;

        mUserName = null;
        mPassword = null;
        mMachineManaged = false;
        mSTokenApp = null;
        mShare = false;

        mImsi = null;
    }

    public Credential(long ctime, long expTime, String realm, boolean checkAAACert,
                      EAPMethod eapMethod, String imsi) {
        mCtime = ctime;
        mExpTime = expTime;
        mRealm = realm;
        mCheckAAACert = checkAAACert;
        mEAPMethod = eapMethod;
        mImsi = imsi;

        mCertType = null;
        mFingerPrint = null;

        mUserName = null;
        mPassword = null;
        mMachineManaged = false;
        mSTokenApp = null;
        mShare = false;
    }

    public Credential(WifiEnterpriseConfig enterpriseConfig) {
        mCtime = 0;
        mExpTime = 0;
        mRealm = enterpriseConfig.getRealm();
        mCheckAAACert = true;
        mEAPMethod = mapEapMethod(enterpriseConfig.getEapMethod(),
                enterpriseConfig.getPhase2Method());
        mCertType = mapCertType(enterpriseConfig.getEapMethod());
        mFingerPrint = null;
        mImsi = enterpriseConfig.getPlmn();
        mUserName = null;
        mPassword = null;
        mMachineManaged = false;
        mSTokenApp = null;
        mShare = false;
    }

    public static CertType mapCertType(String certType) throws OMAException {
        if (certType.equalsIgnoreCase("x509v3")) {
            return CertType.x509v3;
        } else if (certType.equalsIgnoreCase("802.1ar")) {
            return CertType.IEEE;
        } else {
            throw new OMAException("Invalid cert type: '" + certType + "'");
        }
    }

    private static EAPMethod mapEapMethod(int eapMethod, int phase2Method) {
        if (eapMethod == WifiEnterpriseConfig.Eap.TLS) {
            return new EAPMethod(EAP.EAPMethodID.EAP_TLS, null);
        } else if (eapMethod == WifiEnterpriseConfig.Eap.TTLS) {
            /* keep this table in sync with WifiEnterpriseConfig.Phase2 enum */
            final String innnerMethods[] = { null, "PAP", "MS-CHAP", "MS-CHAP-V2", null };
            return new EAPMethod(EAP.EAPMethodID.EAP_TTLS,
                    new NonEAPInnerAuth(innnerMethods[phase2Method]));
        } else if (eapMethod == WifiEnterpriseConfig.Eap.PEAP) {
            /* restricting passpoint implementation from using PEAP */
            return null;
        } else if (eapMethod == WifiEnterpriseConfig.Eap.PWD) {
            /* restricting passpoint implementation from using EAP_PWD */
            return null;
        } else if (eapMethod == WifiEnterpriseConfig.Eap.SIM) {
            return new EAPMethod(EAP.EAPMethodID.EAP_SIM, null);
        } else if (eapMethod == WifiEnterpriseConfig.Eap.AKA) {
            return new EAPMethod(EAP.EAPMethodID.EAP_AKA, null);
        }
        /*
            TODO: Uncomment this when AKA_PRIME is defined in WifiEnterpriseConfig
        else if (eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME) {
            return new EAPMethod(EAP.EAPMethodID.EAP_AKAPrim, null);
        }
        */

        Log.d("PARSE-LOG", "Invalid eap method");
        return null;
    }

    private static CertType mapCertType(int eapMethod) {
        if (eapMethod == WifiEnterpriseConfig.Eap.TLS
                || eapMethod == WifiEnterpriseConfig.Eap.TTLS) {
            return CertType.x509v3;
        } else {
            Log.d("PARSE-LOG", "Invalid cert type" + eapMethod);
            return null;
        }
    }

    public EAPMethod getEAPMethod() {
        return mEAPMethod;
    }

    public String getRealm() {
        return mRealm;
    }

    public String getImsi() {
        return mImsi;
    }

    public String getUserName() {
        return mUserName;
    }

    public String getPassword() {
        return mPassword;
    }

    @Override
    public String toString() {
        return "Credential{" +
                "mCtime=" + Utils.toUTCString(mCtime) +
                ", mExpTime=" + Utils.toUTCString(mExpTime) +
                ", mRealm='" + mRealm + '\'' +
                ", mCheckAAACert=" + mCheckAAACert +
                ", mUserName='" + mUserName + '\'' +
                ", mPassword='" + mPassword + '\'' +
                ", mMachineManaged=" + mMachineManaged +
                ", mSTokenApp='" + mSTokenApp + '\'' +
                ", mShare=" + mShare +
                ", mEAPMethod=" + mEAPMethod +
                ", mCertType=" + mCertType +
                ", mFingerPrint=" + Utils.toHexString(mFingerPrint) +
                ", mImsi='" + mImsi + '\'' +
                '}';
    }
}
