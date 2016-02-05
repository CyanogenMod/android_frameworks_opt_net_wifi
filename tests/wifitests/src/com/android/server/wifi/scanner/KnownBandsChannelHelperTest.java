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

package com.android.server.wifi.scanner;

import static com.android.server.wifi.ScanTestUtil.bandIs;
import static com.android.server.wifi.ScanTestUtil.channelsAre;

import static org.hamcrest.MatcherAssert.assertThat;

import android.net.wifi.WifiScanner;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.WifiNative;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link com.android.server.wifi.scanner.KnownBandsChannelHelper}.
 */
@SmallTest
@RunWith(Enclosed.class) // WARNING: tests cannot be declared in the outer class
public class KnownBandsChannelHelperTest {

    /**
     * Unit tests for
     * {@link com.android.server.wifi.scanner.KnownBandsChannelHelper.KnownBandsChannelCollection}.
     */
    public static class KnownBandsChannelCollectionTest {
        ChannelHelper.ChannelCollection mChannelCollection;

        /**
         * Called before each test
         * Create a collection to use for each test
         */
        @Before
        public void setUp() throws Exception {
            KnownBandsChannelHelper channelHelper = new KnownBandsChannelHelper(
                    new int[]{2400, 2450},
                    new int[]{5150, 5175},
                    new int[]{5600, 5650, 5660});
            mChannelCollection = channelHelper.createChannelCollection();
        }

        /**
         * Create an empty collection
         */
        @Test
        public void empty() {
            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);

            assertThat(bucketSettings, channelsAre());
        }

        /**
         * Add something to a collection and then clear it and make sure nothing is in it
         */
        @Test
        public void clear() {
            mChannelCollection.addBand(WifiScanner.WIFI_BAND_24_GHZ);
            mChannelCollection.clear();

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);
            assertThat(bucketSettings, channelsAre());
        }

        /**
         * Add a single band to the collection
         */
        @Test
        public void addBand() {
            mChannelCollection.addBand(WifiScanner.WIFI_BAND_24_GHZ);

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);
            assertThat(bucketSettings, bandIs(WifiScanner.WIFI_BAND_24_GHZ));
        }

        /**
         * Add a single channel to the collection
         */
        @Test
        public void addChannel_single() {
            mChannelCollection.addChannel(2400);

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);
            assertThat(bucketSettings, channelsAre(2400));
        }

        /**
         * Add a multiple channels to the collection
         */
        @Test
        public void addChannel_multiple() {
            mChannelCollection.addChannel(2400);
            mChannelCollection.addChannel(2450);

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);
            assertThat(bucketSettings, channelsAre(2400, 2450));
        }

        /**
         * Add a band and channel that is on that band
         */
        @Test
        public void addChannel_and_addBand_sameBand() {
            mChannelCollection.addBand(WifiScanner.WIFI_BAND_24_GHZ);
            mChannelCollection.addChannel(2400);

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);
            assertThat(bucketSettings, bandIs(WifiScanner.WIFI_BAND_24_GHZ));
        }

        /**
         * Add a band and channel that is not that band
         */
        @Test
        public void addChannel_and_addBand_withDifferentBandChannel() {
            mChannelCollection.addBand(WifiScanner.WIFI_BAND_24_GHZ);
            mChannelCollection.addChannel(5150);

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);
            assertThat(bucketSettings, channelsAre(2400, 2450, 5150));
        }

        /**
         * Add enough channels on a single band that the max channels is exceeded
         */
        @Test
        public void addChannel_exceedMaxChannels() {
            mChannelCollection.addChannel(5600);
            mChannelCollection.addChannel(5650);
            mChannelCollection.addChannel(5660);

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, 2);
            assertThat(bucketSettings, bandIs(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY));
        }

        /**
         * Add enough channels across multiple bands that the max channels is exceeded
         */
        @Test
        public void addChannel_exceedMaxChannelsOnMultipleBands() {
            mChannelCollection.addChannel(2400);
            mChannelCollection.addChannel(2450);
            mChannelCollection.addChannel(5150);

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            mChannelCollection.fillBucketSettings(bucketSettings, 2);
            assertThat(bucketSettings, bandIs(WifiScanner.WIFI_BAND_BOTH));
        }
    }
}
