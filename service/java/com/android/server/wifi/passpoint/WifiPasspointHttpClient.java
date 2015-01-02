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
import java.net.HttpURLConnection;

import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class WifiPasspointHttpClient {
    // STOPSHIP: Have this investigated by the security team. Why are we
    // turning off hostname verification ?
    private static final HostnameVerifier ALLOW_ALL_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    // Default connection and socket timeout of 60 seconds. Tweak to taste.
    private static final int SOCKET_OPERATION_TIMEOUT = 30 * 1000;

    private static final String TAG = "WifiPasspointHttpClient";

    private SSLSocketFactory mSocketFactory;

    public WifiPasspointHttpClient(KeyStore clientCertStore, char[] passwd) {
        try {
            mSocketFactory = getSocketFactory(clientCertStore, passwd);
        } catch (Exception e) {
            Log.e(TAG, "Exception initializing client", e);
            mSocketFactory = null;
        }
    }

    public void configureURLConnection(HttpURLConnection connection) {
        connection.addRequestProperty("User-Agent", "ksoap2-android/2.6.0+"); // tempoarirly
        connection.setInstanceFollowRedirects(false);  // don't follow redirects.
        connection.setConnectTimeout(SOCKET_OPERATION_TIMEOUT);
        connection.setReadTimeout(SOCKET_OPERATION_TIMEOUT);

        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
            httpsConnection.setHostnameVerifier(ALLOW_ALL_HOSTNAME_VERIFIER);
            httpsConnection.setSSLSocketFactory(mSocketFactory);
        }
    }

    private static SSLSocketFactory getSocketFactory(KeyStore truststore, char[] password)
            throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException,
            KeyManagementException {

        // TODO: This needs a security security review.
        final TrustManager tm = new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            @Override
            public void checkClientTrusted(
                    java.security.cert.X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
            }

            @Override
            public void checkServerTrusted(
                    java.security.cert.X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");

        // TODO: enable OCSP stapling and use {@code truststore} and {@code password)
        //
        // if (truststore != null) {
        //   KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        //   kmf.init(truststore, password);
        //   KeyManager[] keyManagers = kmf.getKeyManagers();
        //   sslContext.init(keyManagers, new TrustManager[] { tm }, null, true);
        // }

        sslContext.init(null, new TrustManager[] { tm }, null);
        return sslContext.getSocketFactory();
    }
}
