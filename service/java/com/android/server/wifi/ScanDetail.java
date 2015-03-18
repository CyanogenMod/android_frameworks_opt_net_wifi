package com.android.server.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiSsid;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.HSFriendlyNameElement;
import com.android.server.wifi.anqp.VenueNameElement;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.PasspointMatchInfo;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScanDetail {
    private final ScanResult mScanResult;
    private volatile NetworkDetail mNetworkDetail;
    private final Map<HomeSP, PasspointMatch> mMatches;

    public ScanDetail(NetworkDetail networkDetail, WifiSsid wifiSsid, String BSSID,
                      String caps, int level, int frequency, long tsf) {
        mNetworkDetail = networkDetail;
        mScanResult = new ScanResult(wifiSsid, BSSID, caps, level, frequency, tsf );
        mScanResult.seen = System.currentTimeMillis();
        mScanResult.channelWidth = networkDetail.getChannelWidth();
        mScanResult.centerFreq0 = networkDetail.getCenterfreq0();
        mScanResult.centerFreq1 = networkDetail.getCenterfreq1();
        mScanResult.is80211McRTTResponder = networkDetail.is80211McResponderSupport();
        mMatches = null;
    }

    private ScanDetail(ScanResult scanResult, NetworkDetail networkDetail,
                       Map<HomeSP, PasspointMatch> matches) {
        mScanResult = scanResult;
        mNetworkDetail = networkDetail;
        mMatches = matches;
    }

    public ScanDetail score(Map<HomeSP, PasspointMatch> matches) {
        return new ScanDetail(mScanResult, mNetworkDetail, matches);
    }

    public void updateResults(NetworkDetail networkDetail, int level, WifiSsid wssid, String ssid,
                              String flags, int freq, long tsf) {
        mScanResult.level = level;
        mScanResult.wifiSsid = wssid;
        // Keep existing API
        mScanResult.SSID = ssid;
        mScanResult.capabilities = flags;
        mScanResult.frequency = freq;
        mScanResult.timestamp = tsf;
        mScanResult.seen = System.currentTimeMillis();
        mScanResult.channelWidth = networkDetail.getChannelWidth();
        mScanResult.centerFreq0 = networkDetail.getCenterfreq0();
        mScanResult.centerFreq1 = networkDetail.getCenterfreq1();
        mScanResult.is80211McRTTResponder = networkDetail.is80211McResponderSupport();
    }

    public void propagateANQPInfo(Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
        mNetworkDetail = mNetworkDetail.complete(anqpElements);
        HSFriendlyNameElement fne = (HSFriendlyNameElement)anqpElements.get(Constants
                .ANQPElementType.HSFriendlyName);
        // !!! Match with language
        if (fne != null && !fne.getNames().isEmpty()) {
            mScanResult.venueName = fne.getNames().get(0).getText();
        } else {
            VenueNameElement vne =
                    (((VenueNameElement)anqpElements.get(Constants.ANQPElementType.ANQPVenueName)));
            if (vne != null && !vne.getNames().isEmpty()) {
                mScanResult.venueName = vne.getNames().get(0).getText();
            }
        }
    }

    public ScanResult getScanResult() {
        return mScanResult;
    }

    public NetworkDetail getNetworkDetail() {
        return mNetworkDetail;
    }

    public String getSSID() {
        return mNetworkDetail.getSSID();
    }

    public String getBSSIDString() {
        return mNetworkDetail.getBSSIDString();
    }

    public List<PasspointMatchInfo> getMatchList() {
        if (mMatches == null || mMatches.isEmpty()) {
            return null;
        }

        List<PasspointMatchInfo> list = new ArrayList<>();
        for (Map.Entry<HomeSP, PasspointMatch> entry : mMatches.entrySet()) {
            new PasspointMatchInfo(entry.getValue(), this, entry.getKey());
        }
        return list;
    }
}
