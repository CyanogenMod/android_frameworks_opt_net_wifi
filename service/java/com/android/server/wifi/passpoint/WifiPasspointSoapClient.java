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

import android.net.wifi.passpoint.WifiPasspointCredential;
import android.net.wifi.passpoint.WifiPasspointDmTree;
import android.net.wifi.passpoint.WifiPasspointManager;
import android.os.*;
import android.util.Log;
import android.net.Uri;
import android.net.wifi.*;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.security.Credentials;

import com.android.internal.util.StateMachine;
import com.android.server.wifi.passpoint.WifiPasspointClient.AuthenticationElement;
import com.android.org.conscrypt.TrustManagerImpl;
import com.android.org.bouncycastle.asn1.*;
import com.android.org.bouncycastle.asn1.util.ASN1Dump;
import com.android.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.org.bouncycastle.asn1.x509.*;
import com.android.org.bouncycastle.jce.PKCS10CertificationRequest;
import com.android.org.bouncycastle.jce.provider.BouncyCastleProvider;
import com.android.org.bouncycastle.jce.exception.ExtCertPathValidatorException;

import org.ksoap2.*;
import org.ksoap2.serialization.*;
import org.ksoap2.transport.*;
import org.ksoap2.kobjects.base64.Base64;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.apache.harmony.security.x509.OtherName;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.apache.http.Header;

import java.util.*;
import java.util.zip.GZIPInputStream;
import java.math.BigInteger;
import java.io.*;
import java.net.*;
import java.security.cert.*;
import java.security.*;
import java.security.KeyStore.PasswordProtection;

import javax.net.ssl.*;
import javax.xml.parsers.*;
import javax.xml.transform.TransformerException;
import javax.security.auth.callback.*;
import javax.security.auth.x500.X500Principal;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;

/**
 * TODO: doc
 */
public class WifiPasspointSoapClient implements WifiPasspointClient.SoapClient {
    private StateMachine mTarget;
    private WifiPasspointClient.DmClient mDmClient;
    private static final String TAG = "SoapClient";
    private static final String TAG2 = "SoapClAdvance";
    private static final String NAMESPACE_NS = "http://www.wi-fi.org/specifications/hotspot2dot0/v1.0/spp";
    private static final String NAMESPACE_DM = "http://www.openmobilealliance.org/tech/DTD/dm_ddf-v1_2.dtd";
    private static final String WIFI_SOAP_POST_DEV_DATA = "sppPostDevData";
    private static final String WIFI_SOAP_UPDATE_RESPONSE = "sppUpdateResponse";
    private static final String WIFI_SOAP_USER_INPUT_RESPONSE = "sppUserInputResponse";
    private static final String WIFI_SOAP_ERROR_CODE = "errorCode";
    private static final String WIFI_SOAP_MGMTREE = "MgmtTree";
    private static final String WIFI_SOAP_REQ_REASON = "requestReason";
    private static final String WIFI_SOAP_REDIRECTURL = "redirectURI";
    private static final String WIFI_SOAP_SESSIONID = "sessionID";
    private static final String WIFI_SOAP_SYNCML_DMDDF_1_2 = "syncml:dmddf1.2";
    private static final String WIFI_SOAP_S_SPP_VERSION = "supportedSPPVersions";
    private static final String WIFI_SOAP_S_MOLIST = "supportedMOList";
    private static final String WIFI_SOAP_S_MODEVINFO = "urn:oma:mo:oma-dm-devinfo:1.0";
    private static final String WIFI_SOAP_S_MODEVDETAIL = "urn:oma:mo:oma-dm-devdetail:1.0";
    private static final String WIFI_SOAP_S_MOSUBSCRIPTION = "urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0";
    private static final String WIFI_SOAP_S_MOHS20 = "urn:wfa:mo-ext:hotspot2dot0-devdetail-ext:1.0";
    private static final String WIFI_SOAP_MO_CONTAINER = "moContainer";
    private static final String WIFI_SOAP_MO_URN = "moURN";
    private static final String WIFI_SOAP_MO_XMLNS = "xmlns";
    private static final String WIFI_SOAP_SPP_VERSION = "sppVersion";
    private static final String WIFI_SOAP_SPP_EXCHANGE_COMPLETE = "sppExchangeComplete";
    private static final String WIFI_SOAP_SPP_ERROR = "sppError";
    private static final String WIFI_SOAP_SPP_NOMOUPDATE = "noMOUpdate";
    private static final String WIFI_SOAP_SPP_STATUS = "sppStatus";
    private static final String WIFI_SOAP_SPP_STATUS_OK = "OK";
    private static final String WIFI_SOAP_SPP_STATUS_PROVISION_COMPLETE = "Provisioning complete, request sppUpdateResponse";
    private static final String WIFI_SOAP_SPP_STATUS_REMEDIATION_COMPLETE = "Remediation complete, request sppUpdateResponse";
    private static final String WIFI_SOAP_SPP_STATUS_UPDATE_COMPLETE = "Update complete, request sppUpdateResponse";
    private static final String WIFI_SOAP_SPP_STATUS_NO_UPDATE_AVAILABLE = "No update available at this time";
    private static final String WIFI_SOAP_SPP_STATUS_EXCHANGE_COMPLETE = "Exchange complete, release TLS connection";
    private static final String WIFI_SOAP_SPP_STATUS_ERROR_OCCURRED = "Error occurred";
    private static final String DEVICE_OBJECT = "The interior node holding all devinfo objects";
    private static final String CONTENT_TYPE_XML_CHARSET_UTF_8 = "text/xml;charset=utf-8";
    private static final String CONTENT_TYPE_SOAP_XML_CHARSET_UTF_8 = "application/soap+xml;charset=utf-8";
    private String mRedirectUrl;
    // Subscription Provisioning request reason
    public static final String SUB_REGISTER = "Subscription registration";
    public static final String CERT_ENROLL_SUCCESS = "Certificate enrollment completed";
    public static final String CERT_ENROLL_FAIL = "Certificate enrollment failed";
    public static final String USER_INPUT_COMPLETED = "User input completed";
    public static final String SUB_REMEDIATION = "Subscription remediation";
    // for EAP-SIM
    public static final String SUB_PROVISION = "Subscription provisioning";
    public static final String SUB_MO_UPLOAD = "MO upload";

    // Policy Provisioning request reason
    public static final String POL_UPDATE = "Policy update";
    public static final String POL_MO_UPLOAD = "MO upload";

    // send response error message
    public static final String WIFI_SOAP_UPDATE_RESPONSE_OK = "OK";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_PERMISSION_DENY = "Permission denied";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_COMMAND_FAILED = "Command failed";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_MO_ADD_UPDATE_FAIL = "MO addition or update failed";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_DEVICE_FULL = "Device full";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_BAD_MGMTREE_URI = "Bad management tree URI";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_REQUEST_ENTITY_TOO_LARGE = "Requested entity too large";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_COMMAND_NOT_ALLOWED = "Command not allowed";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_COMMAND_NOT_EXEC_DUE_TO_USER = "Command not executed due to user";
    public static final String WIFI_SOAP_UPDATE_RESPONSE_NOT_FOUND = "Not found";

    private int mProcedureType;
    private static final int SUBSCRIPTION_PROVISION = 1;
    private static final int SUBSCRIPTION_REMEDIATION = 2;
    private static final int POLICY_UPDATE = 3;
    private static final int SUBSCRIPTION_SIM_PROVISION = 4;
    private PpsmoParser mPpsmoParser = new PpsmoParser();

    private Context mContext;

    // HTTP digest
    private String mDigestUsername;
    private String mDigestPassword;

    // Settion ID
    private String mSessionID;

    // Policy
    private String mRequestReason;

    // Credential
    private static String mSoapWebUrl;
    private static String mOSUServerUrl;
    private static String mREMServerUrl;
    private String mImsi;
    private String mEnrollmentServerURI;
    private String mEnrollmentServerCert;

    private String mOSUFriendlyName;

    TrustManager[] myTrustManagerArray = new TrustManager[] {
            new CertTrustManager()
    };
    private static KeyStore sHs20Pkcs12KeyStore;
    private String mSpFqdn;
    private String mSpFqdnFromMo;
    private static String mOSULanguage;
    private static String iconFileName;
    private static String iconHash;
    private static String enrollDigestUsername;
    private static String enrollDigestPassword;
    private WifiPasspointCertificate mPasspointCertificate;
    private static final int NO_CLIENT_AUTH = 0;
    private static final int CLIENT_CERT = 1;
    private static final int RENEGOTIATE_CERT = 2;
    // pre-provisioned certificate by a provider
    private static String providerIssuerName;

    private WifiPasspointDmTree mSoapTree;
    private WifiPasspointDmTreeHelper mTreeHelper;
    private WifiPasspointCredential mCred;

    // UploadMO
    private static String mUploadMO;

    // soap dump
    private String timeString;

    private static int procedureDone;
    private static int managementTreeUpdateCount = 0;

    private WifiPasspointCertificate.FileOperationUtil mFileOperation;

    public WifiPasspointSoapClient(Context context, WifiPasspointClient.DmClient client) {
        mContext = context;
        mDmClient = client;
    }

    @Override
    public void init(StateMachine target) {
        Log.d(TAG, "[init]");
        mTarget = target;
        mTreeHelper = new WifiPasspointDmTreeHelper();
        mRequestReason = null;
        mDigestUsername = null;
        mDigestPassword = null;

        // Credential
        mSoapWebUrl = null;
        mOSUServerUrl = null;
        mREMServerUrl = null;

        // Certificate
        mPasspointCertificate = WifiPasspointCertificate.getInstance(null);
        mFileOperation = mPasspointCertificate.new FileOperationUtil();
    }

    @Override
    public void startSubscriptionProvision(String serverUrl) {
        Log.d(TAG, "Run startSubscriptionProvision with " + serverUrl);
        mProcedureType = SUBSCRIPTION_PROVISION;
        mOSUServerUrl = serverUrl;
        mSoapWebUrl = serverUrl;
        mSpFqdnFromMo = null;
        mRequestReason = SUB_REGISTER;
        subscriptionProvision();
    }

    @Override
    public void startRemediation(final String serverUrl, WifiPasspointCredential cred) {
        Log.d(TAG, "Run startRemediation with " + serverUrl);
        mProcedureType = SUBSCRIPTION_REMEDIATION;
        mSoapWebUrl = serverUrl;
        mREMServerUrl = serverUrl;
        mSpFqdnFromMo = null;

        if ("SIM".equals(cred.getType())) {
            mRequestReason = SUB_PROVISION;
        } else {
            mRequestReason = SUB_REMEDIATION;
        }

        if (cred != null) {
            mCred = cred;
        } else {
            Log.d(TAG, "cred is null");
            return;
        }

        remediation();
    }

    @Override
    public void startPolicyProvision(final String serverUrl, WifiPasspointCredential cred) {
        Log.d(TAG, "Run startPolicyProvision with " + serverUrl);
        mProcedureType = POLICY_UPDATE;
        mSoapWebUrl = serverUrl;
        mSpFqdnFromMo = null;
        mRequestReason = POL_UPDATE;

        if (cred != null) {
            mCred = cred;
        } else {
            Log.d(TAG, "cred is null");
            return;
        }

        policyProvision();
    }

    @Override
    public void setWifiTree(WifiPasspointDmTree tree) {
        mSoapTree = tree;
    }

    @Override
    public void notifyBrowserRedirected() {
        mRequestReason = USER_INPUT_COMPLETED;
        if (mProcedureType == SUBSCRIPTION_PROVISION) {
            subscriptionProvision();
        } else if (mProcedureType == SUBSCRIPTION_REMEDIATION) {
            remediation();
        }
    }

    @Override
    public void setBrowserRedirectUri(String uri) {
        mRedirectUrl = uri;
    }

