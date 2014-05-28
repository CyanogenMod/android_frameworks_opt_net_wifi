/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.passpoint;

import android.util.Log;
import android.util.Base64;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.Process;
import android.os.Binder;
import android.os.RemoteException;
import android.content.Intent;
import android.content.Context;
import android.security.*;
import android.widget.Toast;

import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.spec.PKCS8EncodedKeySpec;

import java.math.BigInteger;

import java.util.*;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

import com.android.org.bouncycastle.asn1.*;
import com.android.org.bouncycastle.jce.PKCS10CertificationRequest;
import com.android.org.bouncycastle.asn1.util.ASN1Dump;
import com.android.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.org.bouncycastle.asn1.x509.*;
import com.android.org.bouncycastle.asn1.util.ASN1Dump;
import com.android.org.bouncycastle.jce.provider.BouncyCastleProvider;
import com.android.org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import com.android.org.bouncycastle.util.io.pem.*;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.apache.http.Header;

public class WifiPasspointCertificate {
    private static final String TAG = "PasspointCertificate";
    private static final String TAG2 = "PasspointCertAdvance";

    private static Context sContext;
    private android.security.KeyStore mKeyStore;
    private PrivateKey mEnrollPrivKey;
    private static final String ENROLL_CLIENTCERT_ALIAS = "enroll_clientcert";
    private String mClientCertAlias;

    private String mMacAddress;
    private String mChallengePassword;
    private String mShaAlgorithm;
    private String mCommonName;
    private String mImeiMeid;

    private boolean mMacAddressRequired = false;
    private boolean mChallengePasswordRequired = false;
    private boolean mShaAlgorithmRequired = false;
    private boolean mCommonNameRequired = false;
    private boolean mImeiRequired = false;
    private boolean mMeidRequired = false;
    private boolean mDevidRequired = false;
    private boolean mIdkphs20authRequired = false;

    private TrustManager[] myTrustManagerArray = new TrustManager[] {
            new CertTrustManager()
    };

    private FileOperationUtil mFileOperation = new FileOperationUtil();

    private class HeaderProperty {
        private String key;
        private String value;

