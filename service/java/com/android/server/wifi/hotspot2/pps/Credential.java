package com.android.server.wifi.hotspot2.pps;

import android.net.wifi.WifiEnterpriseConfig;
import android.security.Credentials;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.anqp.eap.EAP;
import com.android.server.wifi.anqp.eap.EAPMethod;
import com.android.server.wifi.anqp.eap.NonEAPInnerAuth;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.omadm.OMAException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

public class Credential {
    public enum CertType {IEEE, x509v3}

    public static final String CertTypeX509 = "x509v3";
    public static final String CertTypeIEEE = "802.1ar";

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

        if (!TextUtils.isEmpty(password)) {
            byte[] pwOctets = Base64.decode(password, Base64.DEFAULT);
            mPassword = new String(pwOctets, StandardCharsets.UTF_8);
        } else {
            mPassword = null;
        }

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

    public Credential(WifiEnterpriseConfig enterpriseConfig, KeyStore keyStore) throws IOException {
        mCtime = 0;
        mExpTime = 0;
        mRealm = enterpriseConfig.getRealm();
        mCheckAAACert = true;
        mEAPMethod = mapEapMethod(enterpriseConfig.getEapMethod(),
                enterpriseConfig.getPhase2Method());
        mCertType = mEAPMethod.getEAPMethodID() == EAP.EAPMethodID.EAP_TLS ? CertType.x509v3 : null;
        byte[] fingerPrint;

        if (enterpriseConfig.getClientCertificate() != null) {
            // !!! Not sure this will be true in any practical instances:
            try {
                MessageDigest digester = MessageDigest.getInstance("SHA-256");
                fingerPrint = digester.digest(enterpriseConfig.getClientCertificate().getEncoded());
            }
            catch (GeneralSecurityException gse) {
                Log.e("CRED", "Failed to generate certificate fingerprint: " + gse);
                fingerPrint = null;
            }
        }
        else if (enterpriseConfig.getClientCertificateAlias() != null) {
            String alias = enterpriseConfig.getClientCertificateAlias();
            Log.d("HS2J", "Client alias '" + alias + "'");
            byte[] octets = keyStore.get(Credentials.USER_CERTIFICATE + alias);
            Log.d("HS2J", "DER: " + (octets == null ? "-" : Integer.toString(octets.length)));
            if (octets != null) {
                try {
                    MessageDigest digester = MessageDigest.getInstance("SHA-256");
                    fingerPrint = digester.digest(octets);
                }
                catch (GeneralSecurityException gse) {
                    Log.e("HS2J", "Failed to construct digest: " + gse);
                    fingerPrint = null;
                }
            }
            else // !!! The current alias is *not* derived from the fingerprint...
            {
                try {
                    fingerPrint = Base64.decode(enterpriseConfig.getClientCertificateAlias(),
                            Base64.DEFAULT);
                } catch (IllegalArgumentException ie) {
                    Log.e("CRED", "Bad base 64 alias");
                    fingerPrint = null;
                }
            }
        }
        else {
            fingerPrint = null;
        }
        mFingerPrint = fingerPrint;
        mImsi = enterpriseConfig.getPlmn();
        mUserName = enterpriseConfig.getIdentity();
        mPassword = enterpriseConfig.getPassword();
        mMachineManaged = false;
        mSTokenApp = null;
        mShare = false;
    }

    public static CertType mapCertType(String certType) throws OMAException {
        if (certType.equalsIgnoreCase(CertTypeX509)) {
            return CertType.x509v3;
        } else if (certType.equalsIgnoreCase(CertTypeIEEE)) {
            return CertType.IEEE;
        } else {
            throw new OMAException("Invalid cert type: '" + certType + "'");
        }
    }

    private static EAPMethod mapEapMethod(int eapMethod, int phase2Method) throws IOException {
        if (eapMethod == WifiEnterpriseConfig.Eap.TLS) {
            return new EAPMethod(EAP.EAPMethodID.EAP_TLS, null);
        } else if (eapMethod == WifiEnterpriseConfig.Eap.TTLS) {
            /* keep this table in sync with WifiEnterpriseConfig.Phase2 enum */
            NonEAPInnerAuth inner;
            switch (phase2Method) {
                case WifiEnterpriseConfig.Phase2.PAP:
                    inner = new NonEAPInnerAuth(NonEAPInnerAuth.NonEAPType.PAP);
                    break;
                case WifiEnterpriseConfig.Phase2.MSCHAP:
                    inner = new NonEAPInnerAuth(NonEAPInnerAuth.NonEAPType.MSCHAP);
                    break;
                case WifiEnterpriseConfig.Phase2.MSCHAPV2:
                    inner = new NonEAPInnerAuth(NonEAPInnerAuth.NonEAPType.MSCHAPv2);
                    break;
                default:
                    throw new IOException("TTLS phase2 method " +
                            phase2Method + " not valid for Passpoint");
            }
            return new EAPMethod(EAP.EAPMethodID.EAP_TTLS, inner);
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

    public CertType getCertType() {
        return mCertType;
    }

    public byte[] getFingerPrint() {
        return mFingerPrint;
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