    @Override
    public void setAuthenticationElement(AuthenticationElement ae) {
        Log.d(TAG, "set SPFQDN:" + ae.spFqdn);
        Log.d(TAG, "OSU Friendly Name:" + ae.osuFriendlyName);
        Log.d(TAG, "Default language name:" + ae.osuDefaultLanguage);

        mSoapWebUrl = null;
        mOSUServerUrl = null;
        mREMServerUrl = null;
        mSpFqdn = ae.spFqdn;
        mOSUFriendlyName = ae.osuFriendlyName;
        mOSULanguage = ae.osuDefaultLanguage;
        try {
            iconFileName = ae.osuIconfileName;
            iconHash = mPasspointCertificate.computeHash(
                    mFileOperation.Read(new File("/data/misc/wifi/icon/" + iconFileName)),
                    "SHA-256");// ext: image/xxx
            Log.d(TAG, "Icon file name:" + iconFileName + " icon hash:" + iconHash);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setRemediationHttpDigest() {
        Log.d(TAG, "[setRemediationHttpDigest]");
        mDigestUsername = null;
        mDigestPassword = null;
        String eapType = mCred.getType();
        Log.d(TAG, "eapType = " + eapType);
        if (eapType.equals("TTLS")) {
            String subscriptionUpdateUsername = mCred.getSubscriptionUpdateUsername();
            String subscriptionUpdatePassword = mCred.getSubscriptionUpdatePassword();
            String credentialUsername = mCred.getUserName();
            String credentialPassword = mCred.getPassword();

            if (subscriptionUpdateUsername != null) {
                Log.d(TAG, "subscriptionUpdateUsername = " + subscriptionUpdateUsername
                        + ", subscriptionUpdatePassword = " + subscriptionUpdatePassword);
                if (!subscriptionUpdateUsername.isEmpty()) {
                    mDigestUsername = subscriptionUpdateUsername;
                    mDigestPassword = subscriptionUpdatePassword;
                    Log.d(TAG,
                            "digest using Subscription Update, mDigestUsername/mDigestPassword: "
                                    + mDigestUsername + "/" + mDigestPassword);
                } else if (credentialUsername != null && !credentialUsername.isEmpty()) {
                    Log.d(TAG, "credentialUsername = " + credentialUsername
                            + ", credentialPassword = " + credentialPassword);
                    mDigestUsername = credentialUsername;
                    mDigestPassword = credentialPassword;
                    Log.d(TAG, "digest using credential, mDigestUsername/digestPassword: "
                            + mDigestUsername + "/" + mDigestPassword);
                }
            } else if (credentialUsername != null && !credentialUsername.isEmpty()) {
                Log.d(TAG, "credentialUsername = " + credentialUsername + ", credentialPassword = "
                        + credentialPassword);
                mDigestUsername = credentialUsername;
                mDigestPassword = credentialPassword;
                Log.d(TAG, "digest using credential, mDigestUsername/digestPassword: "
                        + mDigestUsername + "/" + mDigestPassword);
            }
        }
    }

    private void setPolicyUpdateHttpDigest() {
        mDigestUsername = null;
        mDigestPassword = null;

        String eapType = mCred.getType();
        if (eapType.equals("TTLS")) {
            String policyUpdateUsername = mCred.getPolicyUpdateUsername();
            String policyUpdatePassword = mCred.getPolicyUpdatePassword();
            String credentialUsername = mCred.getUserName();
            String credentialPassword = mCred.getPassword();

            if (policyUpdateUsername != null) {
                if (!policyUpdateUsername.isEmpty()) {
                    mDigestUsername = policyUpdateUsername;
                    mDigestPassword = policyUpdatePassword;
                    Log.d(TAG, "digest using Policy Update, mDigestUsername/mDigestPassword: "
                            + mDigestUsername + "/" + mDigestPassword);
                } else if (credentialUsername != null && !credentialUsername.isEmpty()) {
                    mDigestUsername = credentialUsername;
                    mDigestPassword = credentialPassword;
                    Log.d(TAG, "digest using credential, mDigestUsername/mDigestPassword: "
                            + mDigestUsername + "/" + mDigestPassword);
                }
            } else if (credentialUsername != null && !credentialUsername.isEmpty()) {
                mDigestUsername = credentialUsername;
                mDigestPassword = credentialPassword;
                Log.d(TAG, "digest using credential, mDigestUsername/mDigestPassword: "
                        + mDigestUsername + "/" + mDigestPassword);
            }
        }

    }

    private String getDeviceId() {
        TelephonyManager tm =
                (TelephonyManager) (mContext.getSystemService(Context.TELEPHONY_SERVICE));
        if (null == tm) {
            return new String("000000000000000");
        }

        String imei = tm.getDeviceId();
        if (imei == null || imei.isEmpty()) {
            return new String("000000000000000");
        }

        return imei;
    }

    private void startCertificateEnroll(final String operation) {
        Log.d(TAG, "enrollmentServerURI: " + mEnrollmentServerURI + ", operation: " + operation);
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        boolean enrollSuccess;
        Message msg = null;
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null) {
                Log.d(TAG, "no ConnectionInfo found");
                return;
            }
            mPasspointCertificate.setMacAddress(wifiInfo.getMacAddress());
            mPasspointCertificate.setImeiOrMeid(getDeviceId());
            if (operation.equals(mPasspointCertificate.ENROLL)) {
                enrollSuccess = mPasspointCertificate.connectESTServer(mEnrollmentServerURI,
                        operation, enrollDigestUsername, enrollDigestPassword, null);
            } else {
                String credentialCertSHA256Fingerprint = mCred.getCertSha256Fingerprint();

                String subjectDN = mPasspointCertificate
                        .getSubjectX500PrincipalFromPKCS12Keystore(credentialCertSHA256Fingerprint);
                Log.d(TAG, "subjectDN:" + subjectDN);
                if (subjectDN == null) {
                    enrollSuccess = false;
                } else {
                    enrollSuccess = mPasspointCertificate.connectESTServer(mEnrollmentServerURI,
                            operation, enrollDigestUsername, enrollDigestPassword, subjectDN);
                }
            }
            Log.d(TAG, "Certificate Enrolled :" + enrollSuccess);

            if (!enrollSuccess) {
                Log.d(TAG, "Certificate Enroll fail!!!");
            }
        } else {
            Log.d(TAG, "Wifi service not exist, OSU stops");
            return;
        }

        if (enrollSuccess) {
            // success
            mRequestReason = CERT_ENROLL_SUCCESS;
            if (mProcedureType == SUBSCRIPTION_PROVISION) {
                subscriptionProvision();
            } else if (mProcedureType == SUBSCRIPTION_REMEDIATION) {
                remediation();
            }
        } else {
            // fail
            mRequestReason = CERT_ENROLL_FAIL;
            if (mProcedureType == SUBSCRIPTION_PROVISION) {
                subscriptionProvision();
            } else if (mProcedureType == SUBSCRIPTION_REMEDIATION) {
                remediation();
            }
        }
    }

    private void subscriptionProvision() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    SoapObject request = getSubRegistration(mRequestReason);
                    String response = null;

                    response = connectSoapServer(request, null, null, NO_CLIENT_AUTH);

                    if (response == null) {
                        Log.e(TAG, "[startSubscriptionProvisioning] Fail to get soap resonse");
                        mTarget.sendMessage(
                                WifiPasspointStateMachine.CMD_OSU_DONE, 9, 0, null);// "Not found"
                        return;
                    }

                    Document doc = mPpsmoParser.getDocument(response);
                    String status = checkStatus(doc);

