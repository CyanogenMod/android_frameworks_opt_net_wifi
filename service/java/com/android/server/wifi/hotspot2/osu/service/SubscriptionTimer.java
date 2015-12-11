package com.android.server.wifi.hotspot2.osu.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.omadm.MOManager;
import com.android.server.wifi.hotspot2.osu.OSUManager;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SubscriptionTimer {
    private final AlarmManager mAlarmManager;
    private final OSUManager mOSUManager;
    private final MOManager mMOManager;
    private final PendingIntent mTimerIntent;
    private final Map<HomeSP, UpdateAction> mOutstanding = new HashMap<>();

    private static class UpdateAction {
        private final long mRemediation;
        private final long mPolicy;

        private UpdateAction(HomeSP homeSP, long now) {
            mRemediation = homeSP.getSubscriptionUpdate() != null ?
                    now + homeSP.getSubscriptionUpdate().getInterval() : -1;
            mPolicy = homeSP.getPolicy() != null ?
                    now + homeSP.getPolicy().getPolicyUpdate().getInterval() : -1;

            Log.d(OSUManager.TAG, "Timer set for " + homeSP .getFQDN() +
                    ", remediation: " + Utils.toUTCString(mRemediation) +
                    ", policy: " + Utils.toUTCString(mPolicy));
        }

        private boolean remediate(long now) {
            return mRemediation > 0 && now >= mRemediation;
        }

        private boolean policyUpdate(long now) {
            return mPolicy > 0 && now >= mPolicy;
        }

        private long nextExpiry(long now) {
            long min = Long.MAX_VALUE;
            if (mRemediation > now) {
                min = mRemediation;
            }
            if (mPolicy > now) {
                min = Math.min(min, mPolicy);
            }
            return min;
        }
    }

    private static final String ACTION_TIMER =
            "com.android.server.wifi.hotspot2.osu.service.SubscriptionTimer.action.TICK";

    public SubscriptionTimer(OSUManager osuManager, MOManager moManager, Context context) {
        mOSUManager = osuManager;
        mMOManager = moManager;
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(ACTION_TIMER, null);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.setPackage("android");
        mTimerIntent = PendingIntent.getBroadcast(context, 0, intent, 0);

        context.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        tick();
                    }
                },
                new IntentFilter(ACTION_TIMER));
    }

    public void checkUpdates() {
        mAlarmManager.cancel(mTimerIntent);
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (HomeSP homeSP : mMOManager.getLoadedSPs().values()) {
            UpdateAction updateAction = mOutstanding.get(homeSP);
            try {
                if (updateAction == null) {
                    updateAction = new UpdateAction(homeSP, now);
                    mOutstanding.put(homeSP, updateAction);
                } else if (updateAction.remediate(now)) {
                    mOSUManager.remediate(homeSP, false);
                    mOutstanding.put(homeSP, new UpdateAction(homeSP, now));
                } else if (updateAction.policyUpdate(now)) {
                    mOSUManager.remediate(homeSP, true);
                    mOutstanding.put(homeSP, new UpdateAction(homeSP, now));
                }
                next = Math.min(next, updateAction.nextExpiry(now));
            }
            catch (IOException ioe) {
                Log.d(OSUManager.TAG, "Failed subscription update: " + ioe.getMessage());
            }
        }
        setAlarm(next);
    }

    private void setAlarm(long tod) {
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, tod, mTimerIntent);
    }

    private void tick() {
        checkUpdates();
    }
}
