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

package com.android.server.wifi.hotspot;

import android.content.Context;
import android.net.wifi.hotspot.IWifiHotspotManager;
import android.util.Log;

/**
 * TODO: doc
 * @hide
 */
public final class WifiHotspotServiceImpl extends IWifiHotspotManager.Stub {
    private static final String TAG = "WifiHotspotService";
    private static final boolean DBG = false;

    public WifiHotspotServiceImpl(Context context) {
        // TODO
    }

    public void test() {
        Log.d(TAG, "test()");
    }
}