                    // get OSU server page to fill form
                    // while certificate enrollment fail, go to OSU server
                    // page to do user-managed user/pass credential
                    // registration again
                    if (WIFI_SOAP_SPP_STATUS_OK.equals(status)) {
                        if (getSubscriptionSignUpAndUserUpdate(doc)) {
                            Log.d(TAG, "[New] redirect to browser");
                            // useClientCertTLS
                        } else if (getUseClientCertTLS(doc)) {
                            Log.d(TAG,
                                    "Provisioning using client certificate through TLS (useClientCertTLS)");
                        } else if (getEnrollmentInfo(doc)) {
                            startCertificateEnroll(mPasspointCertificate.ENROLL);
                        }
                    }
                    // addMO from server
                    // while certificate enrollment success, just send
                    // sppUpdateResponse
                    // while certificate enrollment fail, install
                    // machine-managed user/pass credential, then send
                    // sppUpdateResponse
                    else if (WIFI_SOAP_SPP_STATUS_PROVISION_COMPLETE.equals(status)) {
                        if (checkWifiSpFqdnForAddMo(doc)) {
                            Document docMgmtTree = mPpsmoParser.extractMgmtTree(response);
                            String moTree = mPpsmoParser.xmlToString(docMgmtTree);
                            String treeUri = getSPPTreeUri(doc, "addMO");
                            int injStatus = mDmClient.injectSoapPackage(treeUri, "addMO", moTree);

                            if (injStatus == 0) {
                                mSoapTree = mDmClient.getWifiTree();
                                sendUpdateResponse(true, SUBSCRIPTION_PROVISION,
                                        WIFI_SOAP_UPDATE_RESPONSE_OK);
                            } else {
                                sendUpdateResponse(true, SUBSCRIPTION_PROVISION,
                                        WIFI_SOAP_UPDATE_RESPONSE_MO_ADD_UPDATE_FAIL);
                            }
                        } else {
                            sendUpdateResponse(false, SUBSCRIPTION_PROVISION,
                                    WIFI_SOAP_UPDATE_RESPONSE_PERMISSION_DENY);
                        }
                    }
                    // abort provisioning
                    // while certificate enrollment fail
                    else if (WIFI_SOAP_SPP_STATUS_EXCHANGE_COMPLETE.equals(status)) {
                        String err = checkErrorCode(doc);
                        Log.e(TAG,
                                "[startSubscriptionProvisioning] Exchange complete, release TLS connection error occurred: "
                                        + err);
                    } else if (WIFI_SOAP_SPP_STATUS_ERROR_OCCURRED.equals(status)) {
                        Log.e(TAG, "[startSubscriptionProvisioning] Error occurred");
                        getErrorCode(doc);
                    } else {
                        status = checkStatus(doc, NAMESPACE_NS, WIFI_SOAP_USER_INPUT_RESPONSE);
                        if (WIFI_SOAP_SPP_STATUS_OK.equals(status)) {
                            if (getEnrollmentInfo(doc)) {
                                startCertificateEnroll(mPasspointCertificate.ENROLL);
                            }
                        } else if (WIFI_SOAP_SPP_STATUS_ERROR_OCCURRED.equals(status)) {
                            Log.e(TAG,
                                    "[startSubscriptionProvisioning] checkStatus of sppUserInputResponse Error occurred");
                            getErrorCode(doc);
                        } else {
                            Log.e(TAG, "[startSubscriptionProvisioning] unknown status");
                        }

                    }
                    return;

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void remediation() {
        if (!USER_INPUT_COMPLETED.equals(mRequestReason) && !SUB_MO_UPLOAD.equals(mRequestReason)) {
            setRemediationHttpDigest();
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    SoapObject request = getSubRemediation(mRequestReason);
                    String response = null;

                    String eapType = null;
                    if (mCred != null) {
                        eapType = mCred.getType();
                    }

                    if ("SIM".equals(eapType)) {
                        Log.d(TAG, "EAP-SIM, no digest or client certifcate");
                        response = connectSoapServer(request, "", "", NO_CLIENT_AUTH);
                    } else if ("TTLS".equals(eapType)) {
                        if (mDigestUsername != null && !mDigestUsername.isEmpty()) {
                            Log.d(TAG,
                                    "digest using U/P or Subscription update credential, mDigestUsername/mDigestPassword: "
                                            + mDigestUsername + "/" + mDigestPassword);
                            response = connectSoapServer(request, mDigestUsername,
                                    mDigestPassword, NO_CLIENT_AUTH);
                        }
                    } else if ("TLS".equals(eapType)) {
                        String credentialCertSHA256Fingerprint = mCred
                                .getCertSha256Fingerprint();
                        Log.d(TAG, "digest using client cert credential, SHA256 fingerprint: "
                                + credentialCertSHA256Fingerprint);
                        sHs20Pkcs12KeyStore = mPasspointCertificate
                                .getCredentialCertKeyStore(credentialCertSHA256Fingerprint);

                        if (sHs20Pkcs12KeyStore != null) {
                            response = connectSoapServer(request, null, null, CLIENT_CERT);
                        } else {
                            Log.d(TAG, "client certifcate not found");
                        }
                    } else {
                        Log.d(TAG, "no digest or client certifcate");
                        response = connectSoapServer(request, "", "", NO_CLIENT_AUTH);
                    }

                    if (response == null) {
                        Log.e(TAG, "[startRemediation] Fail to get soap resonse");
                        return;
                    }

                    Document doc = mPpsmoParser.getDocument(response);
                    String status = checkStatus(doc);
                    if (WIFI_SOAP_SPP_STATUS_REMEDIATION_COMPLETE.equals(status)) {
                        if (getNoMoUpdate(doc)) {
                            Log.d(TAG, WIFI_SOAP_SPP_NOMOUPDATE);
                        } else if (getSubscriptionSignUpAndUserUpdate(doc)) {
                            Log.d(TAG, "[New] redirect to browser");
                        } else {
                            if (checkWifiSpFqdnForUpdateMo(doc)) {
                                Vector<Document> sppUpdateNodes = mPpsmoParser.getSPPNodes(doc,
                                        NAMESPACE_NS, "updateNode");
                                for (Document docNodes : sppUpdateNodes) {
                                    ++managementTreeUpdateCount;
                                    Document docMgmtTree = mPpsmoParser.extractMgmtTree(docNodes);
                                    String moTree = mPpsmoParser.xmlToString(docMgmtTree);
                                    Log.d(TAG2, moTree);
                                    String sppTreeUri = getSPPTreeUri(docNodes, "updateNode");
                                    int injStatus = mDmClient.injectSoapPackage(sppTreeUri,
                                            "updateNode", moTree);

                                    if (injStatus == 0) {
                                        --managementTreeUpdateCount;
                                    }
                                }

                                if (sppUpdateNodes.size() != 0 && managementTreeUpdateCount == 0) {
                                    mSoapTree = mDmClient.getWifiTree();
                                    sendUpdateResponse(true, SUBSCRIPTION_REMEDIATION,
                                            WIFI_SOAP_UPDATE_RESPONSE_OK);
                                } else {
                                    sendUpdateResponse(false, SUBSCRIPTION_REMEDIATION,
                                            WIFI_SOAP_UPDATE_RESPONSE_MO_ADD_UPDATE_FAIL);
                                }
                            } else {
                                sendUpdateResponse(false, SUBSCRIPTION_REMEDIATION,
                                        WIFI_SOAP_UPDATE_RESPONSE_PERMISSION_DENY);
                            }
                        }
                    } else if (WIFI_SOAP_SPP_STATUS_OK.equals(status)) {
                        if (getUploadMO(doc)) {
                            mRequestReason = SUB_MO_UPLOAD;
                            remediation();
                        } else if (getSubscriptionSignUpAndUserUpdate(doc)) {
                            Log.d(TAG, "[New] redirect to browser");
                        } else if (getEnrollmentInfo(doc)) {
                            startCertificateEnroll(mPasspointCertificate.REENROLL);
                        } else {
                            if (checkWifiSpFqdnForUpdateMo(doc)) {
                                Vector<Document> sppUpdateNodes = mPpsmoParser.getSPPNodes(doc,
                                        NAMESPACE_NS, "updateNode");
                                for (Document docNodes : sppUpdateNodes) {
                                    ++managementTreeUpdateCount;
                                    Document docMgmtTree = mPpsmoParser.extractMgmtTree(docNodes);
                                    String moTree = mPpsmoParser.xmlToString(docMgmtTree);
                                    Log.d(TAG2, moTree);
                                    String sppTreeUri = getSPPTreeUri(docNodes, "updateNode");
                                    int injStatus = mDmClient.injectSoapPackage(sppTreeUri,
                                            "updateNode", moTree);

                                    if (injStatus == 0) {
                                        --managementTreeUpdateCount;
                                    }
                                }

                                if (sppUpdateNodes.size() != 0 && managementTreeUpdateCount == 0) {
                                    mSoapTree = mDmClient.getWifiTree();
                                    sendUpdateResponse(true, SUBSCRIPTION_REMEDIATION,
                                            WIFI_SOAP_UPDATE_RESPONSE_OK);
                                } else {
                                    sendUpdateResponse(false, SUBSCRIPTION_REMEDIATION,
                                            WIFI_SOAP_UPDATE_RESPONSE_MO_ADD_UPDATE_FAIL);
                                }
                            } else {
                                sendUpdateResponse(false, SUBSCRIPTION_REMEDIATION,
                                        WIFI_SOAP_UPDATE_RESPONSE_PERMISSION_DENY);
                            }
                        }
                    } else if (WIFI_SOAP_SPP_STATUS_PROVISION_COMPLETE.equals(status)) {
                        Document docMgmtTree = mPpsmoParser.extractMgmtTree(response);
                        String moTree = mPpsmoParser.xmlToString(docMgmtTree);
                        String treeUri = getSPPTreeUri(doc, "addMO");
                        int injStatus = mDmClient.injectSoapPackage(treeUri, "addMO", moTree);

                        if (injStatus == 0) {
                            mSoapTree = mDmClient.getWifiTree();
                            sendUpdateResponse(true, SUBSCRIPTION_SIM_PROVISION,
                                    WIFI_SOAP_UPDATE_RESPONSE_OK);
                        } else {
                            sendUpdateResponse(false, SUBSCRIPTION_SIM_PROVISION,
                                    WIFI_SOAP_UPDATE_RESPONSE_MO_ADD_UPDATE_FAIL);

                        }
                    } else if (WIFI_SOAP_SPP_STATUS_EXCHANGE_COMPLETE.equals(status)) {
                        Log.d(TAG, WIFI_SOAP_SPP_STATUS_EXCHANGE_COMPLETE);
                        mTarget.sendMessage(
                                WifiPasspointStateMachine.CMD_REMEDIATION_DONE, 0, 0, mSoapTree);// "NoUpdate"
                    } else if (WIFI_SOAP_SPP_STATUS_NO_UPDATE_AVAILABLE.equals(status)) {
                        Log.d(TAG, WIFI_SOAP_SPP_STATUS_NO_UPDATE_AVAILABLE);
                    } else if (WIFI_SOAP_SPP_STATUS_ERROR_OCCURRED.equals(status)) {
                        Log.e(TAG, "[startRemediation] Error occurred");
                        getErrorCode(doc);
                    } else {
                        Log.e(TAG, "[startRemediation] unknown status");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void policyProvision() {
        if (POL_UPDATE.equals(mRequestReason)) {
            setPolicyUpdateHttpDigest();
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    SoapObject request = getPolicyUpdateRequest(mRequestReason);
                    String response = null;

                    if (mDigestUsername != null && !mDigestUsername.isEmpty()) {
                        Log.d(TAG,
                                "digest using U/P Credential or Policy update, mDigestUsername/mDigestPassword: "
                                        + mDigestUsername + "/" + mDigestPassword);
                        response = connectSoapServer(request, mDigestUsername, mDigestPassword,
                                NO_CLIENT_AUTH);
                    }

                    if (response != null && !response.isEmpty()) {
                        Document doc = mPpsmoParser.getDocument(response);
                        String status = checkStatus(doc);
                        if (WIFI_SOAP_SPP_STATUS_OK.equals(status)) {
                            if (getUploadMO(doc)) {
                                if (checkWifiSpFqdnForUploadMo(doc)) {
                                    mRequestReason = POL_MO_UPLOAD;
                                    policyProvision();
                                    return;
                                } else {
                                    sendUpdateResponse(false, POLICY_UPDATE,
                                            WIFI_SOAP_UPDATE_RESPONSE_PERMISSION_DENY);
                                    return;
                                }
                            }
                        } else if (WIFI_SOAP_SPP_STATUS_UPDATE_COMPLETE.equals(status)) {
                            if (checkWifiSpFqdnForUpdateMo(doc)) {
                                Vector<Document> sppUpdateNodes = mPpsmoParser.getSPPNodes(doc,
                                        NAMESPACE_NS, "updateNode");
                                for (Document docNodes : sppUpdateNodes) {
                                    ++managementTreeUpdateCount;
                                    Document docMgmtTree = mPpsmoParser.extractMgmtTree(docNodes);
                                    String moTree = mPpsmoParser.xmlToString(docMgmtTree);
                                    Log.d(TAG2, moTree);
                                    String sppTreeUri = getSPPTreeUri(docNodes, "updateNode");
                                    int injStatus = mDmClient.injectSoapPackage(sppTreeUri,
                                            "updateNode", moTree);

                                    if (injStatus == 0) {
                                        --managementTreeUpdateCount;
                                    }
                                }

                                if (sppUpdateNodes.size() != 0 && managementTreeUpdateCount == 0) {
                                    mSoapTree = mDmClient.getWifiTree();
                                    sendUpdateResponse(true, POLICY_UPDATE,
                                            WIFI_SOAP_UPDATE_RESPONSE_OK);
                                } else {
                                    sendUpdateResponse(false, POLICY_UPDATE,
                                            WIFI_SOAP_UPDATE_RESPONSE_MO_ADD_UPDATE_FAIL);
                                }
                            } else {
                                sendUpdateResponse(false, POLICY_UPDATE,
                                        WIFI_SOAP_UPDATE_RESPONSE_PERMISSION_DENY);
                            }

                            return;
                        } else if (WIFI_SOAP_SPP_STATUS_NO_UPDATE_AVAILABLE.equals(status)) {
                            Log.d(TAG, WIFI_SOAP_SPP_STATUS_NO_UPDATE_AVAILABLE);
                        } else if (WIFI_SOAP_SPP_STATUS_ERROR_OCCURRED.equals(status)) {
                            Log.e(TAG, "[startPolicyProvision] Error occurred");
                            getErrorCode(doc);
                        } else {
                            Log.e(TAG, "[startPolicyProvision] unknown status");
                        }
                    } else {
                        Log.e(TAG, "[startPolicyProvision] Fail to get soap resonse");
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (mTarget != null) {
                    mTarget.sendMessage(
                            WifiPasspointStateMachine.CMD_POLICY_UPDATE_DONE, 0, 0, mSoapTree);
                } else {
                    Log.e(TAG,
                            "[startPolicyProvision] send CMD_POLICY_UPDATE_DONE fail, mTarget null");
                }
            }
        }).start();
    }

    private String connectSoapServer(SoapObject request, final String digestUsername,
            final String digestPassword, final int clientCertType) {
        Log.d(TAG, "[connectSoapServer]: request:" + request);
        String response = null;
        SoapSerializationEnvelope envelope = new SoapSerializationEnvelope(SoapEnvelope.VER12);
        envelope.setAddAdornments(false);
        envelope.implicitTypes = true;
        envelope.dotNet = false;
        envelope.setOutputSoapObject(request);

        Log.d(TAG, "Server url:" + mSoapWebUrl);

        if (mSoapWebUrl.startsWith("HTTPS://") || mSoapWebUrl.startsWith("https://")) {
            try {
                int retryCount = 5;
                boolean isConnected = false;

                WifiPasspointHttpClient hc = null;
                UsernamePasswordCredentials credentials = null;

                if (digestUsername != null && digestPassword != null) {
                    credentials = new UsernamePasswordCredentials(digestUsername, digestPassword);
                    hc = new WifiPasspointHttpClient(null, null);
                    hc.setAuthenticationCredentials(credentials);
                } else {
                    if (clientCertType == CLIENT_CERT) {
                        if (sHs20Pkcs12KeyStore.aliases().hasMoreElements()) {
                            hc = new WifiPasspointHttpClient(sHs20Pkcs12KeyStore,
                                    mPasspointCertificate.passWord.toCharArray());
                        } else {
                            Log.d(TAG, "client cert is not installed in passpoint PKCS12 keystore");
                            hc = new WifiPasspointHttpClient(null, null);
                        }
                    } else {
                        hc = new WifiPasspointHttpClient(null, null);
                    }
                }

                while (retryCount > 0 && !isConnected) {
                    try {
                        URI requestUri = new URI(mSoapWebUrl);
                        HttpResponse httpResp = null;
                        byte[] requestData =
                                (new HttpTransportSE(mSoapWebUrl))
                                        .getRequestData(envelope, "UTF-8");
                        Header[] requestHeaders;
                        List<BasicHeader> basicHeaders = new ArrayList<BasicHeader>();

                        if (requestData == null) {
                            break;
                        }

                        basicHeaders.add(new BasicHeader(hc.CONNECTION, "close"));
                        basicHeaders.add(new BasicHeader(hc.ACCEPT_ENCODING_HEADER, "gzip"));
                        basicHeaders.add(new BasicHeader(hc.CONTENT_LENGTH_HEADER, ""
                                + requestData.length));
                        requestHeaders = basicHeaders.toArray(new Header[basicHeaders.size()]);

                        if (envelope.version == SoapSerializationEnvelope.VER12) {
                            httpResp = hc.post(requestUri, CONTENT_TYPE_SOAP_XML_CHARSET_UTF_8,
                                    requestData, requestHeaders);
                        } else {
                            httpResp = hc.post(requestUri, CONTENT_TYPE_XML_CHARSET_UTF_8,
                                    requestData, requestHeaders);
                        }

                        InputStream is = httpResp.getEntity().getContent();

                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];

                        while (true) {
                            int rd = is.read(buf, 0, 8192);
                            if (rd == -1) {
                                break;
                            }
                            bos.write(buf, 0, rd);
                        }

                        bos.flush();
                        response = bos.toString();
                        isConnected = true;
                        Log.d(TAG, "soap connect by TLS");
                    } catch (UnknownHostException ee) {
                        retryCount--;
                        Log.d(TAG, "Wait for retry:" + retryCount);
                        Thread.sleep(3 * 1000);
                    }
                }

                if (!isConnected) {
                    Log.e(TAG, "Failed to connect");
                    return null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return response;
    }

    private void sendUpdateResponse(final boolean success, final int procedureType,
            final String reason) {
        Log.d(TAG, "[sendUpdateResponse] start, success = " + success + ", procedureType = "
                + procedureType + ", reason = " + reason);
        if (procedureType == SUBSCRIPTION_PROVISION) {
            mSoapWebUrl = mOSUServerUrl;
        } else if (procedureType == SUBSCRIPTION_SIM_PROVISION) {
            // SIM provision through remediation procedure
            mSoapWebUrl = mREMServerUrl;
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    SoapObject request = new SoapObject();
                    String response = null;

                    if (success == true) {
                        request = getUpdateResponse(false, null);
                    } else {
                        request = getUpdateResponse(true, reason);
                    }

                    if (procedureType == POLICY_UPDATE || procedureType == SUBSCRIPTION_REMEDIATION) {
                        String eapType = mCred.getType();
                        String credentialCertSHA256Fingerprint = mCred.getCertSha256Fingerprint();

                        if (mDigestUsername != null && !mDigestUsername.isEmpty()) {
                            Log.d(TAG,
                                    "digest using U/P or Policy update credential, mDigestUsername/mDigestPassword: "
                                            + mDigestUsername + "/" + mDigestPassword);
                            response = connectSoapServer(request, mDigestUsername, mDigestPassword,
                                    NO_CLIENT_AUTH);
                        }
                        else if ("TLS".equals(eapType)) {
                            Log.d(TAG, "digest using client cert credential, SHA256 fingerprint: "
                                    + credentialCertSHA256Fingerprint);
                            response = connectSoapServer(request, null, null, CLIENT_CERT);
                        }
                    } else {
                        // procedureType == SUBSCRIPTION_PROVISION ||
                        // procedureType == SUBSCRIPTION_SIM_PROVISION
                        Log.d(TAG, "OSU, no need to set digest");
                        response = connectSoapServer(request, null, null, NO_CLIENT_AUTH);
                    }

                    if (response == null || response.isEmpty()) {
                        Log.e(TAG, "[sendUpdateResponse] Fail to get soap resonse");
                        return;
                    }

                    Document doc = mPpsmoParser.getDocument(response);
                    String status = checkStatus(doc, NAMESPACE_NS, WIFI_SOAP_SPP_EXCHANGE_COMPLETE);

                    if (WIFI_SOAP_SPP_STATUS_EXCHANGE_COMPLETE.equals(status)) {
                        Log.d(TAG, "[sendUpdateResponse] exchange complete");
                        if (procedureType == SUBSCRIPTION_PROVISION) {
                            Log.d(TAG,
                                    "[sendUpdateResponse] exchange complete:procedureType == SUBSCRIPTION_PROVISION");

                            boolean result = true;

                            Collection<WifiPasspointDmTree.CredentialInfo> creds = mTreeHelper
                                    .getCredentialInfo(mTreeHelper.getSp(mSoapTree,
                                            mSpFqdnFromMo));
                            for (WifiPasspointDmTree.CredentialInfo credInfo : creds) {
                                // Save mapping of enrolled certificate
                                // alias and SHA256 fingerprint
                                if (credInfo.credential.digitalCertificate.CertificateType
                                        .equals("x509v3")) {
                                    mPasspointCertificate.saveMappingOfEnrollCertAliasAndSha256(
                                            mPasspointCertificate.getEnrollCertAlias(),
                                            credInfo.credential.digitalCertificate.CertSHA256Fingerprint);
                                }

                                for (WifiPasspointDmTree.AAAServerTrustRoot aaaTrustRoot : credInfo.aAAServerTrustRoot
                                        .values()) {
                                    result &= mPasspointCertificate.installServerTrustRoot(
                                            aaaTrustRoot.CertURL,
                                            aaaTrustRoot.CertSHA256Fingerprint,
                                            WifiPasspointCertificate.AAA_ROOT, false);
                                }
                                result &= mPasspointCertificate
                                        .installServerTrustRoot(
                                                credInfo.subscriptionUpdate.trustRoot.CertURL,
                                                credInfo.subscriptionUpdate.trustRoot.CertSHA256Fingerprint,
                                                WifiPasspointCertificate.SUBSCRIPTION_ROOT, false);
                                result &= mPasspointCertificate
                                        .installServerTrustRoot(
                                                credInfo.policy.policyUpdate.trustRoot.CertURL,
                                                credInfo.policy.policyUpdate.trustRoot.CertSHA256Fingerprint,
                                                WifiPasspointCertificate.POLICY_ROOT, false);
                                result &= success;
                            }

                            mTarget.sendMessage(
                                    WifiPasspointStateMachine.CMD_OSU_DONE, result ? 0 : 1, 0,
                                    result ? mSoapTree : null);
                        } else if (procedureType == SUBSCRIPTION_SIM_PROVISION) {
                            Log.d(TAG,
                                    "[sendUpdateResponse] exchange complete:procedureType == SUBSCRIPTION_SIM_PROVISION");

                            boolean result = true;

                            Collection<WifiPasspointDmTree.CredentialInfo> creds = mTreeHelper
                                    .getCredentialInfo(mTreeHelper.getSp(mSoapTree,
                                            mSpFqdnFromMo));
                            for (WifiPasspointDmTree.CredentialInfo credInfo : creds) {
                                for (WifiPasspointDmTree.AAAServerTrustRoot aaaTrustRoot : credInfo.aAAServerTrustRoot
                                        .values()) {
                                    result &= mPasspointCertificate.installServerTrustRoot(
                                            aaaTrustRoot.CertURL,
                                            aaaTrustRoot.CertSHA256Fingerprint,
                                            WifiPasspointCertificate.AAA_ROOT, false);
                                }
                                result &= mPasspointCertificate
                                        .installServerTrustRoot(
                                                credInfo.subscriptionUpdate.trustRoot.CertURL,
                                                credInfo.subscriptionUpdate.trustRoot.CertSHA256Fingerprint,
                                                WifiPasspointCertificate.SUBSCRIPTION_ROOT, false);
                                result &= mPasspointCertificate
                                        .installServerTrustRoot(
                                                credInfo.policy.policyUpdate.trustRoot.CertURL,
                                                credInfo.policy.policyUpdate.trustRoot.CertSHA256Fingerprint,
                                                WifiPasspointCertificate.POLICY_ROOT, false);
                                result &= success;
                            }

                            mTarget.sendMessage(
                                    WifiPasspointStateMachine.CMD_SIM_PROVISION_DONE, result ? 0 : 1,
                                    0,
                                    result ? mSoapTree : null);
                        } else if (procedureType == SUBSCRIPTION_REMEDIATION) {
                            Log.d(TAG,
                                    "[sendUpdateResponse] exchange complete:procedureType == SUBSCRIPTION_REMEDIATION");

                            boolean result = true;

                            WifiPasspointDmTree.CredentialInfo credInfo = mTreeHelper.getCredentialInfo(
                                    mSoapTree, mCred.getWifiSpFqdn(), mCred.getCredName());
                            if (credInfo == null) {
                                Log.d(TAG, "credInfo is null while retrieving AAA trust root");
                                mTarget.sendMessage(
                                        WifiPasspointStateMachine.CMD_REMEDIATION_DONE, 3, 0,
                                        null);// "MO addition or update failed"
                                return;
                            } else {
                                for (WifiPasspointDmTree.AAAServerTrustRoot aaaTrustRoot : credInfo.aAAServerTrustRoot
                                        .values()) {
                                    result &= mPasspointCertificate.installServerTrustRoot(
                                            aaaTrustRoot.CertURL,
                                            aaaTrustRoot.CertSHA256Fingerprint,
                                            WifiPasspointCertificate.AAA_ROOT, true);
                                }
                                result &= mPasspointCertificate
                                        .installServerTrustRoot(
                                                credInfo.subscriptionUpdate.trustRoot.CertURL,
                                                credInfo.subscriptionUpdate.trustRoot.CertSHA256Fingerprint,
                                                WifiPasspointCertificate.SUBSCRIPTION_ROOT, false);
                                result &= mPasspointCertificate
                                        .installServerTrustRoot(
                                                credInfo.policy.policyUpdate.trustRoot.CertURL,
                                                credInfo.policy.policyUpdate.trustRoot.CertSHA256Fingerprint,
                                                WifiPasspointCertificate.POLICY_ROOT, false);
                                result &= success;

                            }

                            mTarget.sendMessage(
                                    WifiPasspointStateMachine.CMD_REMEDIATION_DONE, success ? 0 : 1, 0,
                                    success ? mSoapTree : null);// "MO addition or update failed"
                        } else if (procedureType == POLICY_UPDATE) {
                            Log.d(TAG,
                                    "[sendUpdateResponse] exchange complete:procedureType == POLICY_UPDATE");
                            mTarget.sendMessage(
                                    WifiPasspointStateMachine.CMD_POLICY_UPDATE_DONE, success ? 0 : 1,
                                    0,
                                    success ? mSoapTree : null);
                        }
                    } else if (WIFI_SOAP_SPP_STATUS_ERROR_OCCURRED.equals(status)) {
                        Log.e(TAG, "[sendUpdateResponse] Error occurred");
                        getErrorCode(doc);
                    } else {
                        Log.e(TAG, "[sendUpdateResponse] unknown status");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }

    private SoapObject getSubRegistration(String requestReason) {
        // Construct sppPostDevData element
        SoapObject request = new SoapObject(NAMESPACE_NS, WIFI_SOAP_POST_DEV_DATA);
        AttributeInfo attributeInfo = new AttributeInfo();
        attributeInfo.setName(WIFI_SOAP_SPP_VERSION);
        attributeInfo.setValue("1.0");
        attributeInfo.setType("PropertyInfo.STRING_CLASS");
        attributeInfo.setNamespace(NAMESPACE_NS);
        request.addAttribute(attributeInfo);
        //requestReason
        request.addAttribute(WIFI_SOAP_REQ_REASON, requestReason);

        //redirectURL
        request.addAttribute(WIFI_SOAP_REDIRECTURL, mRedirectUrl);

        //sessionID (adding all packages except for the first one)
        if (!SUB_REGISTER.equals(requestReason)) {
            AttributeInfo sessionIDattributeInfo = new AttributeInfo();
            sessionIDattributeInfo.setName(WIFI_SOAP_SESSIONID);
            sessionIDattributeInfo.setValue(mSessionID);
            sessionIDattributeInfo.setType("PropertyInfo.STRING_CLASS");
            sessionIDattributeInfo.setNamespace(NAMESPACE_NS);
            request.addAttribute(sessionIDattributeInfo);
        }

        //New supportedSPPVersions element
        request.addProperty(WIFI_SOAP_S_SPP_VERSION, "1.0");
        //New supportedMOList
        request.addProperty(WIFI_SOAP_S_MOLIST, WIFI_SOAP_S_MOSUBSCRIPTION
                + " " + WIFI_SOAP_S_MODEVINFO
                + " " + WIFI_SOAP_S_MODEVDETAIL
                + " " + WIFI_SOAP_S_MOHS20);

        //New moContainer
        //Construct moContainer
        SoapObject moInfo = getMoInfo();
        SoapObject moDetail = getMoDetail();
        request.addSoapObject(moInfo);
        request.addSoapObject(moDetail);

        return request;
    }

    private SoapObject getSubRemediation(String requestReason) {
        // Construct sppPostDevData element
        SoapObject request = new SoapObject(NAMESPACE_NS, WIFI_SOAP_POST_DEV_DATA);
        //sppVersion
        AttributeInfo attributeInfo = new AttributeInfo();
        attributeInfo.setName(WIFI_SOAP_SPP_VERSION);
        attributeInfo.setValue("1.0");
        attributeInfo.setType("PropertyInfo.STRING_CLASS");
        attributeInfo.setNamespace(NAMESPACE_NS);
        request.addAttribute(attributeInfo);
        //requestReason
        request.addAttribute(WIFI_SOAP_REQ_REASON, requestReason);
        //redirectURL
        request.addAttribute(WIFI_SOAP_REDIRECTURL, mRedirectUrl);

        //sessionID
        if (!SUB_PROVISION.equals(requestReason) && !SUB_REMEDIATION.equals(requestReason)) {
            AttributeInfo sessionIDattributeInfo = new AttributeInfo();
            sessionIDattributeInfo.setName(WIFI_SOAP_SESSIONID);
            sessionIDattributeInfo.setValue(mSessionID);
            sessionIDattributeInfo.setType("PropertyInfo.STRING_CLASS");
            sessionIDattributeInfo.setNamespace(NAMESPACE_NS);
            request.addAttribute(sessionIDattributeInfo);
        }

        //New supportedSPPVersions element
        request.addProperty(WIFI_SOAP_S_SPP_VERSION, "1.0");
        //New supportedMOList
        request.addProperty(WIFI_SOAP_S_MOLIST, WIFI_SOAP_S_MOSUBSCRIPTION
                + " " + WIFI_SOAP_S_MODEVINFO
                + " " + WIFI_SOAP_S_MODEVDETAIL
                + " " + WIFI_SOAP_S_MOHS20);

        //New moContainer
        //Construct moContainer
        SoapObject moInfo = getMoInfo();
        SoapObject moDetail = getMoDetail();
        request.addSoapObject(moInfo);
        request.addSoapObject(moDetail);

        //New moContainer
        //Construct moContainer
        if (SUB_MO_UPLOAD.equals(requestReason)) {
            SoapObject moSub = getSubscription();
            request.addSoapObject(moSub);
        }

        return request;
    }

    private SoapObject getSubscription() {
        WifiPasspointDmTree.CredentialInfo credInfo = mTreeHelper.getCredentialInfo(mSoapTree,
                mCred.getWifiSpFqdn(), mCred.getCredName());
        SoapObject nsRequest = new SoapObject(NAMESPACE_NS, WIFI_SOAP_MO_CONTAINER);
        AttributeInfo attributeInfo = new AttributeInfo();
        attributeInfo.setName(WIFI_SOAP_MO_URN);
        attributeInfo.setValue(WIFI_SOAP_S_MOSUBSCRIPTION);
        attributeInfo.setType("PropertyInfo.STRING_CLASS");
        attributeInfo.setNamespace(NAMESPACE_NS);
        nsRequest.addAttribute(attributeInfo);

        SoapObject MgmtTreeRequest = new SoapObject(null, WIFI_SOAP_MGMTREE);
        //xmlns
        AttributeInfo ppsMoattributeInfo = new AttributeInfo();
        ppsMoattributeInfo.setName(WIFI_SOAP_MO_XMLNS);
        ppsMoattributeInfo.setValue(WIFI_SOAP_SYNCML_DMDDF_1_2);
        ppsMoattributeInfo.setType("PropertyInfo.STRING_CLASS");
        MgmtTreeRequest.addAttribute(ppsMoattributeInfo);
        MgmtTreeRequest.addProperty("VerDTD", "1.2");

        SoapObject PerProviderSubscriptionRequest = new SoapObject(null, "Node");
        PerProviderSubscriptionRequest.addProperty("NodeName", "PerProviderSubscription");

        SoapObject rtPropertiesRequest = new SoapObject(null, "RTProperties");
        SoapObject typeRequest = new SoapObject(null, "Type");
        typeRequest.addProperty("DDFName", WIFI_SOAP_S_MOSUBSCRIPTION);
        rtPropertiesRequest.addSoapObject(typeRequest);
        PerProviderSubscriptionRequest.addSoapObject(rtPropertiesRequest);

        SoapObject x1Request = new SoapObject(null, "Node");
        x1Request.addProperty("NodeName", "x1");

        SoapObject SubscriptionPriorityRequest = new SoapObject(null, "Node");
        SubscriptionPriorityRequest.addProperty("NodeName", "SubscriptionPriority");
        SubscriptionPriorityRequest.addProperty("Value", credInfo.credentialPriority);
        x1Request.addSoapObject(SubscriptionPriorityRequest);

        SoapObject SubscriptionRemediationRequest = new SoapObject(null, "Node");
        SubscriptionRemediationRequest.addProperty("NodeName", "SubscriptionRemediation");

        SoapObject RemURIRequest = new SoapObject(null, "Node");
        RemURIRequest.addProperty("NodeName", "URI");
        RemURIRequest.addProperty("Value", mSoapWebUrl);
        SubscriptionRemediationRequest.addSoapObject(RemURIRequest);

        SoapObject certURLRequest = new SoapObject(null, "Node");
        certURLRequest.addProperty("NodeName", "certURL");
        certURLRequest.addProperty("Value", credInfo.subscriptionUpdate.trustRoot.CertURL);
        SubscriptionRemediationRequest.addSoapObject(certURLRequest);

        SoapObject certSHA256FingerprintRequest = new SoapObject(null, "Node");
        certSHA256FingerprintRequest.addProperty("NodeName", "certSHA256Fingerprint");
        certSHA256FingerprintRequest.addProperty("Value",
                credInfo.subscriptionUpdate.trustRoot.CertSHA256Fingerprint);
        SubscriptionRemediationRequest.addSoapObject(certSHA256FingerprintRequest);

        x1Request.addSoapObject(SubscriptionRemediationRequest);

        SoapObject SubscriptionUpdateRequest = new SoapObject(null, "Node");
        SubscriptionUpdateRequest.addProperty("NodeName", "SubscriptionUpdate");

        SoapObject UpdateIntervalRequest = new SoapObject(null, "Node");
        UpdateIntervalRequest.addProperty("NodeName", "UpdateInterval");
        UpdateIntervalRequest.addProperty("Value", credInfo.subscriptionUpdate.UpdateInterval);
        SubscriptionUpdateRequest.addSoapObject(UpdateIntervalRequest);

        SoapObject UpdateMethodRequest = new SoapObject(null, "Node");
        UpdateMethodRequest.addProperty("NodeName", "UpdateMethod");
        UpdateMethodRequest.addProperty("Value", credInfo.subscriptionUpdate.UpdateMethod);
        SubscriptionUpdateRequest.addSoapObject(UpdateMethodRequest);

        SoapObject RestrictionRequest = new SoapObject(null, "Node");
        RestrictionRequest.addProperty("NodeName", "Restriction");
        RestrictionRequest.addProperty("Value", credInfo.subscriptionUpdate.Restriction);
        SubscriptionUpdateRequest.addSoapObject(RestrictionRequest);

        SoapObject UpdURIRequest = new SoapObject(null, "Node");
        UpdURIRequest.addProperty("NodeName", "URI");
        UpdURIRequest.addProperty("Value", credInfo.subscriptionUpdate.URI);
        SubscriptionUpdateRequest.addSoapObject(UpdURIRequest);

        x1Request.addSoapObject(SubscriptionUpdateRequest);

        SoapObject HomeSPRequest = new SoapObject(null, "Node");
        HomeSPRequest.addProperty("NodeName", "HomeSP");

        SoapObject FriendlyNameRequest = new SoapObject(null, "Node");
        FriendlyNameRequest.addProperty("NodeName", "FriendlyName");
        FriendlyNameRequest.addProperty("Value", credInfo.homeSP.FriendlyName);
        HomeSPRequest.addSoapObject(FriendlyNameRequest);

        SoapObject FQDNNameRequest = new SoapObject(null, "Node");
        FQDNNameRequest.addProperty("NodeName", "FQDN");
        FQDNNameRequest.addProperty("Value", credInfo.homeSP.FQDN);
        HomeSPRequest.addSoapObject(FQDNNameRequest);

        SoapObject HomeOIListRequest = new SoapObject(null, "Node");
        HomeOIListRequest.addProperty("NodeName", "HomeOIList");

        Collection<WifiPasspointDmTree.HomeOIList> oil = mTreeHelper.getHomeOIList(credInfo);
        for (WifiPasspointDmTree.HomeOIList oi : oil) {
            SoapObject HomeOIListx1Request = new SoapObject(null, "Node");
            HomeOIListx1Request.addProperty("NodeName", oi.nodeName);

            SoapObject HomeOIx1Request = new SoapObject(null, "Node");
            HomeOIx1Request.addProperty("NodeName", "HomeOI");
            HomeOIx1Request.addProperty("Value", oi.HomeOI);
            HomeOIx1Request.addProperty("NodeName", "HomeOIRequired");
            HomeOIx1Request.addProperty("Value", Boolean.toString(oi.HomeOIRequired));
            HomeOIListx1Request.addSoapObject(HomeOIx1Request);

            HomeOIListRequest.addSoapObject(HomeOIListx1Request.newInstance());
        }

        HomeSPRequest.addSoapObject(HomeOIListRequest);

        x1Request.addSoapObject(HomeSPRequest);

        SoapObject SubscriptionParametersRequest = new SoapObject(null, "Node");
        SubscriptionParametersRequest.addProperty("NodeName", "SubscriptionParameters");
        x1Request.addSoapObject(SubscriptionParametersRequest);

        SoapObject CredentialRequest = new SoapObject(null, "Node");
        CredentialRequest.addProperty("NodeName", "Credential");

        SoapObject CreationDateRequest = new SoapObject(null, "Node");
        CreationDateRequest.addProperty("NodeName", "CreationDate");
        CreationDateRequest.addProperty("Value", credInfo.credential.CreationDate);
        CredentialRequest.addSoapObject(CreationDateRequest);

        SoapObject UsernamePasswordRequest = new SoapObject(null, "Node");
        UsernamePasswordRequest.addProperty("NodeName", "UsernamePassword");

        SoapObject UsernameRequest = new SoapObject(null, "Node");
        UsernameRequest.addProperty("NodeName", "Username");
        UsernameRequest.addProperty("Value", credInfo.credential.usernamePassword.Username);
        UsernamePasswordRequest.addSoapObject(UsernameRequest);

        SoapObject PasswordRequest = new SoapObject(null, "Node");
        PasswordRequest.addProperty("NodeName", "Password");
        PasswordRequest.addProperty("Value", credInfo.credential.usernamePassword.Password);
        UsernamePasswordRequest.addSoapObject(PasswordRequest);

        SoapObject MachineManagedRequest = new SoapObject(null, "Node");
        MachineManagedRequest.addProperty("NodeName", "MachineManaged");
        if (credInfo.credential.usernamePassword.MachineManaged) {
            MachineManagedRequest.addProperty("Value", "TRUE");
        } else {
            MachineManagedRequest.addProperty("Value", "FALSE");
        }
        UsernamePasswordRequest.addSoapObject(MachineManagedRequest);

        SoapObject EAPMethodRequest = new SoapObject(null, "Node");
        EAPMethodRequest.addProperty("NodeName", "EAPMethod");

        SoapObject EAPTypeRequest = new SoapObject(null, "Node");
        EAPTypeRequest.addProperty("NodeName", "EAPType");
        EAPTypeRequest.addProperty("Value", credInfo.credential.usernamePassword.eAPMethod.EAPType);
        EAPMethodRequest.addSoapObject(EAPTypeRequest);

        SoapObject InnerMethodRequest = new SoapObject(null, "Node");
        InnerMethodRequest.addProperty("NodeName", "InnerMethod");
        InnerMethodRequest.addProperty("Value",
                credInfo.credential.usernamePassword.eAPMethod.InnerMethod);
        EAPMethodRequest.addSoapObject(InnerMethodRequest);
        UsernamePasswordRequest.addSoapObject(EAPMethodRequest);

        CredentialRequest.addSoapObject(UsernamePasswordRequest);

        SoapObject RealmRequest = new SoapObject(null, "Node");
        RealmRequest.addProperty("NodeName", "Realm");
        RealmRequest.addProperty("Value", credInfo.credential.Realm);
        CredentialRequest.addSoapObject(RealmRequest);

        x1Request.addSoapObject(CredentialRequest);

        PerProviderSubscriptionRequest.addSoapObject(x1Request);

        MgmtTreeRequest.addSoapObject(PerProviderSubscriptionRequest);

        nsRequest.addSoapObject(MgmtTreeRequest);

        return nsRequest;
    }

    private SoapObject getPolicyUpdateRequest(String requestReason) {
        // Construct sppPostDevData element
        SoapObject request = new SoapObject(NAMESPACE_NS, WIFI_SOAP_POST_DEV_DATA);
        //sppVersion
        AttributeInfo attributeInfo = new AttributeInfo();
        attributeInfo.setName(WIFI_SOAP_SPP_VERSION);
        attributeInfo.setValue("1.0");
        attributeInfo.setType("PropertyInfo.STRING_CLASS");
        attributeInfo.setNamespace(NAMESPACE_NS);
        request.addAttribute(attributeInfo);
        //requestReason
        request.addAttribute(WIFI_SOAP_REQ_REASON, requestReason);
        //sessionID
        if (!POL_UPDATE.equals(requestReason)) {
            AttributeInfo sessionIDattributeInfo = new AttributeInfo();
            sessionIDattributeInfo.setName(WIFI_SOAP_SESSIONID);
            sessionIDattributeInfo.setValue(mSessionID);
            sessionIDattributeInfo.setType("PropertyInfo.STRING_CLASS");
            sessionIDattributeInfo.setNamespace(NAMESPACE_NS);
            request.addAttribute(sessionIDattributeInfo);
        }

        //New supportedSPPVersions element
        request.addProperty(WIFI_SOAP_S_SPP_VERSION, "1.0");
        //New supportedMOList
        request.addProperty(WIFI_SOAP_S_MOLIST, WIFI_SOAP_S_MOSUBSCRIPTION
                + " " + WIFI_SOAP_S_MODEVINFO
                + " " + WIFI_SOAP_S_MODEVDETAIL
                + " " + WIFI_SOAP_S_MOHS20);

        //New moContainer
        //Construct moContainer
        SoapObject moInfo = getMoInfo();
        SoapObject moDetail = getMoDetail();
        request.addSoapObject(moInfo);
        request.addSoapObject(moDetail);

        //New moContainer
        //Construct moContainer
        if (POL_MO_UPLOAD.equals(requestReason)) {
            SoapObject subscription = getSubscription();
            request.addSoapObject(subscription);
        }
        return request;
    }

    private SoapObject getUpdateResponse(boolean errorOccur, String reason) {
        // Construct sppUpdateResponse element
        SoapObject request = new SoapObject(NAMESPACE_NS, WIFI_SOAP_UPDATE_RESPONSE);
        //sppVersion
        AttributeInfo attributeInfo = new AttributeInfo();
        attributeInfo.setName(WIFI_SOAP_SPP_VERSION);
        attributeInfo.setValue("1.0");
        attributeInfo.setType("PropertyInfo.STRING_CLASS");
        attributeInfo.setNamespace(NAMESPACE_NS);
        request.addAttribute(attributeInfo);
        //sppStatus
        AttributeInfo attributeSppStatus = new AttributeInfo();
        attributeSppStatus.setName(WIFI_SOAP_SPP_STATUS);
        if (errorOccur == true) {
            attributeSppStatus.setValue(WIFI_SOAP_SPP_STATUS_ERROR_OCCURRED);
        }
        else {
            attributeSppStatus.setValue(WIFI_SOAP_SPP_STATUS_OK);
        }
        attributeSppStatus.setType("PropertyInfo.STRING_CLASS");
        attributeSppStatus.setNamespace(NAMESPACE_NS);
        request.addAttribute(attributeSppStatus);
        //sessionID
        AttributeInfo sessionIDattributeInfo = new AttributeInfo();
        sessionIDattributeInfo.setName(WIFI_SOAP_SESSIONID);
        sessionIDattributeInfo.setValue(mSessionID);
        sessionIDattributeInfo.setType("PropertyInfo.STRING_CLASS");
        sessionIDattributeInfo.setNamespace(NAMESPACE_NS);
        request.addAttribute(sessionIDattributeInfo);

        if (errorOccur == true) {
            //sppError
            SoapObject sppError = new SoapObject(NAMESPACE_NS, WIFI_SOAP_SPP_ERROR);
            //errorCode
            AttributeInfo attributeInfoErrorCode = new AttributeInfo();
            attributeInfoErrorCode.setName(WIFI_SOAP_ERROR_CODE);
            attributeInfoErrorCode.setValue(reason);
            attributeInfoErrorCode.setType("PropertyInfo.STRING_CLASS");
            sppError.addAttribute(attributeInfoErrorCode);

            request.addSoapObject(sppError);
        }

        return request;
    }

    private SoapObject getMoInfo() {
        //New moContainer
        //Construct moContainer
        SoapObject dmMoRequest = new SoapObject(NAMESPACE_NS, WIFI_SOAP_MO_CONTAINER);
        AttributeInfo attributeInfo = new AttributeInfo();
        attributeInfo.setName(WIFI_SOAP_MO_URN);
        attributeInfo.setValue(WIFI_SOAP_S_MODEVINFO);
        attributeInfo.setType("PropertyInfo.STRING_CLASS");
        attributeInfo.setNamespace(NAMESPACE_NS);
        dmMoRequest.addAttribute(attributeInfo);

        //DevInfo
        /*
        sample:
                <![CDATA[ <MgmtTree>
                    <VerDTD>1.2</VerDTD>
                        <Node>
                            <NodeName>DevInfo</NodeName>
                                <RTProperties>
                                <Type>
                                    <DDFName>urn:oma:mo:oma-dm-devinfo:1.0</DDFName>
                                </Type>
                                </RTProperties>
                                <Node>
                                    <NodeName>DevID</NodeName>
                                    <Value>urn:acme:00-11-22-33-44-55</Value>
                                </Node>
                                <Node>
                                    <NodeName>Man</NodeName>
                                    <Value>ACME</Value>
                                </Node>
                                <Node>
                                    <NodeName>Mod</NodeName>
                                    <Value>HS2.0-01</Value>
                                </Node>
                                <Node>
                                    <NodeName>DmV</NodeName>
                                    <Value>1.2</Value>
                                </Node>
                                <Node>
                                    <NodeName>Lang</NodeName>
                                    <Value>en-US</Value>
                                </Node>
                        </Node>
                    </MgmtTree> ]]>
        */

        SoapObject devInfoRequest = new SoapObject(null, WIFI_SOAP_MGMTREE);
        //xmlns
        AttributeInfo MoDetailattributeInfo = new AttributeInfo();
        MoDetailattributeInfo.setName(WIFI_SOAP_MO_XMLNS);
        MoDetailattributeInfo.setValue(WIFI_SOAP_SYNCML_DMDDF_1_2);
        MoDetailattributeInfo.setType("PropertyInfo.STRING_CLASS");
        devInfoRequest.addAttribute(MoDetailattributeInfo);

        devInfoRequest.addProperty("VerDTD", "1.2");

        SoapObject node1Request = new SoapObject(null, "Node");
        node1Request.addProperty("NodeName", "DevInfo");

        SoapObject rtPropertiesRequest = new SoapObject(null, "RTProperties");
        SoapObject typeRequest = new SoapObject(null, "Type");
        typeRequest.addProperty("DDFName", WIFI_SOAP_S_MODEVINFO);
        rtPropertiesRequest.addSoapObject(typeRequest);
        node1Request.addSoapObject(rtPropertiesRequest);

        SoapObject node1aRequest = new SoapObject(null, "Node");
        node1aRequest.addProperty("NodeName", "DevId");
        node1aRequest.addProperty("Value", "imei:" + getDeviceId());
        node1Request.addSoapObject(node1aRequest);

        SoapObject node1bRequest = new SoapObject(null, "Node");
        node1bRequest.addProperty("NodeName", "Man");
        node1bRequest.addProperty("Value", "Google");
        node1Request.addSoapObject(node1bRequest);

        SoapObject node1cRequest = new SoapObject(null, "Node");
        node1cRequest.addProperty("NodeName", "Mod");
        node1cRequest.addProperty("Value", "HS20-station");
        node1Request.addSoapObject(node1cRequest);

        SoapObject node1dRequest = new SoapObject(null, "Node");
        node1dRequest.addProperty("NodeName", "DmV");
        node1dRequest.addProperty("Value", "1.2");
        node1Request.addSoapObject(node1dRequest);

        SoapObject node1eRequest = new SoapObject(null, "Node");
        node1eRequest.addProperty("NodeName", "Lang");
        //node1eRequest.addProperty("Value", "en-US");
        node1eRequest.addProperty("Value", get2LettersSystemLanguageCode());
        node1Request.addSoapObject(node1eRequest);

        devInfoRequest.addSoapObject(node1Request);

        //Add to DM tree
        dmMoRequest.addSoapObject(devInfoRequest);

        return dmMoRequest;
    }

    private SoapObject getMoDetail() {
        //New moContainer
        //Construct moContainer
        Log.d(TAG, "[getMoDetail]");
        SoapObject dmMoRequest = new SoapObject(NAMESPACE_NS, WIFI_SOAP_MO_CONTAINER);
        AttributeInfo attributeInfo = new AttributeInfo();
        attributeInfo.setName(WIFI_SOAP_MO_URN);
        attributeInfo.setValue(WIFI_SOAP_S_MODEVDETAIL);
        attributeInfo.setType("PropertyInfo.STRING_CLASS");
        attributeInfo.setNamespace(NAMESPACE_NS);
        dmMoRequest.addAttribute(attributeInfo);

        //DevDetail
        /*
        sample:
             <![CDATA[ <MgmtTree>
                 <VerDTD>1.2</VerDTD>
                 <Node>
                     <NodeName>DevDetail</NodeName>
                     <RTProperties>
                         <Type>
                             <DDFName>urn:oma:mo:oma-dm-devdetail:1.0</DDFName>
                         </Type>
                     </RTProperties>
                 </Node>
                 <Node>
                     <NodeName>URI</NodeName>
                     <Node>
                         <NodeName>MaxDepth</NodeName>
                         <Value> 32 </Value>
                     </Node>
                     <Node>
                         <NodeName>MaxTotLen</NodeName>
                         <Value> 2048 </Value>
                     </Node>
                     <Node>
                         <NodeName>MaxSegLen</NodeName>
                         <Value> 64 </Value>
                     </Node>
                 </Node>
                 <Node>
                     <NodeName>DevType</NodeName>
                     <Value> Smartphone </Value>
                 </Node>
                 <Node>
                     <NodeName>OEM</NodeName>
                     <Value> ACME </Value>
                 </Node>
                 <Node>
                     <NodeName>FmV</NodeName>
                     <Value> 1.2.100.5 </Value>
                 </Node>
                 <Node>
                     <NodeName>SmV</NodeName>
                     <Value> 9.11.130 </Value>
                 </Node>
                 <Node>
                     <NodeName>HmV</NodeName>
                     <Value> 1.0 </Value>
                 </Node>
                 <Node>
                     <NodeName>LrgObj</NodeName>
                     <Value>FALSE</Value>
                 </Node>
             </MgmtTree> ]]>
        */
        SoapObject MgmtTreeRequest = new SoapObject(null, WIFI_SOAP_MGMTREE);
        //xmlns
        AttributeInfo MoDetailattributeInfo = new AttributeInfo();
        MoDetailattributeInfo.setName(WIFI_SOAP_MO_XMLNS);
        MoDetailattributeInfo.setValue(WIFI_SOAP_SYNCML_DMDDF_1_2);
        MoDetailattributeInfo.setType("PropertyInfo.STRING_CLASS");
        MgmtTreeRequest.addAttribute(MoDetailattributeInfo);

        //VerDTD
        MgmtTreeRequest.addProperty("VerDTD", "1.2");

        //DevDetail
        SoapObject DevDetailRequest = new SoapObject(null, "Node");
        DevDetailRequest.addProperty("NodeName", "DevDetail");

        SoapObject rtPerpertiesRequest = new SoapObject(null, "RTProperties");
        SoapObject typePerpertiesRequest = new SoapObject(null, "Type");
        typePerpertiesRequest.addProperty("DDFName", WIFI_SOAP_S_MODEVDETAIL);
        rtPerpertiesRequest.addSoapObject(typePerpertiesRequest);
        DevDetailRequest.addSoapObject(rtPerpertiesRequest);

        //Ext
        SoapObject extRequest = new SoapObject(null, "Node");
        extRequest.addProperty("NodeName", "Ext");

        SoapObject wifiOrgRequest = new SoapObject(null, "Node");
        wifiOrgRequest.addProperty("NodeName", "org.wi-fi");

        SoapObject wifiRequest = new SoapObject(null, "Node");
        wifiRequest.addProperty("NodeName", "Wi-Fi");
        //EAPMethodList
        SoapObject eapMethodListRequest = new SoapObject(null, "Node");
        eapMethodListRequest.addProperty("NodeName", "EAPMethodList");

        SoapObject eapMethod1Request = new SoapObject(null, "Node");
        eapMethod1Request.addProperty("NodeName", "EAPMethod1");

        SoapObject eapMethod1 = new SoapObject(null, "Node");
        eapMethod1.addProperty("NodeName", "EAPMethod");//TLS
        eapMethod1.addProperty("Value", "13");
        eapMethod1Request.addSoapObject(eapMethod1);
        eapMethodListRequest.addSoapObject(eapMethod1Request);

        SoapObject eapMethod2Request = new SoapObject(null, "Node");
        eapMethod2Request.addProperty("NodeName", "EAPMethod2");

        SoapObject eapMethod2 = new SoapObject(null, "Node");
        eapMethod2.addProperty("NodeName", "EAPMethod");//TTLS
        eapMethod2.addProperty("Value", "21");
        eapMethod2.addProperty("NodeName", "InnerEAPMethod");//MSCHAPv2
        eapMethod2.addProperty("Value", "27");
        eapMethod2Request.addSoapObject(eapMethod2);
        eapMethodListRequest.addSoapObject(eapMethod2Request);

        SoapObject eapMethod3Request = new SoapObject(null, "Node");
        eapMethod3Request.addProperty("NodeName", "EAPMethod3");

        SoapObject eapMethod3 = new SoapObject(null, "Node");
        eapMethod3.addProperty("NodeName", "EAPMethod");//SIM
        eapMethod3.addProperty("Value", "18");
        eapMethod3Request.addSoapObject(eapMethod3);
        eapMethodListRequest.addSoapObject(eapMethod3Request);

        SoapObject eapMethod4Request = new SoapObject(null, "Node");
        eapMethod4Request.addProperty("NodeName", "EAPMethod4");

        SoapObject eapMethod4 = new SoapObject(null, "Node");
        eapMethod4.addProperty("NodeName", "EAPMethod");//AKA
        eapMethod4.addProperty("Value", "23");
        eapMethod4Request.addSoapObject(eapMethod4);
        eapMethodListRequest.addSoapObject(eapMethod4Request);

        wifiRequest.addSoapObject(eapMethodListRequest);

        //IMSI
        if ("false".equals(SystemProperties.get("persist.service.manual.ut.rem"))) {
            WifiPasspointDmTree.CredentialInfo credInfo = mTreeHelper.getCredentialInfo(mSoapTree,
                    mCred.getWifiSpFqdn(), mCred.getCredName());
            if (credInfo != null &&
                    credInfo.credential != null &&
                    credInfo.credential.sim != null) {
                mImsi = credInfo.credential.sim.IMSI;
                SoapObject imsiRequest = new SoapObject(null, "Node");
                imsiRequest.addProperty("NodeName", "IMSI");
                imsiRequest.addProperty("Value", mImsi);
                wifiRequest.addSoapObject(imsiRequest);
            }
        } else {
            SoapObject imsiRequest = new SoapObject(null, "Node");
            imsiRequest.addProperty("NodeName", "IMSI");
            imsiRequest.addProperty("Value", "40002600000004");
            wifiRequest.addSoapObject(imsiRequest);
        }

        //ManufacturingCertificate
        SoapObject manufactCertRequest = new SoapObject(null, "Node");
        manufactCertRequest.addProperty("NodeName", "ManufacturingCertificate");
        manufactCertRequest.addProperty("Value", "false");
        wifiRequest.addSoapObject(manufactCertRequest);

        // Wi-FiMACAddress
        SoapObject wifiMacAddressRequest = new SoapObject(null, "Node");
        wifiMacAddressRequest.addProperty("NodeName", "Wi-FiMACAddress");
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            Log.d(TAG, "no ConnectionInfo found");
            return null;
        }
        wifiMacAddressRequest.addProperty("Value", wifiInfo.getMacAddress().replace(":", "")
                .toLowerCase());
        wifiRequest.addSoapObject(wifiMacAddressRequest);
        //ClientTriggerRedirectURI
        //wifiRequest.addProperty("ClientTriggerRedirectURI", "http://127.0.0.1:54685");
        wifiOrgRequest.addSoapObject(wifiRequest);
        extRequest.addSoapObject(wifiOrgRequest);
        DevDetailRequest.addSoapObject(extRequest);

        //URI
        SoapObject URIRequest = new SoapObject(null, "Node");
        URIRequest.addProperty("NodeName", "URI");

        SoapObject MaxDepthRequest = new SoapObject(null, "Node");
        MaxDepthRequest.addProperty("NodeName", "MaxDepth");
        MaxDepthRequest.addProperty("Value", "32");
        URIRequest.addSoapObject(MaxDepthRequest);

        SoapObject MaxTotLenRequest = new SoapObject(null, "Node");
        MaxTotLenRequest.addProperty("NodeName", "MaxTotLen");
        MaxTotLenRequest.addProperty("Value", "2048");
        URIRequest.addSoapObject(MaxTotLenRequest);

        SoapObject MaxSegLenRequest = new SoapObject(null, "Node");
        MaxSegLenRequest.addProperty("NodeName", "MaxSegLen");
        MaxSegLenRequest.addProperty("Value", "64");
        URIRequest.addSoapObject(MaxSegLenRequest);

        DevDetailRequest.addSoapObject(URIRequest);

        //Required property
        SoapObject DevTypeRequest = new SoapObject(null, "Node");
        DevTypeRequest.addProperty("NodeName", "DevType");
        DevTypeRequest.addProperty("Value", "MobilePhone");
        DevDetailRequest.addSoapObject(DevTypeRequest);

        SoapObject OEMRequest = new SoapObject(null, "Node");
        OEMRequest.addProperty("NodeName", "OEM");
        OEMRequest.addProperty("Value", "GOOGLE");
        DevDetailRequest.addSoapObject(OEMRequest);

        SoapObject FwVRequest = new SoapObject(null, "Node");
        FwVRequest.addProperty("NodeName", "FwV");
        FwVRequest.addProperty("Value", "1.0");
        DevDetailRequest.addSoapObject(FwVRequest);

        SoapObject SwVRequest = new SoapObject(null, "Node");
        SwVRequest.addProperty("NodeName", "SwV");
        SwVRequest.addProperty("Value", "1.0");
        DevDetailRequest.addSoapObject(SwVRequest);

        SoapObject HwVRequest = new SoapObject(null, "Node");
        HwVRequest.addProperty("NodeName", "HwV");
        HwVRequest.addProperty("Value", "1.0");
        DevDetailRequest.addSoapObject(HwVRequest);

        SoapObject LrgObjRequest = new SoapObject(null, "Node");
        LrgObjRequest.addProperty("NodeName", "LrgObj");
        LrgObjRequest.addProperty("Value", "FALSE");
        DevDetailRequest.addSoapObject(LrgObjRequest);
        MgmtTreeRequest.addSoapObject(DevDetailRequest);

        //Add to DM tree
        dmMoRequest.addSoapObject(MgmtTreeRequest);

        return dmMoRequest;
    }

    private String checkErrorCode(Document doc) {
        return null;
    }

    private String checkStatus(Document doc) {
        if (doc == null) {
            return null;
        }

        NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, "sppPostDevDataResponse");
        if (list.getLength() != 0) {
            Log.d(TAG, "[checkStatus(Document doc)] format type Q can be removed");

            Element element = (Element) list.item(0);
            String sppStatus = element.getAttributeNS(NAMESPACE_NS, WIFI_SOAP_SPP_STATUS);
            Log.d(TAG, "sppStatus: " + sppStatus);
            if (WIFI_SOAP_SPP_STATUS_OK.equals(sppStatus)
                    || WIFI_SOAP_SPP_STATUS_PROVISION_COMPLETE.equals(sppStatus)
                    || WIFI_SOAP_SPP_STATUS_REMEDIATION_COMPLETE.equals(sppStatus)
                    || WIFI_SOAP_SPP_STATUS_UPDATE_COMPLETE.equals(sppStatus)) {
                mSessionID = element.getAttributeNS(NAMESPACE_NS, "sessionID");
                Log.d(TAG, "sessionID: " + mSessionID);
            }
            return sppStatus;
        }

        return null;
    }

    private String checkStatus(Document doc, String tagName) {
        if (doc == null) {
            return null;
        }

        NodeList list = doc.getElementsByTagName(tagName);

        if (list.getLength() == 0) {
            return null;
        }

        Element element = (Element) list.item(0);
        String att = element.getAttribute("spp:sppStatus");
        Log.d(TAG, "att:" + att);
        return att;
    }

    private String checkStatus(Document doc, String namespace, String tagName) {
        if (doc == null) {
            return null;
        }

        NodeList list = doc.getElementsByTagNameNS(namespace, tagName);

        if (list.getLength() == 0) {
            return null;
        }

        Element element = (Element) list.item(0);
        String att = element.getAttributeNS(namespace, WIFI_SOAP_SPP_STATUS);
        Log.d(TAG, "att:" + att);
        return att;
    }

    private String getErrorCode(Document doc) {
        NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, WIFI_SOAP_SPP_ERROR);
        if (list.getLength() != 0) {
            Element element = (Element) list.item(0);
            String errorCode = element.getAttribute(WIFI_SOAP_SPP_ERROR);
            Log.d(TAG, "errorCode: " + errorCode);
            return errorCode;
        }
        return null;
    }

    private boolean getNoMoUpdate(Document doc) {
        Log.d(TAG, "[getNoMoUpdate]");
        NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, WIFI_SOAP_SPP_NOMOUPDATE);
        if (list.getLength() != 0) {
            mTarget.sendMessage(
                    WifiPasspointStateMachine.CMD_REMEDIATION_DONE, 0, 0, mSoapTree);// "DoUpdate"
            return true;
        }
        return false;
    }

    private Boolean getUploadMO(Document doc) {
        Log.d(TAG, "[getUploadMO]");
        NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, "uploadMO");
        if (list.getLength() != 0) {
            Element element = (Element) list.item(0);
            mUploadMO = element.getAttributeNS(NAMESPACE_NS, WIFI_SOAP_MO_URN);
            Log.d(TAG, "Upload MO: " + mUploadMO);
            if (mUploadMO != null || !mUploadMO.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private Boolean getEnrollmentInfo(Document doc) {
        Log.d(TAG, "[getEnrollmentInfo]");
        try {
            NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, "getCertificate");
            if (list.getLength() != 0) {
                Log.d(TAG, "[getEnrollmentInfo] got getCertificate:");
                Element eElement = (Element) list.item(0);
                String att = eElement.getAttribute("enrollmentProtocol");
                if (!"EST".equals(att)) { // EST is allowed only
                    return false;
                }
                mEnrollmentServerURI = mPpsmoParser.getTagValue(NAMESPACE_NS,
                        "enrollmentServerURI", eElement);
                Log.d(TAG, "EnrollmentServerURI: " + mEnrollmentServerURI);
                mEnrollmentServerCert = mPpsmoParser.getTagValue(NAMESPACE_NS,
                        "caCertificate", eElement);
                Log.d(TAG, "caCertificate: " + mEnrollmentServerCert);
                enrollDigestUsername = mPpsmoParser.getTagValue(NAMESPACE_NS, "estUserID",
                        eElement);
                if (enrollDigestUsername == null) {
                    enrollDigestUsername = null;
                }
                Log.d(TAG, "enrollDigestUsername: " + enrollDigestUsername);
                String enrollDigestPasswordBase64 = mPpsmoParser.getTagValue(NAMESPACE_NS,
                        "estPassword", eElement);
                if (enrollDigestPasswordBase64 == null) {
                    enrollDigestPassword = null;
                } else {
                    enrollDigestPassword = new String(Base64.decode(enrollDigestPasswordBase64));
                }
                Log.d(TAG, "enrollDigestPassword: " + enrollDigestPassword);
            } else {
                return false;
            }
            return true;
        } catch (Exception ee) {
            ee.printStackTrace();
            return false;
        }
    }

    private Boolean getUseClientCertTLS(Document doc) {
        Log.d(TAG, "[getUseClientCertTLS]");
        try {
            NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, "useClientCertTLS");
            if (list.getLength() != 0) {
                Element eElement = (Element) list.item(0);
                String attAcceptMfgCerts = eElement.getAttributeNS(NAMESPACE_NS, "acceptMfgCerts");// true/false
                String attAcceptProviderCerts = eElement.getAttributeNS(NAMESPACE_NS,
                        "acceptProviderCerts"); // providerIssuerName

                if (attAcceptProviderCerts.equalsIgnoreCase("true")) {
                    providerIssuerName = mPpsmoParser.getTagValue(NAMESPACE_NS,
                            "providerIssuerName", eElement);
                } else {
                    providerIssuerName = null;
                }
            } else {
                return false;
            }
            return true;
        } catch (Exception ee) {
            ee.printStackTrace();
            return false;
        }
    }

    private String getSPPTreeUri(Document doc, String execution) {
        // ex: spp:addMO
        // spp:managementTreeURI="./Wi-Fi/wi-fi.org/PerProviderSubscription"
        Log.d(TAG, "[getSPPTreeUri]");
        NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, execution);
        if (list.getLength() != 0) {
            Element element = (Element) list.item(0);
            String sppTreeUri = element.getAttributeNS(NAMESPACE_NS, "managementTreeURI");
            Log.d(TAG, "managementTreeURI: " + sppTreeUri);

            if (sppTreeUri != null && !sppTreeUri.isEmpty()) {
                return sppTreeUri;
            }
        }

        return null;
    }

    private String getWifiSpFqdnFromMoTree(Document doc, String execution) {
        // ex: spp:addMO
        // spp:managementTreeURI="./Wi-Fi/wi-fi.org/PerProviderSubscription"
        Log.d(TAG, "[getWifiSpFqdnFromMoTree]");
        String sppTreeUri = getSPPTreeUri(doc, execution);

        if (sppTreeUri != null && !sppTreeUri.isEmpty()) {
            String[] words = sppTreeUri.split("/");
            Log.d(TAG, "wifiSPFQDN: " + words[2]);
            return words[2];
        }

        return null;
    }

    private boolean checkWifiSpFqdnForAddMo(Document doc) {
        Log.d(TAG, "[checkWifiSpFqdnForAddMo]");
        mSpFqdnFromMo = getWifiSpFqdnFromMoTree(doc, "addMO");
        Log.d(TAG, "current wifiSPFQDN: " + mSpFqdn + ", wifiSPFQDN From MO: " + mSpFqdnFromMo);
        if (mSpFqdnFromMo != null && mSpFqdn.endsWith(mSpFqdnFromMo)) {
            Log.d(TAG, "[checkWifiSpFqdnForAddMo] pass");
            return true;
        }
        Log.d(TAG, "[checkWifiSpFqdnForAddMo] fail");
        return false;
    }

    private boolean checkWifiSpFqdnForUpdateMo(Document doc) {
        Log.d(TAG, "[checkWifiSpFqdnForUpdateMo]");
        mSpFqdnFromMo = getWifiSpFqdnFromMoTree(doc, "updateNode");
        Log.d(TAG, "current wifiSPFQDN: " + mSpFqdn + ", wifiSPFQDN From MO: " + mSpFqdnFromMo);
        if (mSpFqdnFromMo != null && mSpFqdn.endsWith(mSpFqdnFromMo)) {
            Log.d(TAG, "[checkWifiSpFqdnForUpdateMo] pass");
            return true;
        }
        Log.d(TAG, "[checkWifiSpFqdnForUpdateMo] fail");
        return false;
    }

    private boolean checkWifiSpFqdnForUploadMo(Document doc) {
        Log.d(TAG, "[checkWifiSpFqdnForUploadMo]");
        mSpFqdnFromMo = getWifiSpFqdnFromMoTree(doc, "uploadMO");
        Log.d(TAG, "current wifiSPFQDN: " + mSpFqdn + ", wifiSPFQDN From MO: " + mSpFqdnFromMo);
        if (mSpFqdnFromMo != null && mSpFqdn.endsWith(mSpFqdnFromMo)) {
            Log.d(TAG, "[checkWifiSpFqdnForUploadMo] pass");
            return true;
        }
        Log.d(TAG, "[checkWifiSpFqdnForUploadMo] fail");
        return false;
    }

    private Boolean getSubscriptionSignUpAndUserUpdate(Document doc) {
        Log.d(TAG, "[getSubscriptionSignUpAndUserUpdate]");
        NodeList list = doc.getElementsByTagNameNS(NAMESPACE_NS, "exec");

        for (int tmp = 0; tmp < list.getLength(); tmp++) {
            Node nNode = list.item(tmp);
            if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                try {
                    Element eElement = (Element) nNode;
                    Log.d(TAG,
                            "launchBrowserToURI : "
                                    + mPpsmoParser.getTagValue(NAMESPACE_NS,
                                            "launchBrowserToURI", eElement));
                    String uri = mPpsmoParser.getTagValue(NAMESPACE_NS,
                            "launchBrowserToURI", eElement);
                    if (uri == null) {
                        return false;
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);

                    Log.d(TAG, "value : " + uri);
                    return true;
                } catch (Exception ee) {
                    ee.printStackTrace();
                    return false;
                }
            }
        }

        return false;
    }