        public HeaderProperty(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    private static WifiPasspointCertificate instance = null;

    public KeyStore mHs20PKCS12KeyStore; //store client cert (extract from PKCS12)
    public String passWord = "wifi@123"; //hs20KeyStore password
    public static final String ENROLL = "Enroll";
    public static final String REENROLL = "ReEnroll";
    public static final String AAA_ROOT = "AAA_ROOT";
    public static final String SUBSCRIPTION_ROOT = "SUBSCRIPTION_ROOT";
    public static final String POLICY_ROOT = "POLICY_ROOT";

    private WifiPasspointCertificate() {
        initKeyStore();
    }

    public static WifiPasspointCertificate getInstance(Context ctxt) {
        if (instance == null) {
            instance = new WifiPasspointCertificate();
        }

        if (ctxt != null) {
            sContext = ctxt;
        }
        return instance;
    }

    public void initKeyStore() {
        try {
            //init keystore
            mKeyStore = android.security.KeyStore.getInstance();
            // store the key and the certificate chain for client certificate
            mHs20PKCS12KeyStore = KeyStore.getInstance("PKCS12", "BC");
            mHs20PKCS12KeyStore.load(null, null);
        } catch (Exception e) {
            Log.e(TAG, "initKeyStore err:" + e);
        }
    }

    public String getSubjectX500PrincipalFromPKCS12Keystore(String sha256FingerPrint) {
        if (!mKeyStore.contains(Credentials.WIFI + sha256FingerPrint)) {
            Log.e(TAG, "[getSubjectX500PrincipalFromPKCS12Keystore] client cert (SHA256: "
                    + sha256FingerPrint + ") does not exist !!!");
            return null;
        }

        try {
            String alias = new String(mKeyStore.get(Credentials.WIFI + sha256FingerPrint));
            Log.d(TAG, "[getSubjectX500PrincipalFromPKCS12Keystore] alias: " + alias);
            X509Certificate x509Cert = (X509Certificate) mHs20PKCS12KeyStore.getCertificate(alias);
            return x509Cert.getSubjectX500Principal().toString();
        } catch (Exception e) {
            Log.d(TAG, "getSubjectX500PrincipalFromPKCS12Keystore err:" + e);
        }
        return null;
    }

    public void setMacAddress(String address) {
        mMacAddress = address;
    }

    public void setImeiOrMeid(String s) {
        mImeiMeid = s;
    }

    private void cleanClientCertStore() {
        try {
            Enumeration enu = mHs20PKCS12KeyStore.aliases();
            while (enu.hasMoreElements()) {
                String alias = (String) enu.nextElement();
                Log.d(TAG2, "[cleanClientCertStore] KeyStore alias: " + alias);
                Log.d(TAG2, "[cleanClientCertStore] Certificate " + alias + ": "
                        + mHs20PKCS12KeyStore.getCertificate(alias).toString());
                mHs20PKCS12KeyStore.deleteEntry(alias);
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanClientCertStore err:" + e);
        }

    }

    public boolean verifyCertFingerprint(X509Certificate x509Cert, String sha256) {
        try {
            String fingerPrintSha256 = computeHash(x509Cert.getEncoded(), "SHA-256");

            if (fingerPrintSha256.equals(sha256)) {
                return true;
            }

        } catch (Exception e) {
            Log.e(TAG, "verifyCertFingerprint err:" + e);
        }
        return false;
    }

    public String computeHash(byte[] input, String type) throws NoSuchAlgorithmException,
            UnsupportedEncodingException {
        if (input == null) {
            return null;
        }

        MessageDigest digest = MessageDigest.getInstance(type);
        digest.reset();

        byte[] byteData = digest.digest(input);
        StringBuffer sb = new StringBuffer();

        for (int i = 0; i < byteData.length; i++) {
            sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    private void csrAttrsParse(byte[] csrattr, String chanPassword) {
        mMacAddressRequired = false;
        mChallengePasswordRequired = false;
        mShaAlgorithmRequired = false;
        mCommonNameRequired = false;
        mImeiRequired = false;
        mMeidRequired = false;
        mDevidRequired = false;
        mIdkphs20authRequired = false;

        DataOutputStream dataOutputStream;
        mChallengePassword = chanPassword;
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(csrattr);
            ASN1InputStream asn1_is = new ASN1InputStream(bais);
            ASN1Sequence derObj = (ASN1Sequence) asn1_is.readObject();
            for (int i = 0; i < derObj.size(); i++) {
                String oid = derObj.getObjectAt(i).toString();
                Log.d(TAG2, oid);
                if (oid.equals("1.3.6.1.1.1.1.22")) { //macAddress
                    mMacAddressRequired = true;
                    Log.d(TAG2, "macAddress required");
                } else if (oid.equals("1.3.6.1.4.1.40808.1.1.2")) { //id-kp-HS2.0-auth
                    Log.d(TAG2, "id-kp-HS2.0-auth required");
                    mIdkphs20authRequired = true;
                } else if (oid.equals("1.3.6.1.4.1.40808.1.1.3")) { //IMEI
                    Log.d(TAG2, "IMEI required");
                    mImeiRequired = true;
                } else if (oid.equals("1.3.6.1.4.1.40808.1.1.4")) { //MEID
                    Log.d(TAG2, "MEID required");
                    mMeidRequired = true;
                } else if (oid.equals("1.2.840.113549.1.9.7")) { // challenge password
                    Log.d(TAG2, "challengePassword required");
                    mChallengePasswordRequired = true;
                } else if (oid.equals("1.3.132.0.34")) {
                    //                    Description:
                    //                        NIST curve P-384 (covers "secp384r1", the elliptic curve domain listed in See SEC 2: Recommended Elliptic Curve Domain Parameters)
                    //                        Information:
                    //                        The SEC (Standards for Efficient Cryptography) curves provide elliptic curve domain parameters at commonly required security levels for use by implementers of ECC standards like ANSI X9.62, ANSI X9.63, IEEE P1363, and other standards.
                } else if (oid.equals("2.16.840.1.101.3.4.2.2")) { //SHA algorithm: "sha384"
                    mShaAlgorithm = "SHA-384";
                } else if (oid.equals("2.5.4.3")) {
                    Log.d(TAG2, "commonName required");
                    mCommonNameRequired = true;
                } else if (oid.equals("1.3.6.1.4.1.40808.1.1.5")) {
                    Log.d(TAG2, "DevID required");
                    mDevidRequired = true;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "csrAttrsParse err:" + e);
        }
    }

    private KeyPair createKeyPair() {
        KeyPair keyPair = null;
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            keyPair = keyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "createKeyPair err:" + e);
        }
        return keyPair;
    }

    private void genDERAttribute(DERObjectIdentifier oid, Object derObj, Vector oids,
            Vector values) {
        try {
            if (derObj instanceof GeneralName) {
                oids.add(oid);
                values.add(new X509Extension(false, new DEROctetString(new GeneralNames(
                        (GeneralName) derObj))));
            } else if (derObj instanceof DERPrintableString) {
                oids.add(oid);
                values.add(new X509Extension(false, new DEROctetString((DERPrintableString) derObj)));
            } else if (derObj instanceof DERIA5String) {
                oids.add(oid);
                values.add(new X509Extension(false, new DEROctetString((DERIA5String) derObj)));
            } else if (derObj instanceof DERBitString) {
                oids.add(oid);
                values.add(new X509Extension(false, new DEROctetString((DERBitString) derObj)));
            } else if (derObj instanceof DERObjectIdentifier) {
                oids.add(oid);
                values.add(new X509Extension(false,
                        new DEROctetString((DERObjectIdentifier) derObj)));
            } else if (derObj instanceof DERInteger) {
                oids.add(oid);
                values.add(new X509Extension(false, new DEROctetString((DERInteger) derObj)));
            } else if (derObj instanceof ExtendedKeyUsage) {
                oids.add(oid);
                values.add(new X509Extension(false, new DEROctetString((ExtendedKeyUsage) derObj)));
            }
        } catch (IOException e) {
            Log.e(TAG, "genDERAttribute err:" + e);
        }
    }

    private PKCS10CertificationRequest csrGenerate(String subjectDN) {
        try {
            final KeyPair kp = createKeyPair();
            mEnrollPrivKey = kp.getPrivate();

            //sha384
            if (!mShaAlgorithm.isEmpty()) {
                if ("2.16.840.1.101.3.4.2.2".equals(mShaAlgorithm)) {
                    Log.d(TAG2, "SHA 384 required");
                }
            }

            String signatureAlgorithm = "sha1withRSA";
            X500Principal subject;
            subject = new X500Principal(subjectDN); // this is the username
            //Attributes
            ASN1EncodableVector attributesVector = new ASN1EncodableVector();
            Vector oids = new Vector();
            Vector values = new Vector();
            X509Extensions extensions;
            Attribute attribute;

            if (mChallengePasswordRequired == true) {
                //challenge password
                ASN1ObjectIdentifier attrType = PKCSObjectIdentifiers.pkcs_9_at_challengePassword;
                ASN1Set attrValues = new DERSet(new DERPrintableString(mChallengePassword));
                attribute = new Attribute(attrType, attrValues);
                attributesVector.add(attribute);
            }

            //Extension
            //Extended Key Usage
            if (mIdkphs20authRequired == true) {
                genDERAttribute(X509Extensions.ExtendedKeyUsage, new ExtendedKeyUsage(
                        new KeyPurposeId("1.3.6.1.4.1.40808.1.1.2")), oids, values);//id-kp-HS2.0-auth
            }

            if (mMacAddressRequired == true) {
                //mac address
                genDERAttribute(new DERObjectIdentifier("1.3.6.1.1.1.1.22"), new DERIA5String(
                        mMacAddress), oids, values);
            }

            if (mImeiRequired == true) {
                //IMEI
                genDERAttribute(new DERObjectIdentifier("1.3.6.1.4.1.40808.1.1.3"),
                        new DERIA5String(mImeiMeid), oids, values);
            }

            if (mMeidRequired == true) {
                //MEID
                genDERAttribute(new DERObjectIdentifier("1.3.6.1.4.1.40808.1.1.4"),
                        new DERBitString(mImeiMeid.getBytes()), oids, values);
            }

            if (mDevidRequired == true) {
                //DevID
                genDERAttribute(new DERObjectIdentifier("1.3.6.1.4.1.40808.1.1.5"),
                        new DERPrintableString("imei:" + mImeiMeid), oids, values);
            }

            //complete attributes
            extensions = new X509Extensions(oids, values);
            attribute = new Attribute(
                    PKCSObjectIdentifiers.pkcs_9_at_extensionRequest,
                    new DERSet(extensions));
            attributesVector.add(attribute);
            DERSet attributesFinal = new DERSet(attributesVector);

            Security.addProvider(new BouncyCastleProvider());
            PKCS10CertificationRequest csr;
            if (mChallengePasswordRequired || mMacAddressRequired || mShaAlgorithmRequired) {
                DERSet attrs;
                //debugging for CSR attributes
                if ("true".equals(SystemProperties.get("persist.service.nocsrattrs"))) {
                    attrs = new DERSet();
                } else {
                    attrs = attributesFinal;
                }
                csr = new PKCS10CertificationRequest(signatureAlgorithm, subject,
                        kp.getPublic()/*identity.getPublicKey()*/, attrs, kp.getPrivate()/*privKey*/);
            } else {
                csr = new PKCS10CertificationRequest(signatureAlgorithm, subject,
                        kp.getPublic()/*identity.getPublicKey()*/, new DERSet(), kp.getPrivate()/*privKey*/);
            }
            Log.d(TAG2, ASN1Dump.dumpAsString((Object) csr, true));

            return csr;
        } catch (Exception e) {
            Log.e(TAG, "PKCS10CertificationRequest err:" + e);
        }
        return null;

    }

    public KeyStore getCredentialCertKeyStore(String sha256FingerPrint) {
        if (!mKeyStore.contains(Credentials.WIFI + sha256FingerPrint)) {
            return null;
        }

        try {
            String certAlias = new String(mKeyStore.get(Credentials.WIFI + sha256FingerPrint));
            List<X509Certificate> userCert = Credentials.convertFromPem(mKeyStore
                    .get(Credentials.WIFI + "HS20" + Credentials.USER_CERTIFICATE + certAlias));
            List<X509Certificate> caCerts = Credentials.convertFromPem(mKeyStore
                    .get(Credentials.WIFI + "HS20" + Credentials.CA_CERTIFICATE + certAlias));
            WifiPasspointCertificateHelper mCertificateHelper = WifiPasspointCertificateHelper
                    .getInstance();
            String key = WifiPasspointCertificateHelper.toMd5(userCert.get(0).getPublicKey()
                    .getEncoded());
            Map<String, byte[]> map = mCertificateHelper.getPkeyMap(certAlias);
            byte[] privKey = map.get(key);
            PKCS8EncodedKeySpec ks = new PKCS8EncodedKeySpec(privKey);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey pk = kf.generatePrivate(ks);

            int index = 0;
            Certificate[] chain = new Certificate[userCert.size() + caCerts.size()];

            for (Certificate item : userCert) {
                chain[index] = item;
                index++;
            }

            for (Certificate item : caCerts) {
                chain[index] = item;
                index++;
            }

            cleanClientCertStore();
            mHs20PKCS12KeyStore.setKeyEntry(certAlias, pk, passWord.toCharArray(), chain);
            return mHs20PKCS12KeyStore;
        } catch (Exception e) {
            Log.e(TAG, "getCredentialCertKeyStore err:" + e);
        }

        return null;
    }

    public boolean saveMappingOfEnrollCertAliasAndSha256(String alias, String sha256FingerPrint) {
        if (alias == null || sha256FingerPrint == null) {
            return false;
        }
        Log.d(TAG, "[saveMappingOfEnrollCertAliasAndSha256] SHA256FingerPrint: "
                + sha256FingerPrint);
        return mKeyStore.put(Credentials.WIFI + sha256FingerPrint, alias.getBytes(),
                android.security.KeyStore.UID_SELF, android.security.KeyStore.FLAG_ENCRYPTED);
    }

    public String getEnrollCertAlias() {
        return mClientCertAlias;
    }

    private boolean installEnrolledClientCert(PrivateKey privKey,
            List<X509Certificate> certificatePKCS7,
            List<X509Certificate> trustedRootCACert) {

        try {
            if (!mKeyStore.isUnlocked()) {
                Log.e(TAG, "Credential storage locked!!!");
                return false;
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "Credential storage is uninitialized!!! " + e);
            return false;
        }

        try {
            WifiPasspointCertificateHelper mCertificateHelper = WifiPasspointCertificateHelper
                    .getInstance();
            int rootCaAmount = trustedRootCACert.size();
            int clientCertAmount = certificatePKCS7.size();
            Certificate[] chain = new Certificate[rootCaAmount + clientCertAmount];

            //chain[2] = createMasterCert(caPubKey, caPrivKey);
            Log.d(TAG2, "rootCaAmount: " + rootCaAmount);
            Log.d(TAG2, "clientCertAmount: " + clientCertAmount);

            mClientCertAlias = mCertificateHelper.getSha1FingerPrint(certificatePKCS7.get(0));

            //Install client cert
            X509Certificate[] userCerts = certificatePKCS7
                    .toArray(new X509Certificate[certificatePKCS7.size()]);
            byte[] userCertsData = Credentials.convertToPem(userCerts);

            if (!mKeyStore.put(Credentials.WIFI + "HS20" + Credentials.USER_CERTIFICATE
                    + mClientCertAlias, userCertsData, android.security.KeyStore.UID_SELF,
                    android.security.KeyStore.FLAG_ENCRYPTED)) {
                Log.e(TAG, "Failed to install " + Credentials.WIFI + "HS20"
                        + Credentials.USER_CERTIFICATE + mClientCertAlias + " as user "
                        + android.security.KeyStore.UID_SELF);
                return false;
            }

            if (!mKeyStore.put(Credentials.WIFI + "HS20" + Credentials.USER_CERTIFICATE
                    + mClientCertAlias, userCertsData, Process.WIFI_UID,
                    android.security.KeyStore.FLAG_NONE)) {
                Log.e(TAG, "Failed to install " + Credentials.WIFI + "HS20"
                        + Credentials.USER_CERTIFICATE + mClientCertAlias + " as user "
                        + Process.WIFI_UID);
                return false;
            }

            //Install keypair
            byte[] prikey = privKey.getEncoded();
            byte[] pubkey = userCerts[0].getPublicKey().getEncoded();
            int flags = android.security.KeyStore.FLAG_ENCRYPTED;

            if (!mCertificateHelper.saveKeyPair(pubkey, prikey, mClientCertAlias)) {
                Log.e(TAG, "Failed to install private key as user "
                        + android.security.KeyStore.UID_SELF);
                return false;
            }

            if (mCertificateHelper.isHardwareBackedKey(prikey)) {
                // Hardware backed keystore is secure enough to allow for WIFI stack
                // to enable access to secure networks without user intervention
                Log.d(TAG, "Saving private key with FLAG_NONE for WIFI_UID");
                flags = android.security.KeyStore.FLAG_NONE;
            }

            if (!mKeyStore.importKey(Credentials.WIFI + "HS20" + Credentials.USER_PRIVATE_KEY
                    + mClientCertAlias, prikey, Process.WIFI_UID, flags)) {
                Log.e(TAG, "Failed to install " + Credentials.WIFI + "HS20"
                        + Credentials.USER_PRIVATE_KEY + mClientCertAlias + " as user "
                        + Process.WIFI_UID);
                return false;
            }

            //Install trusted ca certs
            X509Certificate[] caCerts = trustedRootCACert
                    .toArray(new X509Certificate[trustedRootCACert.size()]);
            byte[] caCertsData = Credentials.convertToPem(caCerts);

            if (!mKeyStore.put(Credentials.WIFI + "HS20" + Credentials.CA_CERTIFICATE
                    + mClientCertAlias, caCertsData, android.security.KeyStore.UID_SELF,
                    android.security.KeyStore.FLAG_ENCRYPTED)) {
                Log.e(TAG, "Failed to install " + Credentials.WIFI + "HS20"
                        + Credentials.CA_CERTIFICATE + mClientCertAlias + " as user "
                        + android.security.KeyStore.UID_SELF);
                return false;
            }

            if (!mKeyStore.put(Credentials.WIFI + "HS20" + Credentials.CA_CERTIFICATE
                    + mClientCertAlias, caCertsData, Process.WIFI_UID,
                    android.security.KeyStore.FLAG_NONE)) {
                Log.e(TAG, "Failed to install " + Credentials.WIFI + "HS20"
                        + Credentials.CA_CERTIFICATE + mClientCertAlias + " as user "
                        + Process.WIFI_UID);
                return false;
            }

            //Save SHA256 SHA1 mapping
            String sha256FingerPrint = computeHash(userCerts[0].getEncoded(), "SHA-256");
            saveMappingOfEnrollCertAliasAndSha256(mClientCertAlias, sha256FingerPrint);

            if ("true".equals(SystemProperties.get("persist.service.hs20.cert.dbg"))) {
                int index = 0;

                for (Certificate item : certificatePKCS7) {
                    chain[index] = item;
                    index++;
                }

                for (Certificate item : trustedRootCACert) {
                    chain[index] = item;
                    index++;
                }

                KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
                ks.load(null, null);

                ks.setKeyEntry(ENROLL_CLIENTCERT_ALIAS, privKey, null, chain);
                FileOutputStream fOut = new FileOutputStream(
                        "/data/data/est_client_cert.p12");
                ks.store(fOut, passWord.toCharArray());
                fOut.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "installEnrolledClientCert err:" + e);
            return false;
        }

        return true;
    }

    public byte[] httpClient(String serverUrl, String method) {
        try {
            boolean bGzipContent = false;
            boolean bBase64 = false;
            URL url = new URL(serverUrl);
            //get response content
            InputStream in = null;

            if (serverUrl.startsWith("HTTPS://") || serverUrl.startsWith("https://")) {
                //KeyStore keyStore = ...;
                //TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
                //tmf.init(keyStore)
                // TODO: Implement  HostnameVerifier
                HostnameVerifier hv = new HostnameVerifier()
                {
                    @Override
                    public boolean verify(String urlHostName, SSLSession session) {
                        Log.d(TAG2, "verify:" + urlHostName);
                        return true;
                    }
                };

                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, myTrustManagerArray, null);
                HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
                urlConnection.setSSLSocketFactory(context.getSocketFactory());
                urlConnection.setHostnameVerifier(hv);
                urlConnection.setDoOutput(true);
                urlConnection.setRequestMethod(method);
                //urlConnection.setRequestProperty("Connection", "close");
                urlConnection.setRequestProperty("Accept-Encoding", "gzip");

                //urlConnection.setRequestProperty("Content-Transfer-Encoding", "base64");

                //get response header
                boolean bPKCS7 = false;
                Log.d(TAG2, "Response code :" + String.valueOf(urlConnection.getResponseCode()));
                Log.d(TAG2, "Header :" + String.valueOf(urlConnection.getHeaderFields().toString()));

                if (urlConnection.getResponseCode() == 200) {
                    Map properties = urlConnection.getHeaderFields();
                    Set keys = properties.keySet();
                    List retList = new LinkedList();

                    for (Iterator i = keys.iterator(); i.hasNext();) {
                        String key = (String) i.next();
                        List values = (List) properties.get(key);

                        for (int j = 0; j < values.size(); j++) {
                            retList.add(new HeaderProperty(key, (String) values.get(j)));
                        }
                    }

                    for (int i = 0; i < retList.size(); i++) {
                        HeaderProperty hp = (HeaderProperty) retList.get(i);
                        // HTTP response code has null key
                        if (null == hp.getKey()) {
                            continue;
                        }

                        if (hp.getKey().equalsIgnoreCase("Content-Type")) {
                            if (hp.getValue().equals("application/pkcs7-mime") ||
                                    hp.getValue().equals("application/x-x509-ca-cert")) { //cacert and client cert
                                bPKCS7 = true;
                            }
                        }

                        if (hp.getKey().equalsIgnoreCase("Content-Encoding") &&
                                hp.getValue().equalsIgnoreCase("gzip")) {
                            bGzipContent = true;
                        }

                        if (hp.getKey().equalsIgnoreCase("Content-Transfer-Encoding") &&
                                hp.getValue().equalsIgnoreCase("base64")) {
                            bBase64 = true;
                        }
                    }
                }

                if (bPKCS7) {
                    in = urlConnection.getInputStream();
                    if (bGzipContent) {
                        in = getUnZipInputStream(in);
                    }

                    if (in != null)
                    {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];

                        while (true) {
                            int rd = in.read(buf, 0, 8192);
                            if (rd == -1) {
                                break;
                            }
                            bos.write(buf, 0, rd);
                        }

                        bos.flush();
                        byte[] byteArray = bos.toByteArray();
                        if (bBase64) {
                            String s = new String(byteArray);
                            Log.d(TAG2, "Content : " + s);
                            return Base64.decode(s, Base64.DEFAULT);
                        }

                        return byteArray;
                    }
                }

            } else {
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setDoOutput(true);
                urlConnection.setRequestMethod(method);
                //urlConnection.setRequestProperty("Connection", "close");
                urlConnection.setRequestProperty("Accept-Encoding", "gzip");

                //urlConnection.setRequestProperty("Content-Transfer-Encoding", "base64");

                //get response header
                boolean bPKCS7 = false;
                int responseCode = urlConnection.getResponseCode();
                Log.d(TAG2, "Response code :" + String.valueOf(responseCode));
                Log.d(TAG2, "Header :" + String.valueOf(urlConnection.getHeaderFields().toString()));

                if (responseCode == 200) {
                    Map properties = urlConnection.getHeaderFields();
                    Set keys = properties.keySet();
                    List retList = new LinkedList();

                    for (Iterator i = keys.iterator(); i.hasNext();) {
                        String key = (String) i.next();
                        List values = (List) properties.get(key);

                        for (int j = 0; j < values.size(); j++) {
                            retList.add(new HeaderProperty(key, (String) values.get(j)));
                        }
                    }

                    for (int i = 0; i < retList.size(); i++) {
                        HeaderProperty hp = (HeaderProperty) retList.get(i);
                        // HTTP response code has null key
                        if (null == hp.getKey()) {
                            continue;
                        }

                        if (hp.getKey().equalsIgnoreCase("Content-Type")) {
                            if (hp.getValue().equals("application/pkcs7-mime") ||
                                    hp.getValue().equals("application/x-x509-ca-cert")) {
                                bPKCS7 = true;
                            }
                        }

                        if (hp.getKey().equalsIgnoreCase("Content-Encoding") &&
                                hp.getValue().equalsIgnoreCase("gzip")) {
                            bGzipContent = true;
                        }

                        if (hp.getKey().equalsIgnoreCase("Content-Transfer-Encoding") &&
                                hp.getValue().equalsIgnoreCase("base64")) {
                            bBase64 = true;
                        }
                    }
                }

                if (bPKCS7) {
                    in = urlConnection.getInputStream();
                    if (bGzipContent) {
                        in = getUnZipInputStream(in);
                    }

                    if (in != null)
                    {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];

                        while (true) {
                            int rd = in.read(buf, 0, 8192);
                            if (rd == -1) {
                                break;
                            }
                            bos.write(buf, 0, rd);
                        }

                        bos.flush();
                        byte[] byteArray = bos.toByteArray();
                        if (bBase64) {
                            String s = new String(byteArray);
                            Log.d(TAG2, "Content : " + s);
                            return Base64.decode(s, Base64.DEFAULT);
                        }

                        return byteArray;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "httpClient err:" + e);
        }
        return null;
    }

    public boolean installServerTrustRoot(String certURL, String CertSHA256Fingerprint,
            String certName, boolean remediation) {
        Log.d(TAG, "[installServerTrustRoot] start for " + certName);
        Log.d(TAG, "[installServerTrustRoot] certURL " + certURL);

        if (certURL == null) {
            return true;
        } else if (certURL.isEmpty()) {
            return true;
        }

        int retryCount = 3;
        boolean isConnected = false;
        byte[] response = null;

        try {

            if (!mKeyStore.contains(Credentials.WIFI + CertSHA256Fingerprint)) { //not having yet
                Log.d(TAG, "[installServerTrustRoot] get ca from:" + certURL);

                while (retryCount > 0 && !isConnected) {
                    response = httpClient(certURL, "GET");

                    if (response == null) {
                        Log.d(TAG, "[installServerTrustRoot] response is null");
                        retryCount--;
                        Log.d(TAG, "Wait for retry:" + retryCount);
                        Thread.sleep(3 * 1000);
                    } else {
                        isConnected = true;
                        if ("true".equals(SystemProperties.get("persist.service.hs20.cert.dbg"))) {
                            String CaPath = "/data/data/" + certName + ".crt";
                            mFileOperation.writeBytesToFile(response, CaPath);
                        }
                        byte[] trustRoot = response;
                        CertificateFactory cf = CertificateFactory.getInstance("X.509");
                        X509Certificate x509Cert = (X509Certificate) cf
                                .generateCertificate(new ByteArrayInputStream(trustRoot));

                        if (verifyCertFingerprint(x509Cert, CertSHA256Fingerprint)) {
                            WifiPasspointCertificateHelper mCertificateHelper = WifiPasspointCertificateHelper
                                    .getInstance();
                            String aliasSHA1 = mCertificateHelper.getSha1FingerPrint(x509Cert);

                            if (certName.equals(AAA_ROOT)) {
                                if (!mKeyStore.put(Credentials.WIFI + "HS20"
                                        + Credentials.CA_CERTIFICATE + aliasSHA1,
                                        x509Cert.getEncoded(), Process.WIFI_UID,
                                        android.security.KeyStore.FLAG_NONE)) {
                                    Log.e(TAG, "[installServerTrustRoot] Failed to install "
                                            + Credentials.WIFI + "HS20"
                                            + Credentials.CA_CERTIFICATE + aliasSHA1 + " as user "
                                            + Process.WIFI_UID);
                                }
                            } else {
                                if (!mKeyStore.put(Credentials.WIFI + "HS20"
                                        + Credentials.CA_CERTIFICATE + aliasSHA1,
                                        x509Cert.getEncoded(), android.security.KeyStore.UID_SELF,
                                        android.security.KeyStore.FLAG_ENCRYPTED)) {
                                    Log.e(TAG, "[installServerTrustRoot] Failed to install "
                                            + Credentials.WIFI + "HS20"
                                            + Credentials.CA_CERTIFICATE + aliasSHA1 + " as user "
                                            + android.security.KeyStore.UID_SELF);
                                }

                                ByteArrayInputStream bais = new ByteArrayInputStream(trustRoot);
                                List<X509Certificate> caCerts = (List<X509Certificate>) cf
                                        .generateCertificates(bais);
                                mCertificateHelper.installCaCertsToKeyChain(sContext, caCerts);
                            }

                            saveMappingOfEnrollCertAliasAndSha256(aliasSHA1, CertSHA256Fingerprint);
                        } else {
                            Log.d(TAG, "Server Trust Root fingerprint verify fail!");
                            return false;
                        }
                    }
                }

                if (isConnected) {
                    if (remediation) {
                        // TODO: remove old AAA server trust root
                    }
                } else {
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "installServerTrustRoot err:" + e);
            return false;
        }

        return true;
    }

    public void installServerTrustRootsFromPEM(String certFile, String certType) {
        Log.d(TAG, "[installServerTrustRootsFromPEM] certFile: " + certFile);

        try {
            String serverCAs = mFileOperation.Read(certFile);
            String[] firstParts = serverCAs.split("-----BEGIN CERTIFICATE-----");
            for (String firstPart : firstParts) {
                if (firstPart.indexOf("-----END CERTIFICATE-----") == -1) {
                    continue;
                }
                String secPart = firstPart.substring(0,
                        firstPart.indexOf("-----END CERTIFICATE-----"));
                Log.d(TAG2, "secPart: " + secPart);
                byte[] serverRoot = Base64.decode(secPart, Base64.DEFAULT);
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate x509ServerRoot = (X509Certificate) cf
                        .generateCertificate(new ByteArrayInputStream(serverRoot));

                String aliasSHA1 = computeHash(x509ServerRoot.getEncoded(), "SHA-1");
                String fingerPrintSHA256 = computeHash(x509ServerRoot.getEncoded(), "SHA-256");
                Log.d(TAG, "SHA1 : " + aliasSHA1);
                Log.d(TAG, "SHA256 : " + fingerPrintSHA256);

                WifiPasspointCertificateHelper mCertificateHelper = WifiPasspointCertificateHelper
                        .getInstance();

                if (certType.equals("aaaroot")) {
                    if (!mKeyStore.put(Credentials.WIFI + "HS20" + Credentials.CA_CERTIFICATE
                            + aliasSHA1, x509ServerRoot.getEncoded(), Process.WIFI_UID,
                            android.security.KeyStore.FLAG_NONE)) {
                        Log.e(TAG, "[installServerTrustRoot] Failed to install " + Credentials.WIFI
                                + "HS20" + Credentials.CA_CERTIFICATE + aliasSHA1 + " as user "
                                + Process.WIFI_UID);
                    }
                } else {
                    if (!mKeyStore.put(Credentials.WIFI + "HS20" + Credentials.CA_CERTIFICATE
                            + aliasSHA1, x509ServerRoot.getEncoded(),
                            android.security.KeyStore.UID_SELF,
                            android.security.KeyStore.FLAG_ENCRYPTED)) {
                        Log.e(TAG, "[installServerTrustRoot] Failed to install " + Credentials.WIFI
                                + "HS20" + Credentials.CA_CERTIFICATE + aliasSHA1 + " as user "
                                + android.security.KeyStore.UID_SELF);
                    }

                    ByteArrayInputStream bais = new ByteArrayInputStream(serverRoot);
                    List<X509Certificate> caCerts = (List<X509Certificate>) cf
                            .generateCertificates(bais);
                    mCertificateHelper.installCaCertsToKeyChain(sContext, caCerts);
                }

                saveMappingOfEnrollCertAliasAndSha256(aliasSHA1, fingerPrintSHA256);

            }
        } catch (Exception e) {
            Log.d(TAG2, "installServerTrustRootsFromPEM err:" + e);
        }
    }

    public boolean connectESTServer(final String serverUrl, String operation,
            final String digestUsername, final String digestPassword, final String subjectDN) {
        Log.d(TAG2, "Certificate Enrollment Starts...(EST)");

        if ("true".equals(SystemProperties.get("persist.service.soapenroltest"))) {
            return true;
        }

        Security.addProvider(new BouncyCastleProvider());
        try {
            byte[] in = null;
            //Get CACert
            in = estHttpClient(serverUrl, "GET", "/cacerts", operation, null, digestUsername,
                    digestPassword);
            if (in == null) {
                Log.d(TAG2, "Something wrong getting CACert");
                return false;
            }
            if ("true".equals(SystemProperties.get("persist.service.hs20.cert.dbg"))) {
                mFileOperation.writeBytesToFile(in,
                        "/data/data/est_ca_cert.p7b");
            }

            Provider provBC = Security.getProvider("BC");
            CertificateFactory cf = CertificateFactory.getInstance("X.509", provBC);

            ByteArrayInputStream bais = new ByteArrayInputStream(in);
            List<X509Certificate> gRootCA = (List<X509Certificate>) cf
                    .generateCertificates(bais);
            for (X509Certificate item : gRootCA) {
                Log.d(TAG2, "gRootCA: \r" + item);
            }
            //Get CSR Attributes
            in = estHttpClient(serverUrl, "GET", "/csrattrs", operation, null, digestUsername,
                    digestPassword);
            if (in == null) {
                Log.d(TAG2, "Something wrong getting CSR Attributes");
                return false;
            }
            //Parse CSR Attribute
            csrAttrsParse(in, digestPassword);

            //Enroll
            //Build CSR according to CSR Attributes
            PKCS10CertificationRequest csrPkcs10;
            if (operation.equals(REENROLL)) {
                csrPkcs10 = csrGenerate(subjectDN);
            } else {
                csrPkcs10 = csrGenerate("CN=" + digestUsername
                        + ", OU=Google, O=Google, L=MountainView, ST=CA, C=US");
            }

            byte[] csr = csrPkcs10.getEncoded();
            //start Enroll
            if (operation.equals(ENROLL)) {
                in = estHttpClient(serverUrl, "POST", "/simpleenroll", operation, csr,
                        digestUsername, digestPassword);
                if (in == null) {
                    Log.d(TAG2, "Something wrong Enrolling");
                    return false;
                }
                Log.d(TAG2, "Enrolled Client Certificate: \r" + new String(in));

                if ("true".equals(SystemProperties.get("persist.service.hs20.cert.dbg"))) {
                    mFileOperation.writeBytesToFile(in,
                            "/data/data/est_client_cert.p7b");
                }

                bais = new ByteArrayInputStream(in);
                List<X509Certificate> x509Cert = (List<X509Certificate>) cf
                        .generateCertificates(bais);
                for (X509Certificate item : x509Cert) {
                    Log.d(TAG2, "x509Cert: \r" + item);
                }

                return installEnrolledClientCert(mEnrollPrivKey, x509Cert, gRootCA);
            } else if (operation.equals(REENROLL)) {
                in = estHttpClient(serverUrl, "POST", "/simplereenroll", operation, csr,
                        digestUsername, digestPassword);
                if (in == null) {
                    Log.d(TAG2, "Something wrong Re-Enrolling");
                    return false;
                }
                Log.d(TAG2, "Enrolled Client Certificate: \r" + new String(in));

                if ("true".equals(SystemProperties.get("persist.service.hs20.cert.dbg"))) {
                    mFileOperation.writeBytesToFile(in,
                            "/data/data/est_client_cert.p7b");
                }

                bais = new ByteArrayInputStream(in);
                List<X509Certificate> x509Cert = (List<X509Certificate>) cf
                        .generateCertificates(bais);
                for (X509Certificate item : x509Cert) {
                    Log.d(TAG2, "x509Cert: \r" + item);
                }

                return installEnrolledClientCert(mEnrollPrivKey, x509Cert, gRootCA);
            }

        } catch (Exception e) {
            Log.d(TAG2, "Certificate Enrollment fail");
            Log.e(TAG, "err:" + e);
            return false;
        }

        return true;
    }

    public byte[] estHttpClient(String serverUrl, String method, String suffix, String operation,
            byte[] csr, final String digestUsername, final String digestPassword) {
        try {
            boolean bGzipContent = false;
            URI url = new URI(serverUrl + suffix);
            WifiPasspointHttpClient hc = null;
            //get response content
            InputStream in = null;

            if (serverUrl.startsWith("HTTPS://") || serverUrl.startsWith("https://")) {
                if (operation.equals(REENROLL)) {
                    Log.d(TAG, "[estHttpClient]: re-enroll");
                    if (mHs20PKCS12KeyStore.aliases().hasMoreElements()) {
                        hc = new WifiPasspointHttpClient(mHs20PKCS12KeyStore, passWord.toCharArray());
                    } else {
                        Log.d(TAG, "client cert is not installed in passpoint PKCS12 keystore");
                        return null;
                    }
                } else {
                    Log.d(TAG, "[estHttpClient]: enroll");
                    hc = new WifiPasspointHttpClient(null, null);
                }

                if (digestUsername != null && digestPassword != null) {
                    hc.setAuthenticationCredentials(new UsernamePasswordCredentials(digestUsername,
                            digestPassword));

                }

                HttpResponse httpResp = null;
                Header[] requestHeaders;
                List<BasicHeader> basicHeaders = new ArrayList<BasicHeader>();

                basicHeaders.add(new BasicHeader(hc.ACCEPT_ENCODING_HEADER, "gzip"));

                if (method.equals("POST") && csr != null) {
                    byte[] base64csr = Base64.encode(csr, Base64.DEFAULT);
                    String base64String = new String(base64csr);
                    String pemCsr = "-----BEGIN CERTIFICATE REQUEST-----\n" +
                            base64String.replaceAll("(.{64})", "$1\n") +
                            "\n-----END CERTIFICATE REQUEST-----";
                    pemCsr = base64String.replaceAll("(.{64})", "$1\n");
                    Log.d(TAG, "CSR: " + pemCsr);
                    basicHeaders.add(new BasicHeader(hc.CONTENT_LENGTH_HEADER,
                            "" + pemCsr.getBytes().length));
                    basicHeaders.add(new BasicHeader(hc.CONTENT_TRANSFER_ENCODING, "base64"));
                    requestHeaders = basicHeaders.toArray(new Header[basicHeaders.size()]);
                    httpResp = hc
                            .post(url, "application/pkcs10", pemCsr.getBytes(), requestHeaders);
                }

                //get response header
                boolean bPKCS7 = false;
                boolean bCSRAttrs = false;
                int statusCode = httpResp.getStatusLine().getStatusCode();
                Log.d(TAG2, "Response code :" + String.valueOf(statusCode));
                if (statusCode == 200) {
                    String contentType = httpResp.getEntity().getContentType().getValue();
                    if ("application/pkcs7-mime".equals(contentType)) { //cacert and client cert
                        bPKCS7 = true;
                    } else if ("application/csrattrs".equals(contentType)) { // csr attributes
                        bCSRAttrs = true;
                    }

                    if ("gzip".equalsIgnoreCase(httpResp.getEntity().getContentEncoding()
                            .getValue())) {
                        bGzipContent = true;
                    }
                }

                if (bPKCS7 || bCSRAttrs) {
                    in = httpResp.getEntity().getContent();
                    if (bGzipContent) {
                        in = getUnZipInputStream(in);
                    }

                    if (in != null)
                    {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];

                        while (true) {
                            int rd = in.read(buf, 0, 8192);
                            if (rd == -1) {
                                break;
                            }
                            bos.write(buf, 0, rd);
                        }

                        bos.flush();
                        byte[] byteArray = bos.toByteArray();
                        if (org.apache.commons.codec.binary.Base64.isArrayByteBase64(byteArray)) {
                            String s = new String(byteArray);
                            Log.d(TAG2, "Content : " + s);
                            return Base64.decode(new String(byteArray), Base64.DEFAULT);
                        }

                        return byteArray;
                    }
                }
            } else {
                hc = new WifiPasspointHttpClient(null, null);
                if (digestUsername != null && digestPassword != null) {
                    hc.setAuthenticationCredentials(new UsernamePasswordCredentials(digestUsername,
                            digestPassword));

                }

                HttpResponse httpResp = null;
                Header[] requestHeaders;
                List<BasicHeader> basicHeaders = new ArrayList<BasicHeader>();

                basicHeaders.add(new BasicHeader(hc.ACCEPT_ENCODING_HEADER, "gzip"));
                if (method.equals("POST") && csr != null) {
                    byte[] base64csr = Base64.encode(csr, Base64.DEFAULT);
                    String base64String = new String(base64csr);
                    String pemCsr = "-----BEGIN CERTIFICATE REQUEST-----\n" +
                            base64String.replaceAll("(.{64})", "$1\n") +
                            "\n-----END CERTIFICATE REQUEST-----";

                    pemCsr = base64String.replaceAll("(.{64})", "$1\n");
                    Log.d(TAG, "CSR: " + pemCsr);
                    basicHeaders.add(new BasicHeader(hc.CONTENT_LENGTH_HEADER,
                            "" + pemCsr.getBytes().length));
                    basicHeaders.add(new BasicHeader(hc.CONTENT_TRANSFER_ENCODING, "base64"));
                    requestHeaders = basicHeaders.toArray(new Header[basicHeaders.size()]);
                    httpResp = hc
                            .post(url, "application/pkcs10", pemCsr.getBytes(), requestHeaders);

                }

                //get response header
                boolean bPKCS7 = false;
                boolean bCSRAttrs = false;
                int statusCode = httpResp.getStatusLine().getStatusCode();
                Log.d(TAG2, "Response code :" + String.valueOf(statusCode));
                if (statusCode == 200) {
                    String contentType = httpResp.getEntity().getContentType().getValue();
                    if ("application/pkcs7-mime".equals(contentType)) { //cacert and client cert
                        bPKCS7 = true;
                    } else if ("application/csrattrs".equals(contentType)) { // csr attributes
                        bCSRAttrs = true;
                    }

                    if ("gzip".equalsIgnoreCase(httpResp.getEntity().getContentEncoding()
                            .getValue())) {
                        bGzipContent = true;
                    }
                }

                if (bPKCS7 || bCSRAttrs) {
                    in = httpResp.getEntity().getContent();
                    if (bGzipContent) {
                        in = getUnZipInputStream(in);
                    }

                    if (in != null)
                    {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];

                        while (true) {
                            int rd = in.read(buf, 0, 8192);
                            if (rd == -1) {
                                break;
                            }
                            bos.write(buf, 0, rd);
                        }

                        bos.flush();
                        byte[] byteArray = bos.toByteArray();
                        if (org.apache.commons.codec.binary.Base64.isArrayByteBase64(byteArray)) {
                            String s = new String(byteArray);
                            Log.d(TAG2, "Content : " + s);
                            return Base64.decode(new String(byteArray), Base64.DEFAULT);
                        }
                        return byteArray;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "estHttpClient err:" + e);
        }
        return null;
    }

    private InputStream getUnZipInputStream(InputStream inputStream) throws IOException {
        /* workaround for Android 2.3
           (see http://stackoverflow.com/questions/5131016/)
        */
        try {
            return (GZIPInputStream) inputStream;
        } catch (ClassCastException e) {
            return new GZIPInputStream(inputStream);
        }
    }

    private class CertTrustManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] arg0, String arg1) {
            Log.d(TAG, "[checkClientTrusted] " + arg0 + arg1);
        }

