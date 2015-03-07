package com.android.server.wifi.hotspot2.pps;

import android.util.Base64;

import com.android.server.wifi.anqp.eap.EAPMethod;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.omadm.OMAException;

import java.nio.charset.StandardCharsets;

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

    public static CertType mapCertType(String certType) throws OMAException {
        if (certType.equalsIgnoreCase("x509v3")) {
            return CertType.x509v3;
        } else if (certType.equalsIgnoreCase("802.1ar")) {
            return CertType.IEEE;
        } else {
            throw new OMAException("Invalid cert type: '" + certType + "'");
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
