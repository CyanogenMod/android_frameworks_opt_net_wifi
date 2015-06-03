package com.android.server.wifi;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

public class SIMAccessor {
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;

    public SIMAccessor(Context context) {
        mTelephonyManager = TelephonyManager.from(context);
        mSubscriptionManager = SubscriptionManager.from(context);
    }

    public String getMatchingImsi(String mccMnc) {
        if (mccMnc == null) {
            return null;
        }
        for (int subId : mSubscriptionManager.getActiveSubscriptionIdList()) {
            String imsi = mTelephonyManager.getSubscriberId(subId);
            if (imsi.startsWith(mccMnc)) {
                return imsi;
            }
        }
        return null;
    }
}
