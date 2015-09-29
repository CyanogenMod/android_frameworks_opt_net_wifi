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
 * limitations under the License.
 */

package com.android.server.wifi.nan;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;

import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.PublishData;
import android.net.wifi.nan.PublishSettings;
import android.net.wifi.nan.SubscribeData;
import android.net.wifi.nan.SubscribeSettings;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.ExpectedException;

/**
 * Unit test harness for WifiNanManager class.
 */
@SmallTest
public class WifiNanManagerTest {
    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    /*
     * ConfigRequest Tests
     */

    @Test
    public void testConfigRequestBuilder() {
        final int clusterHigh = 100;
        final int clusterLow = 5;
        final int masterPreference = 55;
        final boolean supportBand5g = true;

        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setClusterHigh(clusterHigh);
        builder.setClusterLow(clusterLow);
        builder.setMasterPreference(masterPreference);
        builder.setSupport5gBand(supportBand5g);
        ConfigRequest configRequest = builder.build();

        collector.checkThat("mClusterHigh", clusterHigh, equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", clusterLow, equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", masterPreference,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", supportBand5g, equalTo(configRequest.mSupport5gBand));
    }

    @Test
    public void testConfigRequestBuilderMasterPrefNegative() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(-1);
    }

    @Test
    public void testConfigRequestBuilderMasterPrefReserved1() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(1);
    }

    @Test
    public void testConfigRequestBuilderMasterPrefReserved255() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(255);
    }

    @Test
    public void testConfigRequestBuilderMasterPrefTooLarge() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(256);
    }

    @Test
    public void testConfigRequestBuilderClusterLowNegative() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setClusterLow(-1);
    }

    @Test
    public void testConfigRequestBuilderClusterHighNegative() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setClusterHigh(-1);
    }

    @Test
    public void testConfigRequestBuilderClusterLowAboveMax() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setClusterLow(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test
    public void testConfigRequestBuilderClusterHighAboveMax() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setClusterHigh(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test
    public void testConfigRequestBuilderClusterLowLargerThanHigh() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();

        builder.setClusterLow(100);
        builder.setClusterHigh(5);
        ConfigRequest configRequest = builder.build();
    }

    @Test
    public void testConfigRequestParcel() {
        final int clusterHigh = 189;
        final int clusterLow = 25;
        final int masterPreference = 177;
        final boolean supportBand5g = true;

        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setClusterHigh(clusterHigh);
        builder.setClusterLow(clusterLow);
        builder.setMasterPreference(masterPreference);
        builder.setSupport5gBand(supportBand5g);
        ConfigRequest configRequest = builder.build();

        Parcel parcelW = Parcel.obtain();
        configRequest.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        ConfigRequest rereadConfigRequest = ConfigRequest.CREATOR.createFromParcel(parcelR);

        assertEquals(configRequest, rereadConfigRequest);
    }

    /*
     * SubscribeData Tests
     */

    @Test
    public void testSubscribeDataBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };

        SubscribeData.Builder builder = new SubscribeData.Builder();
        builder.setServiceName(serviceName);
        builder.setServiceSpecificInfo(serviceSpecificInfo);
        builder.setTxFilter(txFilter, txFilter.length);
        builder.setRxFilter(rxFilter, rxFilter.length);
        SubscribeData subscribeData = builder.build();

        collector.checkThat("mServiceName", serviceName, equalTo(subscribeData.mServiceName));
        String mServiceSpecificInfo = new String(subscribeData.mServiceSpecificInfo, 0,
                subscribeData.mServiceSpecificInfoLength);
        collector.checkThat("mServiceSpecificInfo",
                utilAreArraysEqual(serviceSpecificInfo.getBytes(), serviceSpecificInfo.length(),
                        subscribeData.mServiceSpecificInfo,
                        subscribeData.mServiceSpecificInfoLength),
                equalTo(true));
        collector.checkThat("mTxFilter", utilAreArraysEqual(txFilter, txFilter.length,
                subscribeData.mTxFilter, subscribeData.mTxFilterLength), equalTo(true));
        collector.checkThat("mRxFilter", utilAreArraysEqual(rxFilter, rxFilter.length,
                subscribeData.mRxFilter, subscribeData.mRxFilterLength), equalTo(true));
    }

    @Test
    public void testSubscribeDataParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };

        SubscribeData.Builder builder = new SubscribeData.Builder();
        builder.setServiceName(serviceName);
        builder.setServiceSpecificInfo(serviceSpecificInfo);
        builder.setTxFilter(txFilter, txFilter.length);
        builder.setTxFilter(rxFilter, rxFilter.length);
        SubscribeData subscribeData = builder.build();

        Parcel parcelW = Parcel.obtain();
        subscribeData.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        SubscribeData rereadSubscribeData = SubscribeData.CREATOR.createFromParcel(parcelR);

        assertEquals(subscribeData, rereadSubscribeData);
    }

    /*
     * SubscribeSettings Tests
     */

    @Test
    public void testSubscribeSettingsBuilder() {
        final int subscribeType = SubscribeSettings.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeCount = 10;
        final int subscribeTtl = 15;

        SubscribeSettings.Builder builder = new SubscribeSettings.Builder();
        builder.setSubscribeType(subscribeType);
        builder.setSubscribeCount(subscribeCount);
        builder.setTtlSec(subscribeTtl);
        SubscribeSettings subscribeSetting = builder.build();

        collector.checkThat("mSubscribeType", subscribeType,
                equalTo(subscribeSetting.mSubscribeType));
        collector.checkThat("mSubscribeCount", subscribeCount,
                equalTo(subscribeSetting.mSubscribeCount));
        collector.checkThat("mTtlSec", subscribeTtl, equalTo(subscribeSetting.mTtlSec));
    }

    @Test
    public void testSubscribeSettingsBuilderBadSubscribeType() {
        thrown.expect(IllegalArgumentException.class);
        SubscribeSettings.Builder builder = new SubscribeSettings.Builder();
        builder.setSubscribeType(10);
    }

    @Test
    public void testSubscribeSettingsBuilderNegativeCount() {
        thrown.expect(IllegalArgumentException.class);
        SubscribeSettings.Builder builder = new SubscribeSettings.Builder();
        builder.setSubscribeCount(-1);
    }

    @Test
    public void testSubscribeSettingsBuilderNegativeTtl() {
        thrown.expect(IllegalArgumentException.class);
        SubscribeSettings.Builder builder = new SubscribeSettings.Builder();
        builder.setTtlSec(-100);
    }

    @Test
    public void testSubscribeSettingsParcel() {
        final int subscribeType = SubscribeSettings.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeCount = 10;
        final int subscribeTtl = 15;

        SubscribeSettings.Builder builder = new SubscribeSettings.Builder();
        builder.setSubscribeType(subscribeType);
        builder.setSubscribeCount(subscribeCount);
        builder.setTtlSec(subscribeTtl);
        SubscribeSettings subscribeSetting = builder.build();

        Parcel parcelW = Parcel.obtain();
        subscribeSetting.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        SubscribeSettings rereadSubscribeSettings = SubscribeSettings.CREATOR
                .createFromParcel(parcelR);

        assertEquals(subscribeSetting, rereadSubscribeSettings);
    }

    /*
     * PublishData Tests
     */

    @Test
    public void testPublishDataBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };

        PublishData.Builder builder = new PublishData.Builder();
        builder.setServiceName(serviceName);
        builder.setServiceSpecificInfo(serviceSpecificInfo);
        builder.setTxFilter(txFilter, txFilter.length);
        builder.setRxFilter(rxFilter, rxFilter.length);
        PublishData publishData = builder.build();

        collector.checkThat("mServiceName", serviceName, equalTo(publishData.mServiceName));
        String mServiceSpecificInfo = new String(publishData.mServiceSpecificInfo, 0,
                publishData.mServiceSpecificInfoLength);
        collector.checkThat("mServiceSpecificInfo",
                utilAreArraysEqual(serviceSpecificInfo.getBytes(), serviceSpecificInfo.length(),
                        publishData.mServiceSpecificInfo, publishData.mServiceSpecificInfoLength),
                equalTo(true));
        collector.checkThat("mTxFilter", utilAreArraysEqual(txFilter, txFilter.length,
                publishData.mTxFilter, publishData.mTxFilterLength), equalTo(true));
        collector.checkThat("mRxFilter", utilAreArraysEqual(rxFilter, rxFilter.length,
                publishData.mRxFilter, publishData.mRxFilterLength), equalTo(true));
    }

    @Test
    public void testPublishDataParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };

        PublishData.Builder builder = new PublishData.Builder();
        builder.setServiceName(serviceName);
        builder.setServiceSpecificInfo(serviceSpecificInfo);
        builder.setTxFilter(txFilter, txFilter.length);
        builder.setTxFilter(rxFilter, rxFilter.length);
        PublishData publishData = builder.build();

        Parcel parcelW = Parcel.obtain();
        publishData.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        PublishData rereadPublishData = PublishData.CREATOR.createFromParcel(parcelR);

        assertEquals(publishData, rereadPublishData);
    }

    /*
     * PublishSettings Tests
     */

    @Test
    public void testPublishSettingsBuilder() {
        final int publishType = PublishSettings.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;

        PublishSettings.Builder builder = new PublishSettings.Builder();
        builder.setPublishType(publishType);
        builder.setPublishCount(publishCount);
        builder.setTtlSec(publishTtl);
        PublishSettings publishSetting = builder.build();

        collector.checkThat("mPublishType", publishType, equalTo(publishSetting.mPublishType));
        collector.checkThat("mPublishCount", publishCount, equalTo(publishSetting.mPublishCount));
        collector.checkThat("mTtlSec", publishTtl, equalTo(publishSetting.mTtlSec));
    }

    @Test
    public void testPublishSettingsBuilderBadPublishType() {
        thrown.expect(IllegalArgumentException.class);
        PublishSettings.Builder builder = new PublishSettings.Builder();
        builder.setPublishType(5);
    }

    @Test
    public void testPublishSettingsBuilderNegativeCount() {
        thrown.expect(IllegalArgumentException.class);
        PublishSettings.Builder builder = new PublishSettings.Builder();
        builder.setPublishCount(-4);
    }

    @Test
    public void testPublishSettingsBuilderNegativeTtl() {
        thrown.expect(IllegalArgumentException.class);
        PublishSettings.Builder builder = new PublishSettings.Builder();
        builder.setTtlSec(-10);
    }

    @Test
    public void testPublishSettingsParcel() {
        final int publishType = PublishSettings.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;

        PublishSettings.Builder builder = new PublishSettings.Builder();
        builder.setPublishType(publishType);
        builder.setPublishCount(publishCount);
        builder.setTtlSec(publishTtl);
        PublishSettings configSetting = builder.build();

        Parcel parcelW = Parcel.obtain();
        configSetting.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        PublishSettings rereadPublishSettings = PublishSettings.CREATOR.createFromParcel(parcelR);

        assertEquals(configSetting, rereadPublishSettings);
    }

    /*
     * Utilities
     */

    private static boolean utilAreArraysEqual(byte[] x, int xLength, byte[] y, int yLength) {
        if (xLength != yLength) {
            return false;
        }

        if (x != null && y != null) {
            for (int i = 0; i < xLength; ++i) {
                if (x[i] != y[i]) {
                    return false;
                }
            }
        } else if (xLength != 0) {
            return false; // invalid != invalid
        }

        return true;
    }
}
