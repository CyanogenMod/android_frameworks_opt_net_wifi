/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.security.Credentials;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.security.cert.X509Certificate;


/**
 * Unit tests for {@link android.net.wifi.WifiEnterpriseConfig}.
 */
@SmallTest
public class WifiEnterpriseConfigTest {
    // Maintain a ground truth of the keystore uri prefix which is expected by wpa_supplicant.
    public static final String KEYSTORE_URI = "keystore://";
    public static final String CA_CERT_PREFIX = KEYSTORE_URI + Credentials.CA_CERTIFICATE;
    public static final String KEYSTORES_URI = "keystores://";

    private WifiEnterpriseConfig mEnterpriseConfig;

    @Before
    public void setUp() throws Exception {
        mEnterpriseConfig = new WifiEnterpriseConfig();
    }

    @Test
    public void testSetGetSingleCaCertificate() {
        X509Certificate cert0 = FakeKeys.CA_CERT0;
        mEnterpriseConfig.setCaCertificate(cert0);
        assertEquals(mEnterpriseConfig.getCaCertificate(), cert0);
    }

    @Test
    public void testSetGetMultipleCaCertificates() {
        X509Certificate cert0 = FakeKeys.CA_CERT0;
        X509Certificate cert1 = FakeKeys.CA_CERT1;
        mEnterpriseConfig.setCaCertificates(new X509Certificate[] {cert0, cert1});
        X509Certificate[] result = mEnterpriseConfig.getCaCertificates();
        assertEquals(result.length, 2);
        assertTrue(result[0] == cert0 && result[1] == cert1);
    }

    @Test
    public void testSaveSingleCaCertificateAlias() {
        final String alias = "single_alias 0";
        mEnterpriseConfig.setCaCertificateAliases(new String[] {alias});
        assertEquals(getCaCertField(), CA_CERT_PREFIX + alias);
    }

    @Test
    public void testLoadSingleCaCertificateAlias() {
        final String alias = "single_alias 1";
        setCaCertField(CA_CERT_PREFIX + alias);
        String[] aliases = mEnterpriseConfig.getCaCertificateAliases();
        assertEquals(aliases.length, 1);
        assertEquals(aliases[0], alias);
    }

    @Test
    public void testSaveMultipleCaCertificates() {
        final String alias0 = "single_alias 0";
        final String alias1 = "single_alias 1";
        mEnterpriseConfig.setCaCertificateAliases(new String[] {alias0, alias1});
        assertEquals(getCaCertField(), String.format("%s%s %s",
                KEYSTORES_URI,
                WifiEnterpriseConfig.encodeCaCertificateAlias(Credentials.CA_CERTIFICATE + alias0),
                WifiEnterpriseConfig.encodeCaCertificateAlias(Credentials.CA_CERTIFICATE + alias1)));
    }

    @Test
    public void testLoadMultipleCaCertificates() {
        final String alias0 = "single_alias 0";
        final String alias1 = "single_alias 1";
        setCaCertField(String.format("%s%s %s",
                KEYSTORES_URI,
                WifiEnterpriseConfig.encodeCaCertificateAlias(Credentials.CA_CERTIFICATE + alias0),
                WifiEnterpriseConfig.encodeCaCertificateAlias(Credentials.CA_CERTIFICATE + alias1)));
        String[] aliases = mEnterpriseConfig.getCaCertificateAliases();
        assertEquals(aliases.length, 2);
        assertEquals(aliases[0], alias0);
        assertEquals(aliases[1], alias1);
    }

    private String getCaCertField() {
        return mEnterpriseConfig.getFieldValue(WifiEnterpriseConfig.CA_CERT_KEY, "");
    }

    private void setCaCertField(String value) {
        mEnterpriseConfig.setFieldValue(WifiEnterpriseConfig.CA_CERT_KEY, value);
    }
}
