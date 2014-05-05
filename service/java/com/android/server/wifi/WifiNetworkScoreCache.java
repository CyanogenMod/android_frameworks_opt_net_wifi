/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.wifi;

import android.net.INetworkScoreCache;
import android.net.NetworkKey;
import android.net.ScoredNetwork;
import android.net.wifi.ScanResult;
import android.util.Log;

import java.util.HashMap;
import java.util.List;

public class WifiNetworkScoreCache extends INetworkScoreCache.Stub
 {

    private static String TAG = "WifiNetworkScoreCache";
    private boolean DBG = true;
    private HashMap<String, ScoredNetwork> mNetworkCache;

    public WifiNetworkScoreCache() {
        mNetworkCache = new HashMap<String, ScoredNetwork>();
    }


     //void updateScores(in List<ScoredNetwork> networks);

     @Override public final void updateScores(List<android.net.ScoredNetwork> networks) {
        if (networks == null) {
            return;
        }
        Log.e(TAG, "updateScores list size=" + networks.size());

        synchronized(mNetworkCache) {
            for (ScoredNetwork network : networks) {
                if (network.networkKey == null) continue;
                if (network.networkKey.wifiKey == null) continue;
                if (network.networkKey.type == NetworkKey.TYPE_WIFI) {
                    String key = network.networkKey.wifiKey.ssid;
                    if (key == null) continue;
                    if (network.networkKey.wifiKey.bssid != null) {
                        key = key + network.networkKey.wifiKey.bssid;
                    }
                    mNetworkCache.put(key, network);
                }
            }
        }
     }

     @Override public final void clearScores() {
         synchronized(mNetworkCache) {
             mNetworkCache.clear();
         }
     }


    public int getNetworkScore(ScanResult result) {

        int score = -1;

        String key = result.SSID;
        if (key == null) return -1;
        if (result.BSSID != null) {
            key = key + result.BSSID;
        }
        //find it
        synchronized(mNetworkCache) {
            ScoredNetwork network = mNetworkCache.get(key);
            if (network != null && network.rssiCurve != null) {
                score = network.rssiCurve.lookupScore(result.level);
                if (DBG) {
                    Log.e(TAG, "getNetworkScore found Herrevad network" + key
                            + " score " + Integer.toString(score)
                            + " RSSI " + result.level);
                }
            }
        }
        return score;
    }

}
