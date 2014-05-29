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

import com.android.internal.util.StateMachine;

/**
 * TODO: doc
 */
public class WifiPasspointClient {

    public interface BaseClient {
        /**
         * init client parameters
         */
        public void init(StateMachine target);

        /**
         * Start to Online Sign-Up procedure
         */
        public void startSubscriptionProvision(String serverUri);

        /**
         * Start to remediation procedure
         */
        public void startRemediation(String serverUri, WifiPasspointCredential cred);

        /**
         * Start to policy update procedure
         */
        public void startPolicyProvision(String serverUri, WifiPasspointCredential cred);

        /**
         * set WifiTree
         */
        public void setWifiTree(WifiPasspointDmTree tree);

        /**
         * notify client the browser is redirected
         */
        public void setBrowserRedirected();

        /**
         * set browser redirect URI
         */
        public void setBrowserRedirectUri(String uri);

        /**
         * set authentication elements for SSL connection to check server certificate
         */
        public void setAuthenticationElement(AuthenticationElement ae);
    }

    public interface DmClient extends BaseClient {
        public WifiPasspointDmTree getWifiTree();

        public int injectSoapPackage(String path, String command, String pacckage);
    }

    public interface SoapClient extends BaseClient {
    }

    /**
     * Authentication elements for check server certificate in SSL connection
     */
    public static class AuthenticationElement {
        public String spFqdn;
        public String osuFriendlyName;
        public String osuDefaultLanguage;
        public String osuIconfileName;

        public AuthenticationElement(String fqdn, String fn, String dl, String ifn) {
            spFqdn = fqdn;
            osuFriendlyName = fn;
            osuDefaultLanguage = dl;
            osuIconfileName = ifn;
        }
    }
}
