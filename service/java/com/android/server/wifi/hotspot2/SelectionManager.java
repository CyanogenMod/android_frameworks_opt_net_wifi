package com.android.server.wifi.hotspot2;

import android.util.Log;

import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiStateMachine;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.omadm.MOManager;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.android.server.wifi.anqp.Constants.ANQPElementType;

public class SelectionManager {
    private final List<HomeSP> mHomeSPs;
    private final AnqpCache mAnqpCache;
    private final List<PasspointMatchInfo> mMatchInfoList;
    private final SupplicantBridge mSupplicantBridge;
    private final WifiStateMachine mWifiStateMachine;

    public SelectionManager(File ppsFile, File lastSSIDFile, WifiNative supplicantHook,
                            WifiStateMachine wfsm) throws IOException {
        preLoad(ppsFile);
        MOManager moManager = new MOManager(ppsFile);
        mHomeSPs = moManager.loadAllSPs();
        mMatchInfoList = new ArrayList<>();
        Chronograph chronograph = new Chronograph();
        chronograph.start();
        mSupplicantBridge = new SupplicantBridge(supplicantHook, lastSSIDFile, this, chronograph);
        mWifiStateMachine = wfsm;
        mAnqpCache = new AnqpCache(chronograph);
    }

    private static final String PrepCreds =
            "tree 3:1.2(urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0)" +
                    "1:.+" +
                    " 17:PerProviderSubscription(urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0)+" +
                    "  2:x1+" +
                    "   6:HomeSP+" +
                    "    c:FriendlyName=e:Wi-Fi Alliance" +
                    "    a:HomeOIList+" +
                    "     2:x2+" +
                    "      e:HomeOIRequired=5:FALSE" +
                    "      6:HomeOI=6:004096" +
                    "     ." +
                    "     2:x1+" +
                    "      e:HomeOIRequired=5:FALSE" +
                    "      6:HomeOI=6:506f9a" +
                    "     ." +
                    "    ." +
                    "    4:FQDN=9:wi-fi.org" +
                    "   ." +
                    "   a:Credential+" +
                    "    10:UsernamePassword+" +
                    "     9:EAPMethod+" +
                    "      b:InnerMethod=a:MS-CHAP-V2" +
                    "      7:EAPType=2:21" +
                    "     ." +
                    "     8:Password=c:Q2hhbmdlTWU=" +
                    "     8:Username=6:test01" +
                    "     e:MachineManaged=4:TRUE" +
                    "    ." +
                    "    5:Realm=9:wi-fi.org" +
                    "    c:CreationDate=14:2012-12-01T12:00:00Z" +
                    "   ." +
                    "   12:SubscriptionUpdate+" +
                    "    e:UpdateInterval=a:4294967295" +
                    "    c:UpdateMethod=f:ClientInitiated" +
                    "    3:URI=28:subscription-server.R2-testbed.wi-fi.org" +
                    "    b:Restriction=6:HomeSP" +
                    "   ." +
                    "   17:SubscriptionRemediation+" +
                    "    15:certSHA256Fingerprint=40:abcdef01234567899876543210fedcbaabcdef01234567899876543210fedcba" +
                    "    7:certURL=39:http://remediation-server.R2-testbed.wi-fi.org/server.cer" +
                    "    3:URI=27:remediation-server.R2-testbed.wi-fi.org" +
                    "   ." +
                    "   16:SubscriptionParameters+" +
                    "   ." +
                    "   24:CredentialPriorityCredentialPriority=1:1" +
                    "  ." +
                    " ." +
                    "." +
                    "tree 3:1.2(urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0)" +
                    "1:.+" +
                    " 17:PerProviderSubscription(urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0)+" +
                    "  2:x1+" +
                    "   6:HomeSP+" +
                    "    c:FriendlyName=e:Wi-Fi Alliance" +
                    "    a:HomeOIList+" +
                    "     2:x2+" +
                    "      e:HomeOIRequired=5:FALSE" +
                    "      6:HomeOI=6:004096" +
                    "     ." +
                    "     2:x1+" +
                    "      e:HomeOIRequired=5:FALSE" +
                    "      6:HomeOI=6:506f9a" +
                    "     ." +
                    "    ." +
                    "    4:FQDN=a:access.net" +
                    "   ." +
                    "   a:Credential+" +
                    "    10:UsernamePassword+" +
                    "     9:EAPMethod+" +
                    "      b:InnerMethod=a:MS-CHAP-V2" +
                    "      7:EAPType=2:21" +
                    "     ." +
                    "     8:Password=c:Q2hhbmdlTWU=" +
                    "     8:Username=6:test01" +
                    "     e:MachineManaged=4:TRUE" +
                    "    ." +
                    "    5:Realm=9:wi-fi.org" +
                    "    c:CreationDate=14:2012-12-01T12:00:00Z" +
                    "   ." +
                    "   12:SubscriptionUpdate+" +
                    "    e:UpdateInterval=a:4294967295" +
                    "    c:UpdateMethod=f:ClientInitiated" +
                    "    3:URI=28:subscription-server.R2-testbed.wi-fi.org" +
                    "    b:Restriction=6:HomeSP" +
                    "   ." +
                    "   17:SubscriptionRemediation+" +
                    "    15:certSHA256Fingerprint=40:abcdef01234567899876543210fedcbaabcdef01234567899876543210fedcba" +
                    "    7:certURL=39:http://remediation-server.R2-testbed.wi-fi.org/server.cer" +
                    "    3:URI=27:remediation-server.R2-testbed.wi-fi.org" +
                    "   ." +
                    "   16:SubscriptionParameters+" +
                    "   ." +
                    "   24:CredentialPriorityCredentialPriority=1:1" +
                    "  ." +
                    " ." +
                    ".";

