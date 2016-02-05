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

import android.net.wifi.WifiScanner;

import com.android.server.wifi.WifiNative;

/**
 * ChannelHelper offers an abstraction for channel manipulation utilities allowing operation to be
 * adjusted based on the amount of information known about the available channels.
 */
public abstract class ChannelHelper {

    /**
     * Create a new collection that can be used to store channels
     */
    public abstract ChannelCollection createChannelCollection();

    /**
     * Object that supports accumulation of channels and bands
     */
    public abstract class ChannelCollection {
        /**
         * Add a channel to the collection
         */
        public abstract void addChannel(int channel);
        /**
         * Add all channels in the band to the collection
         */
        public abstract void addBand(int band);
        /**
         * Remove all channels from the collection
         */
        public abstract void clear();

        /**
         * Add all channels in the ScanSetting to the collection
         */
        public void addChannels(WifiScanner.ScanSettings setting) {
            if (setting.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
                for (int j = 0; j < setting.channels.length; ++j) {
                    addChannel(setting.channels[j].frequency);
                }
            } else {
                addBand(setting.band);
            }
        }

        /**
         * Store the channels in this collection in the supplied BucketSettings. If maxChannels is
         * exceeded or a band better describes the channels then a band is specified instead of a
         * channel list.
         */
        public abstract void fillBucketSettings(WifiNative.BucketSettings bucket, int maxChannels);
    }
}