        public void checkServerTrusted(final X509Certificate[] arg0, String arg1)
                throws CertificateException {
            Log.d(TAG, "[checkServerTrusted] X509Certificate amount:" + arg0.length
                    + ", cryptography: " + arg1);
        }

        public X509Certificate[] getAcceptedIssuers() {
            Log.d(TAG, "[getAcceptedIssuers] ");
            return null;
        }
    }

    public class FileOperationUtil {
        private static final String TAG = "FileOperationUtil";

        public String Read(String file) {
            String text = null;

            try {
                FileInputStream fis = new FileInputStream(file);
                BufferedInputStream bis = new BufferedInputStream(fis);
                DataInputStream dis = new DataInputStream(fis);

                String buf;
                while ((buf = dis.readLine()) != null) {
                    Log.d(TAG, "Read:" + buf);
                    text += buf;
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            return text;
        }

        public byte[] Read(File file) throws IOException {

            ByteArrayOutputStream ous = null;
            try {
                byte[] buffer = new byte[4096];
                ous = new ByteArrayOutputStream();
                InputStream ios = new FileInputStream(file);
                int read = 0;
                while ((read = ios.read(buffer)) != -1) {
                    ous.write(buffer, 0, read);
                }
            } finally {
                try {
                    if (ous != null)
                        ous.close();
                } catch (IOException e) {
                }
            }
            return ous.toByteArray();
        }

        public void writeBytesToFile(byte[] bytes, String filePath) {
            try {
                FileOutputStream fos;

                fos = new FileOutputStream(filePath);
                fos.write(bytes);
                fos.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

}
