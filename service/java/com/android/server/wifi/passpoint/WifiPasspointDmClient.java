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

import com.android.server.wifi.passpoint.WifiPasspointClient.AuthenticationElement;

/**
 * TODO: doc
 */
public class WifiPasspointDmClient implements WifiPasspointClient.DmClient {
    private StateMachine mTarget;

    @Override
    public void init(StateMachine target) {
        mTarget = target;
    }

    @Override
    public void startSubscriptionProvision(String serverUrl) {
    }

    @Override
    public void startRemediation(String serverUrl, WifiPasspointCredential cred) {
    }

    @Override
    public void startPolicyProvision(String serverUrl, WifiPasspointCredential cred) {
    }

    @Override
    public void setWifiTree(WifiPasspointDmTree tree) {
    }

    @Override
    public WifiPasspointDmTree getWifiTree() {
        WifiPasspointDmTree tree = null;
        //TODO: get all MOs from DM engine and translate to WifiTree
        return tree;
    }

    @Override
    public void notifyBrowserRedirected() {
    }

    @Override
    public void setBrowserRedirectUri(String uri) {
    }

    @Override
    public void setAuthenticationElement(AuthenticationElement ae) {
    }

    @Override
    public int injectSoapPackage(String path, String command, String pacckage) {
        //TODO: return error code if any
        return 0;
    }
}
