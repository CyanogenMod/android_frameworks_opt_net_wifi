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

import android.net.wifi.passpoint.WifiPasspointDmTree;
import android.util.Log;

import java.util.Iterator;
import java.util.Collection;
import java.util.Set;

/**
 * TODO: doc
 */
public class WifiPasspointDmTreeHelper {
    private static final String TAG = "WifiTreeHelper";
    private Object mGlobalSync = new Object();

    public WifiPasspointDmTreeHelper() {

    }

    public void configToPpsMoNumber(int id, WifiPasspointDmTree tree) {
        WifiPasspointDmTree.SpFqdn currentSpfqdn = tree.createSpFqdn("wi-fi.org");
        tree.PpsMoId = id;
        WifiPasspointDmTree.AAAServerTrustRoot aaa = null;
        WifiPasspointDmTree.SPExclusionList spexclusion = null;
        WifiPasspointDmTree.CredentialInfo info = currentSpfqdn.perProviderSubscription
                .createCredentialInfo("Cred01");

        switch (id) {
            case 1:
            case 2:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test01";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                if (id == 2) {
                    currentSpfqdn.perProviderSubscription.UpdateIdentifier = "2";
                    info.credential.usernamePassword.Username = "test02";
                    info.credential.usernamePassword.MachineManaged = false;
                }
                break;

            case 3:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "3";
                info.policy.createPreferredRoamingPartnerList("PRP01",
                        "ruckuswireless.com,exactMatch", "1", "US");
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test03";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 4:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "4";
                aaa = info.createAAAServerTrustRoot("STR01", "xxx", "xxx");
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test04";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 5:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "5";
                info.policy.policyUpdate.UpdateInterval = "0xFFFFFFFF";
                info.policy.policyUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                spexclusion = info.policy.createSPExclusionList("SPE01", "Hotspot 2.0");
                info.credentialPriority = "1";
                aaa = info.createAAAServerTrustRoot("STR01", "xxx", "xxx");
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test05";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 6:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "6";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Example";
                info.homeSP.FQDN = "example.com";
                info.homeSP.createHomeOIList("HOI01", "001BC504BE", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test06";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "example.com";
                break;

            case 7:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "7";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.homeSP.createHomeOIList("HOI01", "001BC504BE", true);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test07";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 8:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "8";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc08";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test08";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 9:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "9";
                info.policy.createPreferredRoamingPartnerList("PRP01",
                        "ruckuswireless.com,exactMatch", "1", "US");
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.usernamePassword.Username = "testdmacc08";
                info.policy.policyUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test09";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 10:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "10";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.IconURL = "TBD";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.digitalCertificate.CertificateType = "x509v3";
                info.credential.digitalCertificate.CertSHA256Fingerprint = "TBD";
                info.credential.Realm = "wi-fi.org";
                break;

            case 11:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "11";
                info.policy.createPreferredRoamingPartnerList("PRP01",
                        "ruckuswireless.com,includeSubdomains", "1", "US");
                info.policy.policyUpdate.UpdateInterval = "0xFFFFFFFF";
                info.policy.policyUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Example";
                info.homeSP.FQDN = "example.com";
                info.homeSP.createHomeOIList("HOI01", "001BC504BE", false);
                info.homeSP.createOtherHomePartners("OHP01", "bt.com");
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test11";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "mail.example.com";
                break;

            case 12:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "12";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "TBD";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.digitalCertificate.CertificateType = "x509v3";
                info.credential.Realm = "wi-fi.org";
                break;

            case 13:
            case 14:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "13";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test13";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                if (id == 14) {
                    currentSpfqdn.perProviderSubscription.UpdateIdentifier = "14";
                    info.credential.usernamePassword.Username = "test14";
                    info.credential.usernamePassword.MachineManaged = false;
                }
                break;

            case 15:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "15";
                info.policy.createPreferredRoamingPartnerList("PRP01",
                        "ruckuswireless.com,exactMatch", "1", "US");
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("HOI01", "506F9A", false);
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test15";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 16:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "16";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test16";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 17:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "17";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.digitalCertificate.CertificateType = "x509v3";
                info.credential.Realm = "wi-fi.org";
                break;

            case 18:
                //Note: this PPS MO is no longer needed, but the ID is reserved so that the testplan does not need renumbering.
                break;

            case 19:
                //Note: this PPS MO is no longer needed, but the ID is reserved so that the testplan does not need renumbering.
                break;

            case 20:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "20";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test20";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 21:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "21";
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.usernamePassword.Username = "testdmacc21";
                info.policy.policyUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test21";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 22:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "22";
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.usernamePassword.Username = "testdmacc22";
                info.policy.policyUpdate.usernamePassword.Password = "P@ssw0rd";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Example dot com";
                info.homeSP.FQDN = "example.com";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test22";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "example.com";
                break;

            case 23:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "23";
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.usernamePassword.Username = "testdmacc23";
                info.policy.policyUpdate.usernamePassword.Password = "P@ssw0rd";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test23";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 24:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "24";
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.usernamePassword.Username = "testdmacc24";
                info.policy.policyUpdate.usernamePassword.Password = "P@ssw0rd";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test24";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 25:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "25";
                info.policy.policyUpdate.UpdateInterval = "0xA";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "Unrestricted";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.usernamePassword.Username = "testdmacc25";
                info.policy.policyUpdate.usernamePassword.Password = "P@ssw0rd";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.FQDN = "wi-fi.org";
                info.credentialPriority = "1";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test25";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 26:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "26";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test26";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";

                WifiPasspointDmTree.CredentialInfo info2 = currentSpfqdn.perProviderSubscription
                        .createCredentialInfo("Cred02");
                info2.credentialPriority = "2";
                info2.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info2.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info2.subscriptionUpdate.Restriction = "HomeSp";
                info2.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info2.homeSP.FriendlyName = "Wi-Fi Alliance";
                info2.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info2.homeSP.FQDN = "wi-fi.org";
                info2.credential.CreationDate = "20121201T12:00:00Z";
                info2.credential.sim.IMSI = "234564085551515";
                info2.credential.sim.EAPType = "18";
                info2.credential.Realm = "wlan.mnc56.mcc234.3gppnetwork.org";
                break;

            case 27:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "27";
                info.policy.createPreferredRoamingPartnerList("Prp01", "ericsson.com,exactMatch",
                        "1", "US");
                info.policy.createPreferredRoamingPartnerList("Prp02", "example.com,exactMatch",
                        "2", "US");
                info.policy.createPreferredRoamingPartnerList("Prp03", "example2.com,exactMatch",
                        "3", "US");
                info.policy.createMinBackhaulThreshold("Mbt01", "Home", "3000", "500");
                info.policy.createMinBackhaulThreshold("Mbt02", "Roaming", "3000", "500");
                info.policy.policyUpdate.UpdateInterval = "0xFFFFFFFF";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "HomeSP";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.trustRoot.CertURL = "TBD";
                info.policy.policyUpdate.trustRoot.CertSHA256Fingerprint = "TBD";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test27";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 28:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "28";
                info.policy.createPreferredRoamingPartnerList("Prp01", "ericsson.com,exactMatch",
                        "1", "US");
                info.policy.createPreferredRoamingPartnerList("Prp02", "example.com,exactMatch",
                        "2", "US");
                info.policy.createPreferredRoamingPartnerList("Prp03", "example2.com,exactMatch",
                        "3", "US");
                info.policy.createRequiredProtoPortTuple("Ppt01", "6", "5060");
                info.policy.createRequiredProtoPortTuple("Ppt02", "17", "5060");
                info.policy.policyUpdate.UpdateInterval = "0xFFFFFFFF";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "HomeSP";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.policy.policyUpdate.trustRoot.CertURL = "TBD";
                info.policy.policyUpdate.trustRoot.CertSHA256Fingerprint = "TBD";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc28";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test28";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 29:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "29";
                info.policy.maximumBSSLoadValue = "100";
                info.policy.policyUpdate.UpdateInterval = "0xFFFFFFFF";
                info.policy.policyUpdate.UpdateMethod = "OMA-DM-ClientInitiated";
                info.policy.policyUpdate.Restriction = "HomeSP";
                info.policy.policyUpdate.URI = "https://policy-server.r2-testbed.wi-fi.org";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc29";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("Home01", "506F9A", false);
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test29";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 30:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "30";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc30";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("Home01", "506F9A", false);
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test30";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 31:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "31";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc31";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("Home01", "506F9A", false);
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test31";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 32:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "32";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc32";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("Home01", "506F9A", false);
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test32";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 33:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "33";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc33";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("Home01", "506F9A", false);
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test33";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 34:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "34";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc34";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.homeSP.createHomeOIList("Home01", "506F9A", false);
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test34";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            case 35:
                currentSpfqdn.perProviderSubscription.UpdateIdentifier = "35";
                info.credentialPriority = "1";
                info.subscriptionUpdate.UpdateInterval = "0xFFFFFFFF";
                info.subscriptionUpdate.UpdateMethod = "SPP-ClientInitiated";
                info.subscriptionUpdate.Restriction = "HomeSp";
                info.subscriptionUpdate.URI = "https://subscription-server.r2-testbed.wi-fi.org";
                info.subscriptionUpdate.usernamePassword.Username = "testdmacc35";
                info.subscriptionUpdate.usernamePassword.Password = "P@ssw0rd";
                info.homeSP.FriendlyName = "Wi-Fi Alliance";
                info.homeSP.IconURL = "http://www.wi-fi.org/sites/default/files/uploads/wfa_certified_3d_web_lr.png";
                info.homeSP.FQDN = "wi-fi.org";
                info.credential.CreationDate = "20121201T12:00:00Z";
                info.credential.usernamePassword.Username = "test35";
                info.credential.usernamePassword.Password = "ChangeMe";
                info.credential.usernamePassword.MachineManaged = true;
                info.credential.usernamePassword.eAPMethod.EAPType = "21";
                info.credential.usernamePassword.eAPMethod.InnerMethod = "29";
                info.credential.Realm = "wi-fi.org";
                break;

            default:
                Log.d(TAG, "configToPpsMoNumber unknow id:" + id);
                break;
        }
    }

    public void dumpTreeInfo(WifiPasspointDmTree tree) {

        synchronized (mGlobalSync) {

            Log.d(TAG, "===== dumpTreeInfo =====");
            Set spfqdnSet = tree.spFqdn.entrySet();
            Iterator spfqdnItr = spfqdnSet.iterator();

            for (WifiPasspointDmTree.SpFqdn sp : getSp(tree)) {
                Log.d(TAG, "<X>+service provider <" + sp.nodeName + ">");
                Log.d(TAG, "    sp.perProviderSubscription.UpdateIdentifier <"
                        + sp.perProviderSubscription.UpdateIdentifier + ">");

                for (WifiPasspointDmTree.CredentialInfo info : getCredentialInfo(sp)) {
                    Log.d(TAG, "    <X>+credential info <" + info.nodeName + ">");
                    Log.d(TAG, "        credentialPriority <" + info.credentialPriority + ">");
                    Log.d(TAG, "        ---- ----");
                    //homeSp
                    Log.d(TAG, "        homeSP.FQDN <" + info.homeSP.FQDN + ">");
                    Log.d(TAG, "        homeSP.FriendlyName <" + info.homeSP.FriendlyName + ">");

                    for (WifiPasspointDmTree.HomeOIList homeoi : getHomeOIList(info)) {
                        Log.d(TAG, "        <X>+homeSP.HomeOIList <" + homeoi.nodeName + ">");
                        Log.d(TAG, "            HomeOI <" + homeoi.HomeOI + ">");
                        Log.d(TAG, "            HomeOIRequired <" + homeoi.HomeOIRequired + ">");
                    }

                    for (WifiPasspointDmTree.OtherHomePartners otherHP : getOtherHomePartner(info)) {
                        Log.d(TAG, "        <X>+homeSP.OtherHomePartners <" + otherHP.nodeName
                                + ">");
                        Log.d(TAG, "            FQDN <" + otherHP.FQDN + ">");
                    }
                    Log.d(TAG, "        ---- ----");
                    //credential
                    Log.d(TAG, "        credential.Realm <" + info.credential.Realm + ">");
                    Log.d(TAG, "        credential.CheckAAAServerCertStatus <"
                            + info.credential.CheckAAAServerCertStatus + ">");
                    Log.d(TAG, "        credential.usernamePassword.eAPMethod.EAPType <" +
                            info.credential.usernamePassword.eAPMethod.EAPType + ">");
                    Log.d(TAG, "        credential.usernamePassword.eAPMethod.InnerMethod <" +
                            info.credential.usernamePassword.eAPMethod.InnerMethod + ">");
                    Log.d(TAG, "        credential.usernamePassword.Username <"
                            + info.credential.usernamePassword.Username + ">");
                    Log.d(TAG, "        credential.usernamePassword.Password <"
                            + info.credential.usernamePassword.Password + ">");
                    Log.d(TAG, "        credential.usernamePassword.MachineManaged <"
                            + info.credential.usernamePassword.MachineManaged + ">");
                    Log.d(TAG, "        credential.digitalCertificate.CertificateType <"
                            + info.credential.digitalCertificate.CertificateType + ">");
                    Log.d(TAG, "        credential.digitalCertificate.CertSHA256Fingerprint <"
                            + info.credential.digitalCertificate.CertSHA256Fingerprint + ">");
                    Log.d(TAG, "        credential.sim.IMSI <" + info.credential.sim.IMSI + ">");
                    Log.d(TAG, "        credential.sim.EAPType <" + info.credential.sim.EAPType
                            + ">");
                    Log.d(TAG, "        ---- ----");
                    //subscriptionUpdate
                    Log.d(TAG, "        subscriptionUpdate.UpdateInterval <"
                            + info.subscriptionUpdate.UpdateInterval + ">");
                    Log.d(TAG, "        subscriptionUpdate.UpdateMethod <"
                            + info.subscriptionUpdate.UpdateMethod + ">");
                    Log.d(TAG, "        subscriptionUpdate.Restriction <"
                            + info.subscriptionUpdate.Restriction + ">");
                    Log.d(TAG, "        subscriptionUpdate.URI <" + info.subscriptionUpdate.URI
                            + ">");
                    Log.d(TAG, "        subscriptionUpdate.usernamePassword.Username <"
                            + info.subscriptionUpdate.usernamePassword.Username + ">");
                    Log.d(TAG, "        subscriptionUpdate.usernamePassword.Password <"
                            + info.subscriptionUpdate.usernamePassword.Password + ">");
                    Log.d(TAG, "        ---- ----");
                    //policy
                    Log.d(TAG, "        policy.policyUpdate.UpdateInterval <"
                            + info.policy.policyUpdate.UpdateInterval + ">");
                    Log.d(TAG, "        policy.policyUpdate.UpdateMethod <"
                            + info.policy.policyUpdate.UpdateMethod + ">");
                    Log.d(TAG, "        policy.policyUpdate.Restriction <"
                            + info.policy.policyUpdate.Restriction + ">");
                    Log.d(TAG, "        policy.policyUpdate.URI <" + info.policy.policyUpdate.URI
                            + ">");
                    Log.d(TAG, "        policy.policyUpdate.usernamePassword.Username <"
                            + info.policy.policyUpdate.usernamePassword.Username + ">");
                    Log.d(TAG, "        policy.policyUpdate.usernamePassword.Password <"
                            + info.policy.policyUpdate.usernamePassword.Password + ">");
                    Log.d(TAG, "        policy.maximumBSSLoadValue <"
                            + info.policy.maximumBSSLoadValue + ">");

                    for (WifiPasspointDmTree.PreferredRoamingPartnerList prp : getPreferredRoamingPartnerList(info)) {
                        Log.d(TAG, "        ---- ----");
                        Log.d(TAG, "        <X>+policy.PreferredRoamingPartnerList <"
                                + prp.nodeName + ">");
                        Log.d(TAG, "            FQDN_Match <" + prp.FQDN_Match + ">");
                        Log.d(TAG, "            Priority <" + prp.Priority + ">");
                        Log.d(TAG, "            Country <" + prp.Country + ">");
                    }
                    Set minBackSet = info.policy.minBackhaulThreshold.entrySet();
                    Iterator minBackItr = minBackSet.iterator();
                    for (WifiPasspointDmTree.MinBackhaulThresholdNetwork minBT : getMinBackhaulThreshold(info)) {
                        Log.d(TAG, "        ---- ----");
                        Log.d(TAG, "        <X>+policy.MinBackhaulThresholdNetwork <"
                                + minBT.nodeName + ">");
                        Log.d(TAG, "            NetworkType <" + minBT.NetworkType + ">");
                        Log.d(TAG, "            DLBandwidth <" + minBT.DLBandwidth + ">");
                        Log.d(TAG, "            ULBandwidth <" + minBT.ULBandwidth + ">");
                    }

                    for (WifiPasspointDmTree.RequiredProtoPortTuple protoPort : getRequiredProtoPortTuple(info)) {
                        Log.d(TAG, "        ---- ----");
                        Log.d(TAG, "        <X>+policy.RequiredProtoPortTuple <"
                                + protoPort.nodeName + ">");
                        Log.d(TAG, "            IPProtocol <" + protoPort.IPProtocol + ">");
                        Log.d(TAG, "            PortNumber <" + protoPort.PortNumber + ">");
                    }

                    for (WifiPasspointDmTree.SPExclusionList spExc : getSPExclusionList(info)) {
                        Log.d(TAG, "        ---- ----");
                        Log.d(TAG, "        <X>+policy.SPExclusionList <" + spExc.nodeName + ">");
                        Log.d(TAG, "            SSID <" + spExc.SSID + ">");
                    }

                    for (WifiPasspointDmTree.AAAServerTrustRoot aaaTrustRoot : getAaaServerTrustRoot(info)) {
                        Log.d(TAG, "        ---- ----");
                        Log.d(TAG, "        <X>+AAAServerTrustRoot <" + aaaTrustRoot.nodeName + ">");
                        Log.d(TAG, "            CertURL <" + aaaTrustRoot.CertURL + ">");
                        Log.d(TAG, "            CertSHA256Fingerprint <"
                                + aaaTrustRoot.CertSHA256Fingerprint + ">");
                    }
                }
            }
            Log.d(TAG, "===== End =====");
        }
    }

    public Collection<WifiPasspointDmTree.CredentialInfo> getCredentialInfo(WifiPasspointDmTree.SpFqdn sp) {
        try {
            return sp.perProviderSubscription.credentialInfo.values();
        } catch (Exception e) {
            Log.d(TAG, "getCredentialInfo err:" + e);
        }
        return null;
    }

    public WifiPasspointDmTree.CredentialInfo getCredentialInfo(WifiPasspointDmTree tree, String spnodename,
            String crednetialnodename) {
        try {
            for (String skey : tree.spFqdn.keySet()) {
                if (spnodename.equals(skey)) {
                    WifiPasspointDmTree.SpFqdn sp = tree.spFqdn.get(skey);
                    for (String ckey : sp.perProviderSubscription.credentialInfo.keySet()) {
                        if (crednetialnodename.equals(ckey)) {
                            return sp.perProviderSubscription.credentialInfo.get(ckey);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "getCredentialInfo err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.SpFqdn> getSp(WifiPasspointDmTree tree) {
        try {
            return tree.spFqdn.values();
        } catch (Exception e) {
            Log.d(TAG, "getSp err:" + e);
        }
        return null;
    }

    public WifiPasspointDmTree.SpFqdn getSp(WifiPasspointDmTree tree, String spnodename) {
        try {
            for (String key : tree.spFqdn.keySet()) {
                if (spnodename.equals(key)) {
                    return tree.spFqdn.get(key);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "getSp err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.HomeOIList> getHomeOIList(WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.homeSP.homeOIList.values();
        } catch (Exception e) {
            Log.d(TAG, "getHomeOiList err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.OtherHomePartners> getOtherHomePartner(WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.homeSP.otherHomePartners.values();
        } catch (Exception e) {
            Log.d(TAG, "getOtherHomePartner err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.AAAServerTrustRoot> getAaaServerTrustRoot(
            WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.aAAServerTrustRoot.values();
        } catch (Exception e) {
            Log.d(TAG, "getAaaServerTrustRoot err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.PreferredRoamingPartnerList> getPreferredRoamingPartnerList(
            WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.policy.preferredRoamingPartnerList.values();
        } catch (Exception e) {
            Log.d(TAG, "getPreferredRoamingPartnerList err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.SPExclusionList> getSPExclusionList(WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.policy.sPExclusionList.values();
        } catch (Exception e) {
            Log.d(TAG, "getSPExclusionList err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.MinBackhaulThresholdNetwork> getMinBackhaulThreshold(
            WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.policy.minBackhaulThreshold.values();
        } catch (Exception e) {
            Log.d(TAG, "getMinBackhaulThreshold err:" + e);
        }
        return null;
    }

    public Collection<WifiPasspointDmTree.RequiredProtoPortTuple> getRequiredProtoPortTuple(
            WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.policy.requiredProtoPortTuple.values();
        } catch (Exception e) {
            Log.d(TAG, "getRequiredProtoPortTuple err:" + e);
        }
        return null;
    }

    public String getMaximumBSSLoadValue(WifiPasspointDmTree.CredentialInfo info) {
        try {
            return info.policy.maximumBSSLoadValue;
        } catch (Exception e) {
            Log.d(TAG, "getMaximumBSSLoadValue err:" + e);
        }
        return null;
    }

}