    private static void preLoad(File ppsFile) throws IOException {
        if (!ppsFile.exists() || ppsFile.length() == 0) {
            BufferedWriter out =
                    new BufferedWriter(new OutputStreamWriter(new FileOutputStream(ppsFile),
                            StandardCharsets.US_ASCII));
            out.write(PrepCreds);
            out.close();
        }
    }

    public ScanDetail scoreNetwork(ScanDetail scanDetail) {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        if (!networkDetail.has80211uInfo()) {
            return null;
        }
        updateCache(scanDetail, networkDetail.getANQPElements());

        Map<HomeSP, PasspointMatch> matches = matchNetwork(scanDetail,
                networkDetail.getANQPElements() == null);
        if (!matches.isEmpty()) {
            return scanDetail.score(matches);
        } else {
            return null;
        }
    }

    public void notifyANQPResponse(ScanDetail scanDetail,
                                   Map<ANQPElementType, ANQPElement> anqpElements) {

        updateCache(scanDetail, anqpElements);

        Map<HomeSP, PasspointMatch> matches = matchNetwork(scanDetail, false);
        Log.d("HS2J", scanDetail.getSSID() + " 2nd Matches: " + toMatchString(matches));

        for (Map.Entry<HomeSP, PasspointMatch> entry : matches.entrySet()) {
            mMatchInfoList.add(
                    new PasspointMatchInfo(entry.getValue(), scanDetail.getNetworkDetail(),
                            entry.getKey()));
        }

        mWifiStateMachine.updateScanResults(scanDetail, matches);
        selectNetwork(mMatchInfoList);  // !!! Remove!
    }

    private volatile boolean xSelected;

    public boolean selectNetwork(List<PasspointMatchInfo> networks) {
        if (networks.isEmpty()) {
            return false;
        }

        if (xSelected) {
            return false;
        }

        Collections.sort(networks);
        PasspointMatchInfo top = networks.iterator().next();
        if (top.getPasspointMatch() != PasspointMatch.HomeProvider &&
                top.getPasspointMatch() != PasspointMatch.RoamingProvider) {
            return false;
        }

        boolean status = mSupplicantBridge.addCredential(top.getHomeSP(), top.getNetworkDetail());
        Log.d("HS2J", "add credential: " + status);
        xSelected = status;
        return status;
    }

    int getRetry(NetworkDetail networkDetail, int limit) {
        int retry = mAnqpCache.getRetry(networkDetail);
        Log.d("HS2J", "Retry count for " + networkDetail.getSSID() + ": " + retry);
        return retry >= 0 && retry < limit ? retry : -1;
    }

    private Map<HomeSP, PasspointMatch> matchNetwork(ScanDetail scanDetail, boolean query) {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();

        ANQPData anqpData = mAnqpCache.getEntry(networkDetail);

        Map<ANQPElementType, ANQPElement> anqpElements =
                anqpData != null ? anqpData.getANQPElements() : null;

        boolean queried = !query;
        Map<HomeSP, PasspointMatch> matches = new HashMap<>(mHomeSPs.size());
        for (HomeSP homeSP : mHomeSPs) {
            PasspointMatch match = homeSP.match(networkDetail, anqpElements);

            if (match == PasspointMatch.Incomplete && networkDetail.isInterworking() && !queried) {
                if (mAnqpCache.initiate(networkDetail)) {
                    mSupplicantBridge.startANQP(scanDetail);
                }
                queried = true;
            }
            matches.put(homeSP, match);
        }
        return matches;
    }

    private void updateCache(ScanDetail scanDetail, Map<ANQPElementType, ANQPElement> anqpElements)
    {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();

        if (anqpElements == null) {
            ANQPData data = mAnqpCache.getEntry(networkDetail);
            if (data != null) {
                scanDetail.propagateANQPInfo(data.getANQPElements());
            }
            return;
        }

        mAnqpCache.update(networkDetail, anqpElements);

        Log.d("HS2J", "Cached " + networkDetail.getBSSIDString() +
                "/" + networkDetail.getAnqpDomainID());
        scanDetail.propagateANQPInfo(anqpElements);
    }

    private static String toMatchString(Map<HomeSP, PasspointMatch> matches) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<HomeSP, PasspointMatch> entry : matches.entrySet()) {
            sb.append(' ').append(entry.getKey().getFQDN()).append("->").append(entry.getValue());
        }
        return sb.toString();
    }
}