    private String get2LettersSystemLanguageCode() {
        Locale loc = Locale.getDefault();
        return loc.getLanguage();
    }

    private String filenameFromURI(String uri) {
        String filename = uri.substring(uri.lastIndexOf("/") + 1);
        return filename;
    }

    private boolean checkExtendedKeyUsageIdKpClientAuth(final X509Certificate x509Cert) {
        boolean result = true;
        try {
            List<String> extKeyUsages = x509Cert.getExtendedKeyUsage();
            if (extKeyUsages != null) {
                result = false;
                for (String extKeyUsage : extKeyUsages) {
                    Log.d(TAG2, "ExtendedKeyUsage:" + extKeyUsage);
                    if (extKeyUsage.equals(KeyPurposeId.id_kp_serverAuth.toString())) {
                        Log.d(TAG, "Server certificate EKU includes id_kp_serverAuth, true");
                        result = true;
                        break;
                    }
                }
            }

        } catch (CertificateParsingException e) {
            e.printStackTrace();
        }

        if (!result)
            Log.d(TAG, "Server certificate EKU not includes id_kp_serverAuth, false");
        return result;

    }

    private boolean checkSubjectAltNameOtherNameSPLangFriendlyName(final X509Certificate x509Cert) {
        boolean result = false;

        if (mOSUFriendlyName == null) {
            return false;
        } else if (mOSUFriendlyName.isEmpty()) {
            return false;
        }

        try {
            Collection c = x509Cert.getSubjectAlternativeNames();

            if (c != null) {
                Iterator it = c.iterator();
                while (it.hasNext()) {
                    List gn = (List) it.next();
                    Integer tag = (Integer) gn.get(0);
                    if (tag == GeneralName.otherName) {
                        Log.d(TAG2, "SubjectAltName OtherName:"
                                + gn.get(1).toString());

                        ByteArrayInputStream bais = new ByteArrayInputStream((byte[]) gn.get(1));
                        ASN1InputStream asn1_is = new ASN1InputStream(bais);
                        Object asn1Obj = (Object) asn1_is.readObject();
                        Log.d(TAG2, ASN1Dump.dumpAsString(asn1Obj, true));

                        DERTaggedObject derTagObj = (DERTaggedObject) asn1Obj;
                        DERSequence derSeq = (DERSequence) derTagObj.getObject();
                        Enumeration enu = derSeq.getObjects();
                        DERObjectIdentifier oid = (DERObjectIdentifier) ((ASN1Encodable) enu
                                .nextElement()).toASN1Primitive();
                        Log.d(TAG2, "    OID:" + oid.toString());

                        if ("1.3.6.1.4.1.40808.1.1.1".equals(oid.toString())) { // id-wfa-hotspot-friendly-name
                            DERTaggedObject dertagObj = (DERTaggedObject) ((ASN1Encodable) enu
                                    .nextElement()).toASN1Primitive();
                            ASN1Object derObj = dertagObj.getObject();
                            String spLangFriendlyName;
                            if (derObj instanceof DERUTF8String) {
                                DERUTF8String spLangFriendlyNameDERString = (DERUTF8String) (dertagObj
                                        .getObject());
                                spLangFriendlyName = spLangFriendlyNameDERString.toString();
                            } else {
                                DEROctetString spLangFriendlyNameDERString = (DEROctetString) (dertagObj
                                        .getObject());
                                spLangFriendlyName = spLangFriendlyNameDERString.toString();
                            }

                            Log.d(TAG,
                                    "language code and friendly name:"
                                            + spLangFriendlyName.toString());

                            // check language code
                            Log.d(TAG, "mOSULanguage = " + mOSULanguage);
                            if (spLangFriendlyName.substring(0, 3).equals(
                                    mOSULanguage.toLowerCase())) { // ISO639
                                Log.d(TAG, "Language code match");

                                // check friendly name
                                Log.d(TAG, "mOSUFriendlyName = " + mOSUFriendlyName);
                                if (mOSUFriendlyName != null && !mOSUFriendlyName.isEmpty()
                                        && mOSUFriendlyName.length() != 0) {
                                    if (spLangFriendlyName.substring(3).equals(mOSUFriendlyName)) {
                                        Log.d(TAG, "OSU friendly name match");
                                        result = true;
                                        return result;
                                    } else {
                                        Log.d(TAG, "OSU friendly name not match");
                                    }
                                }
                            } else {
                                Log.d(TAG, "Language code not match");
                            }
                        }
                    }
                }
                Log.d(TAG2, "Subject Alternative Names:" + c.toString());
            }
        } catch (CertificateParsingException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    private boolean checkSubjectAltNameDNSName(final X509Certificate x509Cert, final String fqdn,
            boolean suffixMatch) {
        boolean result = false;

        try {
            Collection c = x509Cert.getSubjectAlternativeNames();

            if (c != null) {
                Log.d(TAG2, "Subject Alternative Names:" + c.toString());

                Iterator it = c.iterator();
                while (it.hasNext()) {
                    List gn = (List) it.next();
                    Integer tag = (Integer) gn.get(0);
                    if (tag == GeneralName.dNSName) {
                        Log.d(TAG2, "Subject Alternative Name:" + gn.get(1));
                        if (suffixMatch) {
                            String value = (String) (gn.get(1));
                            if (value.endsWith(fqdn)) {
                                Log.d(TAG, "Subject Alternative DNS Name suffix match SPFQDN");
                                result = true;
                            }
                        } else {// complete match
                            if (gn.get(1).equals(fqdn)) {
                                Log.d(TAG, "Subject Alternative DNS Name complete match SPFQDN");
                                result = true;
                            }
                        }

                    }
                }
            }
        } catch (CertificateParsingException e) {
            e.printStackTrace();
        }

        return result;
    }

    private boolean checkLogotypeExtn(final X509Certificate x509Cert) {
        if (iconHash == null) { // icon doesn't successfully downloaded and
                                // displayed, bypass the check
            return true;
        }

        boolean result = true;

        try {
            // Extension Value

            byte[] logoType = x509Cert.getExtensionValue("1.3.6.1.5.5.7.1.12");
            if (logoType != null) {
                ByteArrayInputStream bais = new ByteArrayInputStream(logoType);
                ASN1InputStream asn1_is = new ASN1InputStream(bais);
                DEROctetString derObj = (DEROctetString) asn1_is.readObject();

                bais = (ByteArrayInputStream) derObj.getOctetStream();
                asn1_is = new ASN1InputStream(bais);
                DERSequence logoTypeExt = (DERSequence) asn1_is.readObject();
                Log.d(TAG2, "LogotypeExtn:" + logoTypeExt.toString());

                Enumeration LogotypeExtnSequence = logoTypeExt.getObjects();
                while (LogotypeExtnSequence.hasMoreElements()) {

                    DERTaggedObject LogotypeExtnTaggedObj = (DERTaggedObject) ((ASN1Encodable) LogotypeExtnSequence
                            .nextElement()).toASN1Primitive();
                    Log.d(TAG2, "LogotypeExtnTaggedObj:" + LogotypeExtnTaggedObj.toString());
                    Log.d(TAG2, "LogotypeExtnTaggedObj CHOICE: " + LogotypeExtnTaggedObj.getTagNo());

                    /*
                     * LogotypeExtn ::= SEQUENCE { communityLogos [0] EXPLICIT
                     * SEQUENCE OF LogotypeInfo OPTIONAL, issuerLogo [1]
                     * EXPLICIT LogotypeInfo OPTIONAL, subjectLogo [2] EXPLICIT
                     * LogotypeInfo OPTIONAL, otherLogos [3] EXPLICIT SEQUENCE
                     * OF OtherLogotypeInfo OPTIONAL }
                     */
                    if (LogotypeExtnTaggedObj.getTagNo() == 0) {// communityLogos

                        Log.d(TAG2, "");
                        DERSequence CommunityLogos = (DERSequence) LogotypeExtnTaggedObj
                                .getObject();
                        Log.d(TAG2, "communityLogos:" + CommunityLogos.toString());

                        Enumeration enu;
                        Enumeration CommunityLogosEnu = CommunityLogos.getObjects();
                        while (CommunityLogosEnu.hasMoreElements()) {
                            result = true;

                            DERTaggedObject CommunityLogosTaggedObj = (DERTaggedObject) ((ASN1Encodable) CommunityLogosEnu
                                    .nextElement()).toASN1Primitive();
                            Log.d(TAG2, "CommunityLogosTaggedObj CHOICE: "
                                    + CommunityLogosTaggedObj.getTagNo());
                            /*
                             * LogotypeInfo ::= CHOICE { direct [0]
                             * LogotypeData, indirect [1] LogotypeReference }
                             */
                            if (CommunityLogosTaggedObj.getTagNo() == 0) {// direct
                                /*********************************************
                                 ************ image *************
                                 *********************************************/
                                /*
                                 * LogotypeData ::= SEQUENCE { image SEQUENCE OF
                                 * LogotypeImage OPTIONAL, audio [1] SEQUENCE OF
                                 * LogotypeAudio OPTIONAL }
                                 */
                                DERSequence LogotypeData = (DERSequence) CommunityLogosTaggedObj
                                        .getObject();
                                ;
                                Log.d(TAG2, "LogotypeImage:" + LogotypeData.toString());
                                Enumeration LogotypeDataEnu = LogotypeData.getObjects();
                                while (LogotypeDataEnu.hasMoreElements()) {
                                    /*
                                     * LogotypeImage ::= SEQUENCE { imageDetails
                                     * LogotypeDetails, imageInfo
                                     * LogotypeImageInfo OPTIONAL }
                                     */
                                    DERSequence LogotypeImage = (DERSequence) ((ASN1Encodable) LogotypeDataEnu
                                            .nextElement()).toASN1Primitive();
                                    Log.d(TAG2, "LogotypeImage:" + LogotypeImage.toString());
                                    Enumeration LogotypeImageEnu = LogotypeImage.getObjects();
                                    while (LogotypeImageEnu.hasMoreElements()) {
                                        DERSequence imageDetails = (DERSequence) ((ASN1Encodable) LogotypeImageEnu
                                                .nextElement()).toASN1Primitive();
                                        /*
                                         * LogotypeImageInfo ::= SEQUENCE { type
                                         * [0] LogotypeImageType DEFAULT color,
                                         * fileSize INTEGER, -- In octets xSize
                                         * INTEGER, -- Horizontal size in pixels
                                         * ySize INTEGER, -- Vertical size in
                                         * pixels resolution
                                         * LogotypeImageResolution OPTIONAL,
                                         * language [4] IA5String OPTIONAL } --
                                         * RFC 3066 Language Tag
                                         */
                                        DERSequence imageInfo = (DERSequence) ((ASN1Encodable) LogotypeImageEnu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "imageInfo:" + imageInfo.toString());
                                        enu = imageInfo.getObjects();
                                        while (enu.hasMoreElements()) {
                                            ASN1Object info = ((ASN1Encodable) enu.nextElement())
                                                    .toASN1Primitive();
                                            Log.d(TAG2, "object:" + info.toString());
                                            if (info instanceof DERTaggedObject) {
                                                if (((DERTaggedObject) info).getTagNo() == 4) {
                                                    DEROctetString language = (DEROctetString) ((DERTaggedObject) info)
                                                            .getObject();
                                                    String languageCode = new String(
                                                            language.getEncoded()).substring(2);
                                                    Log.d(TAG2, "imageInfo language code:"
                                                            + languageCode);
                                                }
                                            }
                                        }

                                        /*
                                         * LogotypeDetails ::= SEQUENCE {
                                         * mediaType IA5String, -- MIME media
                                         * type name and optional -- parameters
                                         * logotypeHash SEQUENCE SIZE (1..MAX)
                                         * OF HashAlgAndValue, logotypeURI
                                         * SEQUENCE SIZE (1..MAX) OF IA5String }
                                         */
                                        Log.d(TAG2, "imageDetails:" + imageDetails.toString());
                                        enu = imageDetails.getObjects();

                                        // mediaType
                                        DERIA5String mediaType = (DERIA5String) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "mediaType:" + mediaType.toString());
                                        DERSequence logotypeHash = (DERSequence) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "logotypeHash:" + logotypeHash.toString());
                                        DERSequence logotypeURI = (DERSequence) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "logotypeURI:" + logotypeURI.toString());

                                        // logotypeURI
                                        enu = logotypeURI.getObjects();
                                        DERIA5String logotypeURIStr = (DERIA5String) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "logotypeURIStr:" + logotypeURIStr.toString());
                                        Log.d(TAG2,
                                                "filename : ("
                                                        + filenameFromURI(logotypeURI.toString())
                                                        + ")");
                                        if (iconFileName.equals(filenameFromURI(logotypeURIStr
                                                .toString()))) {
                                            Log.d(TAG, "Icon filename match");
                                            result = true;
                                        } else {
                                            Log.d(TAG, "Icon filename not match");
                                            result = false;
                                            continue;
                                        }

                                        // logotypeHash
                                        enu = logotypeHash.getObjects();
                                        DERSequence HashAlgAndValue = (DERSequence) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "HashAlgAndValue:" + HashAlgAndValue.toString());
                                        enu = HashAlgAndValue.getObjects();
                                        // hashAlg
                                        DERSequence hashAlg = (DERSequence) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "hashAlg:" + hashAlg.toString());
                                        // hashValue
                                        DEROctetString hashValue = (DEROctetString) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2, "hashValue:" + hashValue.toString());
                                        // hashAlg --> AlgorithmIdentifier
                                        enu = hashAlg.getObjects();
                                        DERObjectIdentifier AlgorithmIdentifier = (DERObjectIdentifier) ((ASN1Encodable) enu
                                                .nextElement()).toASN1Primitive();
                                        Log.d(TAG2,
                                                "AlgorithmIdentifier:"
                                                        + AlgorithmIdentifier.toString());
                                        // hashValue --> OctetString
                                        byte[] hashValueOctetString = hashValue.getOctets();
                                        Log.d(TAG2,
                                                "hashValueOctetString:"
                                                        + hashValueOctetString.toString());
                                        // String certIconHash =
                                        // octetStringToString(hashValue.toString().substring(1));
                                        String certIconHash = hashValue.toString().substring(1);
                                        Log.d(TAG2, "hashValue String:" + certIconHash);
                                        if (iconHash.equals(certIconHash)) {
                                            Log.d(TAG, "Icon hash match");
                                            return true;
                                        } else {
                                            Log.d(TAG, "Icon hash not match");
                                            result = false;
                                            continue;
                                        }

                                    }

                                }

                            }

                        }
                    }
                }
                Log.d(TAG2, "LogotypeExtn parsing done");
                return result;
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * A helper class for accessing the raw data in the intent extra and handling
     * certificates.
     */
    private class PpsmoParser {
        private static final String TAG = "PasspointPpsmoParser";

        PpsmoParser() {
        }

        public Document getDocument(String XML) {

            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setCoalescing(true);
                factory.setNamespaceAware(true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = null;

                XML = tearDownSpecialChars(XML);
                Log.d(TAG, "parseXML:" + XML);

                StringReader sr = new StringReader(XML);
                InputSource is = new InputSource(sr);

                try {
                    doc = (Document) builder.parse(is);
                } catch (IOException e) {
                    Log.e(TAG, "getDocument IOException:" + e);
                } catch (SAXException e) {
                    Log.e(TAG, "getDocument SAXException:" + e);
                }

                return doc;
            } catch (Exception e) {
                Log.e(TAG, "getDocument err:" + e);
            }

            return null;
        }

        public Vector<Document> getSPPNodes(Document doc, String namespace, String sTag) {
            Vector<Document> sppNodes = new Vector<Document>();
            NodeList tagElements = doc.getElementsByTagNameNS(namespace, sTag);
            if (tagElements.getLength() != 0) {
                try {
                    for (int i = 0; i < tagElements.getLength(); i++) {
                        Node nNode = tagElements.item(i);
                        Document newXmlDocument = DocumentBuilderFactory.newInstance()
                                .newDocumentBuilder().newDocument();
                        Element root = newXmlDocument.createElementNS(namespace, "root");
                        newXmlDocument.appendChild(root);

                        Node copyNode = newXmlDocument.importNode(nNode, true);
                        root.appendChild(copyNode);

                        sppNodes.add(newXmlDocument);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "getSPPNodes err:" + e);
                }
                return sppNodes;
            }
            return null;
        }

        private String tearDownSpecialChars(String XML) {
            //restore escaping symbol first (format v6)
            XML = XML.replaceAll("&lt;", "<");
            XML = XML.replaceAll("&gt;", ">");
            XML = XML.replaceAll("\\Q<![CDATA[\\E", "");
            XML = XML.replaceAll("\\Q]]>\\E", "");

            return XML;
        }

        public String getTagValue(String sTag, Element eElement) {
            try {
                NodeList tagElements = eElement.getElementsByTagName(sTag);
                if (tagElements != null && tagElements.item(0) != null) {
                    NodeList nlList = tagElements.item(0).getChildNodes();
                    Node nValue = (Node) nlList.item(0);

                    return nValue.getNodeValue();
                } else {
                    return null;
                }

            } catch (Exception e) {
                Log.e(TAG, "getTagValue err:" + e);
            }
            return null;
        }

        public String getTagValue(String namespace, String sTag, Element eElement) {
            try {
                NodeList tagElements = eElement.getElementsByTagNameNS(namespace, sTag);
                if (tagElements != null && tagElements.item(0) != null) {
                    NodeList nlList = tagElements.item(0).getChildNodes();
                    Node nValue = (Node) nlList.item(0);

                    return nValue.getNodeValue();
                } else {
                    return null;
                }

            } catch (Exception e) {
                Log.e(TAG, "getTagValue err:" + e);
            }
            return null;
        }

        public Document extractMgmtTree(String XML) {
            try {
                Document doc = getDocument(XML);
                Document newXmlDocument = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().newDocument();
                Element root = newXmlDocument.createElement("MgmtTree");
                newXmlDocument.appendChild(root);

                NodeList verDtd = doc.getElementsByTagName("VerDTD");
                Node copyNode = newXmlDocument.importNode(verDtd.item(0), true);
                root.appendChild(copyNode);

                NodeList nodes = doc.getElementsByTagName("Node");
                copyNode = newXmlDocument.importNode(nodes.item(0), true);
                root.appendChild(copyNode);

                return newXmlDocument;
            } catch (Exception e) {
                Log.e(TAG, "extractMgmtTree err:" + e);
            }
            return null;
        }

        public Document extractMgmtTree(Document doc) {
            try {
                Document newXmlDocument = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().newDocument();
                Element root = newXmlDocument.createElement("MgmtTree");
                newXmlDocument.appendChild(root);

                NodeList verDtd = doc.getElementsByTagName("VerDTD");
                Node copyNode = newXmlDocument.importNode(verDtd.item(0), true);
                root.appendChild(copyNode);

                NodeList nodes = doc.getElementsByTagName("Node");
                copyNode = newXmlDocument.importNode(nodes.item(0), true);
                root.appendChild(copyNode);

                return newXmlDocument;
            } catch (Exception e) {
                Log.e(TAG, "extractMgmtTree err:" + e);
            }
            return null;
        }

        public String xmlToString(Document doc) {
            try {
                TransformerFactory tf = TransformerFactory.newInstance();
                Transformer transformer = tf.newTransformer();
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                StringWriter writer = new StringWriter();
                transformer.transform(new DOMSource(doc), new StreamResult(writer));
                String output = removeComment(writer.getBuffer().toString().replaceAll("\n|\r", ""));
                return output;
            } catch (TransformerConfigurationException e) {
                Log.e(TAG, "xmlToString TransformerConfigurationException:" + e);
            } catch (TransformerException e) {
                Log.e(TAG, "xmlToString TransformerException:" + e);
            }
            return null;
        }

        private String removeComment(String XML) {
            XML = XML.replaceAll("(?s)<!--.*?-->", "");

            return XML;
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
            try {
                int i;
                for (i = 0; i < arg0.length; i++)
                {
                    Log.d(TAG, "X509Certificate: " + arg0[i]);
                    Log.d(TAG, "====================");

                    // check validity (not before and not after)
                    arg0[i].checkValidity();
                }

                // check root CA chaining
                KeyStore ks = KeyStore.getInstance("AndroidCAStore");
                ks.load(null, null);
                TrustManagerImpl tm = new TrustManagerImpl(ks);
                tm.checkServerTrusted(arg0, arg0[0].getPublicKey().getAlgorithm());

                // only check on OSU
                if (mOSUServerUrl != null && !mOSUServerUrl.isEmpty()) {
                    // check SP friendly name and Language code
                    if (!checkSubjectAltNameOtherNameSPLangFriendlyName(arg0[0])) {
                        throw new RuntimeException("id-wfa-hotspot-friendly-name check fail");
                    }

                    // check icon type hash value
                    if (!checkLogotypeExtn(arg0[0])) {
                        throw new RuntimeException("Certificate Logo icon hash doesn't match");
                    }
                }

                // check id-kp-clientAuth
                if (!checkExtendedKeyUsageIdKpClientAuth(arg0[0])) {
                    throw new RuntimeException("id-kp-clientAuth found");
                }

                // check SP-FQDN
                boolean suffixMatch;

                // check complete host name on OSU server
                if (mOSUServerUrl != null && !mOSUServerUrl.isEmpty()) {
                    suffixMatch = false;
                } else {
                    // check SPFQDN on subscription and policy server
                    suffixMatch = true;
                }
                if (!checkSubjectAltNameDNSName(arg0[0], mSpFqdn, suffixMatch)) {
                    throw new RuntimeException(
                            "Certificate Subject Alternative Name doesn't include SP-FQDN");
                }
            } catch (CertificateException e) {
                throw e;
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        public X509Certificate[] getAcceptedIssuers() {
            Log.d(TAG, "[getAcceptedIssuers] ");
            return null;
        }
    }
}
