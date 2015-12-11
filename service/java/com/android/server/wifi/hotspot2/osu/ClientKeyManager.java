package com.android.server.wifi.hotspot2.osu;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.X509KeyManager;

public class ClientKeyManager implements X509KeyManager {
    private final KeyStore mKeyStore;
    private final Map<OSUCertType, String> mAliasMap;
    private final Map<OSUCertType, Object> mTempKeys;

    private static final String sTempAlias = "client-alias";
    private static final Map<String, OSUCertType> sConfigMapping = new HashMap<>();

    static {
        sConfigMapping.put(WifiEnterpriseConfig.CLIENT_CERT_KEY, OSUCertType.Client);
        sConfigMapping.put(WifiEnterpriseConfig.CA_CERT_KEY, OSUCertType.CA);
        sConfigMapping.put(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, OSUCertType.PrivateKey);
    }

    public ClientKeyManager(WifiConfiguration config) throws IOException {
        android.security.keystore.AndroidKeyStoreProvider.install();
        try {
            mKeyStore = android.security.keystore.AndroidKeyStoreProvider.getKeyStoreForUid(android.os.Process.WIFI_UID);
        }
        catch (GeneralSecurityException gse) {
            throw new IOException("Failed to instantiate KeyStore: " + gse, gse);
        }

        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;
        if (enterpriseConfig == null) {
            throw new IOException("Missing enterprise configuration");
        }
        mAliasMap = populateAliases(enterpriseConfig.getFields());
        mTempKeys = new HashMap<>();
        //dumpKeyStore(mKeyStore, mAliasMap);
    }

    public void reloadKeys(Map<OSUCertType, List<X509Certificate>> certs, PrivateKey key)
            throws IOException {
        List<X509Certificate> clientCerts = certs.get(OSUCertType.Client);
        X509Certificate[] certArray = new X509Certificate[clientCerts.size()];
        int n = 0;
        for (X509Certificate cert : clientCerts) {
            certArray[n++] = cert;
        }
        mTempKeys.put(OSUCertType.Client, certArray);
        mTempKeys.put(OSUCertType.PrivateKey, key);
    }

    private static Map<OSUCertType, String> populateAliases(Map<String, String> configFields)
            throws IOException {

        Map<OSUCertType, String> aliasMap = new HashMap<>();
        for (Map.Entry<String, OSUCertType> entry : sConfigMapping.entrySet()) {
            String alias = getAlias(entry.getKey(), configFields);
            if (alias != null) {
                aliasMap.put(entry.getValue(), alias);
            }
        }
        return aliasMap;
    }

    private static String getAlias(String key, Map<String, String> configFields) throws IOException {
        String alias = configFields.get(key);
        if (alias == null) {
            return null;
        }
        if (alias.endsWith("\"")) {
            alias = alias.substring(0, alias.length() - 1);
        }
        int aliasStart = alias.indexOf('_');
        return aliasStart < 0 ? alias : alias.substring(aliasStart + 1);
    }

    private static void dumpKeyStore(KeyStore keyStore, Map<OSUCertType, String> aliasMap) {
        for (Map.Entry<OSUCertType, String> entry : aliasMap.entrySet()) {
            try {
                switch (entry.getKey()) {
                    case Client:
                        Certificate[] certs = keyStore.getCertificateChain(entry.getValue());
                        for (Certificate cert : certs) {
                            Log.d("ZXZ", "Cert " + cert);
                        }
                        break;
                    case PrivateKey:
                        Key key = keyStore.getKey(entry.getValue(), null);
                        Log.d("ZXZ", "Key: " + key);
                        break;
                    case CA:
                        Certificate cert = keyStore.getCertificate(entry.getValue());
                        Log.d("ZXZ", "CA Cert " + cert);
                        break;
                }
            }
            catch (GeneralSecurityException gse) {
                Log.w("ZXZ", "Caught exception: " + gse);
            }
        }
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        if (mTempKeys.isEmpty()) {
            return mAliasMap.get(OSUCertType.Client);
        }
        else {
            return sTempAlias;
        }
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        if (mTempKeys.isEmpty()) {
            String alias = mAliasMap.get(OSUCertType.Client);
            return alias != null ? new String[]{alias} : null;
        }
        else {
            return new String[] {sTempAlias};
        }
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        if (mTempKeys.isEmpty()) {
            if (!mAliasMap.get(OSUCertType.Client).equals(alias)) {
                Log.w(OSUManager.TAG, "Bad cert alias requested: '" + alias + "'");
                return null;
            }
            try {
                List<X509Certificate> certs = new ArrayList<>();
                for (Certificate certificate :
                        mKeyStore.getCertificateChain(mAliasMap.get(OSUCertType.Client))) {
                    if (certificate instanceof X509Certificate) {
                        certs.add((X509Certificate) certificate);
                    }
                }
                return certs.toArray(new X509Certificate[certs.size()]);
            } catch (KeyStoreException kse) {
                Log.w(OSUManager.TAG, "Failed to retrieve certificates: " + kse);
                return null;
            }
        }
        else if (sTempAlias.equals(alias)) {
            return (X509Certificate[]) mTempKeys.get(OSUCertType.Client);
        }
        else {
            Log.w(OSUManager.TAG, "Bad cert alias requested: '" + alias + "'");
            return null;
        }
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        if (mTempKeys.isEmpty()) {
            if (!mAliasMap.get(OSUCertType.Client).equals(alias)) {
                Log.w(OSUManager.TAG, "Bad key alias requested: '" + alias + "'");
            }
            try {
                return (PrivateKey) mKeyStore.getKey(mAliasMap.get(OSUCertType.PrivateKey), null);
            } catch (GeneralSecurityException gse) {
                Log.w(OSUManager.TAG, "Failed to retrieve private key: " + gse);
                return null;
            }
        }
        else if (sTempAlias.equals(alias)) {
            return (PrivateKey) mTempKeys.get(OSUCertType.PrivateKey);
        }
        else {
            Log.w(OSUManager.TAG, "Bad cert alias requested: '" + alias + "'");
            return null;
        }
    }
}
