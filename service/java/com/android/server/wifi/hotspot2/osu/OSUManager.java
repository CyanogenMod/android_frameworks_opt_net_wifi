package com.android.server.wifi.hotspot2.osu;

import android.content.Context;
import android.content.Intent;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ICaptivePortal;
import android.net.Network;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.ConnectivityService;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiStateMachine;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.HSOsuProvidersElement;
import com.android.server.wifi.anqp.OSUProvider;
import com.android.server.wifi.configparse.ConfigBuilder;
import com.android.server.wifi.hotspot2.IconEvent;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.OMADMAdapter;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.SupplicantBridge;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.WifiNetworkAdapter;
import com.android.server.wifi.hotspot2.omadm.MOManager;
import com.android.server.wifi.hotspot2.omadm.MOTree;
import com.android.server.wifi.hotspot2.osu.commands.MOData;
import com.android.server.wifi.hotspot2.osu.service.RedirectListener;
import com.android.server.wifi.hotspot2.osu.service.SubscriptionTimer;
import com.android.server.wifi.hotspot2.pps.HomeSP;
import com.android.server.wifi.hotspot2.pps.UpdateInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;

import static com.android.server.wifi.anqp.Constants.ANQPElementType.ANQPDomName;
import static com.android.server.wifi.anqp.Constants.ANQPElementType.HSOSUProviders;

public class OSUManager {
    public static final String TAG = "OSUMGR";
    public static final boolean R2_TEST = false;

    private static final int MAX_SCAN_MISSES = 15;
    private static final long REMEDIATION_TIMEOUT = 120000L;
    // How many scan result batches to hang on to
    private static final int SCAN_BATCH_HISTORY = 3;

    public enum FlowType {Provisioning, Remediation, Policy}

    private static final Set<String> IconTypes = new HashSet<>(Arrays.asList("image/png", "image/jpeg"));
    private static final int IconWidth = 16;
    private static final int IconHeight = 16;
    private static final Locale Locale = java.util.Locale.getDefault();

    private final WifiNetworkAdapter mWifiNetworkAdapter;

    private final boolean mEnabled;
    private final Context mContext;
    private final SupplicantBridge mSupplicantBridge;
    private final WifiConfigStore mWifiConfigStore;
    private final IconCache mIconCache;
    private final MOManager mMOManager;
    private final SubscriptionTimer mSubscriptionTimer;
    private final Map<OSUListener, Boolean> mOSUListeners = new IdentityHashMap<>();
    private final Set<String> mOSUSSIDs = new HashSet<>();
    private final Map<OSUProvider, OSUInfo> mOSUMap = new HashMap<>();
    private RedirectListener mRedirectListener;
    private volatile UserInputListener mUserInputListener;
    private volatile boolean mIconQueryEnable;
    private WifiConfiguration mActiveConfig;
    private final Object mRemediationLock = new Object();
    private long mRemediationBSSID;
    private String mRemediationURL;
    private final AtomicInteger mOSUSequence = new AtomicInteger();
    private OSUThread mProvisioningThread;
    private final Map<String, OSUThread> mServiceThreads = new HashMap<>();

    // Variables that holds transient data during scans
    private boolean mScanComplete;
    private boolean mNewScan;
    private final Set<Long> mOutstandingQueries = new HashSet<>();
    private final Map<Long, AtomicInteger> mScanHistory = new HashMap<>();  // BSSID -> missing scan count
    private final Map<Long, HSOsuProvidersElement> mTransientOSUMap = new HashMap<>();
    private final Map<Long, HSOsuProvidersElement> mCachedOSUMap = new HashMap<>();
    private final Map<Long, NetworkDetail> mLastScanBatch = new HashMap<>();
    private final LinkedList<Map<Long, NetworkDetail>> mLastScanBatches = new LinkedList<>();

    public OSUManager(WifiConfigStore wifiConfigStore, Context context,
                      SupplicantBridge supplicantBridge, MOManager moManager,
                      WifiStateMachine wifiStateMachine) {
        mWifiConfigStore = wifiConfigStore;
        mContext = context;
        mSupplicantBridge = supplicantBridge;
        mIconCache = new IconCache(this);
        mWifiNetworkAdapter = new WifiNetworkAdapter(context, this, wifiStateMachine, wifiConfigStore);
        mMOManager = moManager;
        mSubscriptionTimer = new SubscriptionTimer(this, moManager, context);
        mEnabled = moManager.isEnabled();
        mNewScan = true;
        mIconQueryEnable = true;    // For testing only
    }

    private static class OSUThread extends Thread {
        private final OSUClient mOSUClient;
        private final OSUManager mOSUManager;
        private final HomeSP mHomeSP;
        private final FlowType mFlowType;
        private final KeyManager mKeyManager;
        private final long mLaunchTime;
        private final Object mLock = new Object();
        private boolean mLocalAddressSet;
        private Network mNetwork;

        private OSUThread(OSUInfo osuInfo, OSUManager osuManager, KeyManager km) throws MalformedURLException {
            mOSUClient = new OSUClient(osuInfo);
            mOSUManager = osuManager;
            mHomeSP = null;
            mFlowType = FlowType.Provisioning;
            mKeyManager = km;
            mLaunchTime = System.currentTimeMillis();

            setDaemon(true);
            setName("OSU Client Thread");
        }

        private OSUThread(String osuURL, OSUManager osuManager, KeyManager km, HomeSP homeSP,
                          FlowType flowType) throws MalformedURLException {
            mOSUClient = new OSUClient(osuURL);
            mOSUManager = osuManager;
            mHomeSP = homeSP;
            mFlowType = flowType;
            mKeyManager = km;
            mLaunchTime = System.currentTimeMillis();

            setDaemon(true);
            setName("OSU Client Thread");
        }

        public long getLaunchTime() {
            return mLaunchTime;
        }

        private void connect(Network network) {
            synchronized (mLock) {
                mNetwork = network;
                mLocalAddressSet = true;
                mLock.notifyAll();
            }
            Log.d(TAG, "Client notified...");
        }

        @Override
        public void run() {
            Log.d(TAG, mFlowType + "-" + getName() + " running.");
            Network network;
            synchronized (mLock) {
                while (!mLocalAddressSet) {
                    try {
                        mLock.wait();
                    }
                    catch (InterruptedException ie) {
                        /**/
                    }
                    Log.d(TAG, "Good morning!");
                }
                network = mNetwork;
            }

            long remaining = 10000L;
            long until = System.currentTimeMillis() + remaining;
            while (remaining > 0) {
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException ie) {

                }
                remaining = until - System.currentTimeMillis();
            }
            Log.d(TAG, "OK, off we go...");

            if (network == null) {
                Log.d(TAG, "Association failed, exiting OSU flow");
                mOSUManager.provisioningFailed("Network cannot be reached", mHomeSP, mFlowType);
                return;
            }

            Log.d(TAG, "OSU SSID Associated at " + network.toString());
            try {
                switch (mFlowType) {
                    case Provisioning:
                        mOSUClient.provision(mOSUManager, network, mKeyManager);
                        break;
                    case Remediation:
                        mOSUClient.remediate(mOSUManager, network, mKeyManager, mHomeSP, false);
                        break;
                    case Policy:
                        mOSUClient.remediate(mOSUManager, network, mKeyManager, mHomeSP, true);
                        break;
                }
            }
            catch (Throwable t) {
                Log.w(TAG, "OSU flow failed: " + t, t);
                mOSUManager.provisioningFailed(t.getMessage(), mHomeSP, mFlowType);
            }
        }
    }

    public void addMockOSUEnvironment(final OSUManager osuManager) {
        osuManager.registerUserInputListener(new UserInputListener() {
            @Override
            public void requestUserInput(URL target, Network network, URL endRedirect) {
                Log.d(TAG, "Browser to " + target + ", land at " + endRedirect);

                final Intent intent = new Intent(
                        ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN);
                intent.putExtra(ConnectivityManager.EXTRA_NETWORK, network);
                intent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL,
                        new CaptivePortal(new ICaptivePortal.Stub() {
                            @Override
                            public void appResponse(int response) {
                            }
                        }));
                //intent.setData(Uri.parse(target.toString()));     !!! Doesn't work!
                intent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL, target.toString());
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }

            @Override
            public String operationStatus(OSUOperationStatus status, String message) {
                Log.d(TAG, "OSU OP Status: " + status + ", message " + message);
                return null;
            }

            @Override
            public void deAuthNotification(boolean ess, URL url) {
                Log.i(TAG, "De-authentication imminent for " + (ess ? "ess" : "bss") +
                        ", redirect to " + url);
            }
        });
        osuManager.addOSUListener(new OSUListener() {
            @Override
            public void osuNotification(int count) {
            }
        });
        mWifiNetworkAdapter.initialize();
        //mSubscriptionTimer.checkUpdates();    // !!! Looks like there's a bug associated with this.
    }

    public boolean enableOSUQueries() {
        return mUserInputListener != null;
    }

    public void addOSUListener(OSUListener listener) {
        synchronized (mOSUListeners) {
            mOSUListeners.put(listener, Boolean.TRUE);
        }
    }

    public void removeOSUListener(OSUListener listener) {
        synchronized (mOSUListeners) {
            mOSUListeners.remove(listener);
        }
    }

    public void registerUserInputListener(UserInputListener listener) {
        mUserInputListener = listener;
    }

    public void recheckTimers() {
        mSubscriptionTimer.checkUpdates();
    }

    /**
     * Called when an OSU has been selected and the associated network is fully connected.
     * @param osuInfo The selected OSUInfo or null if the current OSU flow is cancelled externally,
     *                e.g. WiFi is turned off or the OSU network is otherwise detected as
     *                unreachable.
     * @param network The currently associated network (for the OSU SSID).
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public void initiateProvisioning(OSUInfo osuInfo, Network network)
            throws IOException, GeneralSecurityException {
        if (mUserInputListener == null) {
            throw new RuntimeException("No user input handler specified");
        }

        synchronized (mWifiNetworkAdapter) {
            if (mProvisioningThread != null) {
                mProvisioningThread.connect(null);
                mProvisioningThread = null;
            }
            if (mRedirectListener != null) {
                mRedirectListener.abort();
                mRedirectListener = null;
            }
            if (osuInfo != null) {
                //new ConnMonitor().start();
                mProvisioningThread = new OSUThread(osuInfo, this, getKeyManager(null));
                mProvisioningThread.start();
                //mWifiNetworkAdapter.associate(osuInfo.getSSID(),
                //        osuInfo.getBSSID(), osuInfo.getOSUProvider().getOsuNai());
                mProvisioningThread.connect(network);
            }
        }
    }

    /**
     * !!! Get a KeyManager for TLS client side auth.
     * @param config The configuration associated with the keying material in question. Passing
     *               null currently returns a null KeyManager, but to support pre-provisioned certs
     *               in the future an appropriate KeyManager should be created which needs to check
     *               for the correct key-pair during the provisioning phase based on the issuer
     *               names retrieved from the ClientCertInfo request.
     * @return A key manager suitable for the given configuration (or pre-provisioned keys).
     */
    private static KeyManager getKeyManager(WifiConfiguration config) throws IOException {
        return config != null ? new ClientKeyManager(config) : new WiFiKeyManager();
    }

    private static class ConnMonitor extends Thread {
        private ConnMonitor() {
        }

        @Override
        public void run() {
            for (;;) {
                try {
                    ProcessBuilder psb = new ProcessBuilder("/system/bin/netstat", "-tn");
                    psb.redirectErrorStream();
                    Process ps = psb.start();
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(ps.getInputStream()))) {
                        String line;
                        StringBuilder sb = new StringBuilder();
                        while ((line = in.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                        Log.d("CNI", sb.toString());
                        Log.d("CNI", "*eof*");
                    }
                }
                catch (IOException ioe) {
                        Log.d("CNI", "IOE: " + ioe);
                }

                long remaining = 1000L;
                long until = System.currentTimeMillis() + remaining;
                while (remaining > 0) {
                    try {
                        Thread.sleep(remaining);
                    }
                    catch (InterruptedException ie) { /**/ }
                    remaining = until - System.currentTimeMillis();
                }
            }
        }
    }

    public Collection<OSUInfo> getAvailableOSUs() {
        synchronized (mOSUMap) {
            List<OSUInfo> completeOSUs = new ArrayList<>();
            for (OSUInfo osuInfo : mOSUMap.values()) {
                if (osuInfo.getIconStatus() == OSUInfo.IconStatus.Available) {
                    completeOSUs.add(osuInfo);
                }
            }
            return completeOSUs;
        }
    }

    public boolean isOSU(String ssid) {
        synchronized (mOSUMap) {
            return mOSUSSIDs.contains(ssid);
        }
    }

    public void setOSUSelection(int osuID) {

    }

    public void addScanResult(NetworkDetail networkDetail) {
        if (mNewScan) {
            mOutstandingQueries.clear();
            mNewScan = false;
        }
        Map<Constants.ANQPElementType, ANQPElement> anqpElements = networkDetail.getANQPElements();
        if (anqpElements != null) {
            HSOsuProvidersElement osuProviders =
                    (HSOsuProvidersElement) anqpElements.get(HSOSUProviders);
            if (osuProviders != null) {
                mTransientOSUMap.put(networkDetail.getBSSID(), osuProviders);
            }
        }
        mLastScanBatch.put(networkDetail.getBSSID(), networkDetail);
    }

    /**
     * Notification that the current scan is complete. MUST be called after any ANQP queries have
     * been initiated.
     */
    public void scanComplete() {
        if (!mEnabled) {
            return;
        }
        mScanComplete = true;
        mNewScan = true;

        Log.d("ZYX", "Scan complete: " + toBSSIDStrings(mOutstandingQueries));
        if (mOutstandingQueries.isEmpty()) {
            updateAvailableOSUs();
        }
    }

    private static String toBSSIDStrings(Set<Long> bssids) {
        StringBuilder sb = new StringBuilder();
        for (Long bssid : bssids) {
            sb.append(String.format(" %012x", bssid));
        }
        return sb.toString();
    }

    public void anqpInitiated(ScanDetail scanDetail) {
        Log.d("ZYX+", String.format("%012x", scanDetail.getNetworkDetail().getBSSID()));
        mOutstandingQueries.add(scanDetail.getNetworkDetail().getBSSID());
    }

    public void anqpDone(ScanDetail scanDetail,
                         Map<Constants.ANQPElementType, ANQPElement> elements) {
        long bssid = scanDetail.getNetworkDetail().getBSSID();
        Log.d("ZYX-", String.format("%012x", bssid));
        mOutstandingQueries.remove(bssid);
        if (elements != null) {
            HSOsuProvidersElement osuProviders =
                    (HSOsuProvidersElement) elements.get(HSOSUProviders);
            Log.d(TAG, "ANQP OSU result: " + osuProviders);
            if (osuProviders != null) {
                mTransientOSUMap.put(bssid, osuProviders);
            }
        }
        if (mOutstandingQueries.isEmpty() && mScanComplete) {
            updateAvailableOSUs();
        }
    }

    private static <T> List<T> additions(List<T> oldList, List<T> newList) {
        if (oldList == null) {
            return newList;
        }
        List<T> additions = new ArrayList<>();
        for (T element : newList) {
            if (!oldList.contains(element)) {
                additions.add(element);
            }
        }
        return additions;
    }

    private static <T> List<T> deletions(List<T> oldList, List<T> newList) {
        List<T> deletions = new ArrayList<>();
        if (oldList == null) {
            return deletions;
        }
        for (T element : oldList) {
            if (!newList.contains(element)) {
                deletions.add(element);
            }
        }
        return deletions;
    }

    private void updateAvailableOSUs() {
        boolean change = false;
        int osuCount;

        Log.d(TAG, "OSU Provider update: " + mTransientOSUMap);
        synchronized (mOSUMap) {
            for (Map.Entry<Long, HSOsuProvidersElement> entry : mTransientOSUMap.entrySet()) {
                HSOsuProvidersElement osuProviders = entry.getValue();
                long bssid = entry.getKey();

                HSOsuProvidersElement cached = mCachedOSUMap.get(bssid);
                List<OSUProvider> cachedProviders = cached != null ? cached.getProviders() : null;
                List<OSUProvider> added = additions(cachedProviders, osuProviders.getProviders());
                List<OSUProvider> dropped = deletions(cachedProviders, osuProviders.getProviders());

                boolean anyValid = false;
                if (!added.isEmpty()) {
                    for (OSUProvider osuProvider : added) {
                        if (osuProvider.getOSUMethods().contains(OSUProvider.OSUMethod.SoapXml)) {
                            anyValid = true;
                            if (!mOSUMap.containsKey(osuProvider)) {
                                mOSUMap.put(osuProvider,
                                        new OSUInfo(bssid, osuProviders.getSSID(),
                                                osuProvider, mOSUSequence.getAndIncrement()));
                                change = true;
                            }
                        }
                    }
                }
                if (!dropped.isEmpty()) {
                    for (OSUProvider osuProvider : dropped) {
                        if (mOSUMap.remove(osuProvider) != null) {
                            change = true;
                        }
                    }
                }

                if (anyValid) {
                    mCachedOSUMap.put(bssid, osuProviders);
                    mScanHistory.put(bssid, new AtomicInteger(1));
                }
            }

            // Scan results tend to disappear intermittently from scan batches.
            // To demote OSUs coming and going, mScanHistory contains historical OSU information and
            // is augmented by mScanHistory that is a parallel map that contains a "retention count"
            // per BSSID.
            // Missing scan results are removed from the cached map only after MAX_SCAN_MISSES.

            Iterator<Map.Entry<Long, HSOsuProvidersElement>> entries =
                    mCachedOSUMap.entrySet().iterator();

            while (entries.hasNext()) {
                Map.Entry<Long, HSOsuProvidersElement> entry = entries.next();
                long bssid = entry.getKey();
                if (!mTransientOSUMap.containsKey(bssid)) {
                    AtomicInteger skipCount = mScanHistory.get(bssid);
                    if (skipCount.incrementAndGet() > MAX_SCAN_MISSES) {
                        mScanHistory.remove(bssid);
                        for (OSUProvider osuProvider : entry.getValue().getProviders()) {
                            mOSUMap.remove(osuProvider);
                        }
                        entries.remove();
                        change = true;
                        Log.d(TAG, "Removed cached OSU provider " + entry.getValue());
                    }
                }
            }

            mTransientOSUMap.clear();
            mLastScanBatches.addFirst(new HashMap<>(mLastScanBatch));
            if (mLastScanBatches.size() > SCAN_BATCH_HISTORY) {
                mLastScanBatches.removeLast();
            }
            mLastScanBatch.clear();
            mScanComplete = false;

            if (change) {
                mOSUSSIDs.clear();
                for (OSUInfo osuInfo : mOSUMap.values()) {
                    mOSUSSIDs.add(osuInfo.getSSID());
                }
            }

            if (mIconQueryEnable) {
                initiateIconQueries();
            }
            osuCount = mOSUMap.size();
        }

        if (change) {
            List<OSUListener> listeners;
            synchronized (mOSUListeners) {
                listeners = new ArrayList<>(mOSUListeners.keySet());
            }
            Log.d(TAG, "OSU Update: " + mOSUMap);
            for (OSUListener listener : listeners) {
                listener.osuNotification(osuCount);
            }
        }
    }

    private void initiateIconQueries() {
        for (OSUInfo osuInfo : mOSUMap.values()) {
            if (osuInfo.getIconStatus() == OSUInfo.IconStatus.NotQueried) {
                mIconCache.startIconQuery(osuInfo,
                        osuInfo.getIconInfo(Locale, IconTypes, IconWidth, IconHeight));
            }
        }
    }

    public void tickleIconCache(boolean all) {
        mIconCache.tickle(all);
    }

    public void enableIconQuery(boolean on) {
        mIconQueryEnable = on;
        if (mIconQueryEnable) {
            initiateIconQueries();
        }
    }

    public SupplicantBridge getSupplicantBridge() {
        return mSupplicantBridge;
    }

    public void setActiveNetwork(WifiConfiguration wifiConfiguration) {
        long bssid;
        String url;
        synchronized (mRemediationLock) {
            mActiveConfig = wifiConfiguration;
            bssid = mRemediationBSSID;
            url = mRemediationURL;
            if (wifiConfiguration != null) {
                mRemediationBSSID = 0L;
                mRemediationURL = null;
            }
        }
        if (bssid != 0L && url != null && wifiConfiguration != null) {
            try {
                wnmRemediate(bssid, url, wifiConfiguration);
            } catch (IOException ioe) {
                Log.w(TAG, "Failed to execute remediation request: " + ioe.getMessage());
            }
        }
        /*
        // !!! Hack to force start remediation at connection time
        else if (wifiConfiguration != null && wifiConfiguration.isPasspoint()) {
            HomeSP homeSP = mWifiConfigStore.getHomeSPForConfig(wifiConfiguration);
            if (homeSP != null && homeSP.getSubscriptionUpdate() != null) {
                if (!mServiceThreads.containsKey(homeSP.getFQDN())) {
                    try {
                        remediate(homeSP);
                    } catch (IOException ioe) {
                        Log.w(TAG, "Failed to remediate: " + ioe);
                    }
                }
            }
        }
        */
        else if (wifiConfiguration == null) {
            mServiceThreads.clear();
        }
    }

    public void wnmReceived(String event){
        try {
            Log.d(TAG, "Received WNM: " + event);
            decodeWnmFrame(event);
        }
        catch (IOException | NumberFormatException e) {
            Log.w(TAG, "Bad WNM event '" + event + "': " + e.toString());
        }
    }

    public void decodeWnmFrame(String event) throws IOException {
        // %012x HS20-SUBSCRIPTION-REMEDIATION "%u %s", osu_method, url
        // %012x HS20-DEAUTH-IMMINENT-NOTICE "%u %u %s", code, reauth_delay, url

        String[] segments = event.split(" ");
        if (segments.length < 2) {
            throw new IOException("Short event");
        }

        long bssid = Long.parseLong(segments[0], 16);

        switch (segments[1]) {
            case WifiMonitor.HS20_SUB_REM_STR: {
                if (segments.length != 4) {
                    throw new IOException("Expected 4 segments");
                }
                int protoID = Integer.parseInt(segments[2]);
                if (protoID >= OSUProvider.OSUMethod.values().length || protoID < 0) {
                    throw new IOException("Unknown OSU Method");
                }
                OSUProvider.OSUMethod method = OSUProvider.OSUMethod.values()[protoID];
                if (method != OSUProvider.OSUMethod.SoapXml) {
                    throw new IOException(method + " is not supported");
                }

                String url = segments[3];

                WifiConfiguration current;
                synchronized (mRemediationLock) {
                    current = mActiveConfig;
                    if (current == null) {
                        mRemediationBSSID = bssid;
                        mRemediationURL = url;
                    }
                    else {
                        mRemediationBSSID = 0L;
                        mRemediationURL = null;
                    }
                }
                Log.d(TAG, String.format(
                        "Subscription remediation %012x using %s to '%s', current %s",
                        bssid, method, url, current != null ? current.SSID : "-"));
                if (current != null) {
                    try {
                        wnmRemediate(bssid, url, current);
                    } catch (IOException ioe) {
                        Log.w(TAG, "Failed to execute remediation request: " + ioe.getMessage());
                    }
                }
                break;
            }
            case WifiMonitor.HS20_DEAUTH_STR: {
                if (segments.length != 5) {
                    throw new IOException("Expected 5 segments");
                }
                int codeID = Integer.parseInt(segments[2]);
                if (codeID < 0 || codeID > 1) {
                    throw new IOException("Unknown code");
                }
                boolean ess = codeID == 1;  // Otherwise BSS
                int delay = Integer.parseInt(segments[3]);
                String url = segments[4];
                Log.d(TAG, String.format("De-auth imminent on %s, delay %ss to '%s'",
                        ess ? "ess" : "bss",
                        delay,
                        url));
                mWifiNetworkAdapter.setHoldoffTime(delay * Constants.MILLIS_IN_A_SEC, ess);
                mUserInputListener.deAuthNotification(ess, new URL(url));
                break;
            }
            default:
                throw new IOException("Unknown event type");
        }
    }

    private void wnmRemediate(long bssid, String url, WifiConfiguration config) throws IOException {
        HomeSP homeSP = mWifiConfigStore.getHomeSPForConfig(config);
        if (homeSP == null) {
            throw new IOException("Remediation request for unidentified Passpoint network " +
                    config.networkId);
        }
        Network network = mWifiNetworkAdapter.getCurrentNetwork();
        if (network == null) {
            throw new IOException("Failed to determine current network");
        }
        WifiInfo wifiInfo = mWifiNetworkAdapter.getConnectionInfo();
        if (wifiInfo == null || Utils.parseMac(wifiInfo.getBSSID()) != bssid) {
            throw new IOException("Mismatching BSSID");
        }
        Log.d(TAG, "WNM Remediation on " + network.netId + " FQDN " + homeSP.getFQDN());

        doRemediate(url, network, homeSP, false);
    }

    public void remediate(HomeSP homeSP, boolean policy) throws IOException {
        UpdateInfo updateInfo;
        if (policy) {
            if (homeSP.getPolicy() == null) {
                throw new IOException("No policy object");
            }
            updateInfo = homeSP.getPolicy().getPolicyUpdate();
        }
        else {
            updateInfo = homeSP.getSubscriptionUpdate();
        }
        switch (updateInfo.getUpdateRestriction()) {
            case HomeSP: {
                Network network = mWifiNetworkAdapter.getCurrentNetwork();
                if (network == null) {
                    throw new IOException("Failed to determine current network");
                }

                WifiConfiguration config = mActiveConfig;
                if (config == null) {
                    throw new IOException("No network association, cannot remediate at this time");
                }

                HomeSP activeSP = mWifiConfigStore.getHomeSPForConfig(config);

                if (activeSP == null || !activeSP.getFQDN().equals(homeSP.getFQDN())) {
                    throw new IOException("Remediation restricted to HomeSP");
                }
                doRemediate(updateInfo.getURI(), network, homeSP, policy);
                break;
            }
            case RoamingPartner: {
                Network network = mWifiNetworkAdapter.getCurrentNetwork();
                if (network == null) {
                    throw new IOException("Failed to determine current network");
                }

                WifiInfo wifiInfo = mWifiNetworkAdapter.getConnectionInfo();
                if (wifiInfo == null) {
                    throw new IOException("Unable to determine WiFi info");
                }
                long bssid = Utils.parseMac(wifiInfo.getBSSID());
                NetworkDetail networkDetail = null;
                for (Map<Long, NetworkDetail> map : mLastScanBatches) {
                    networkDetail = map.get(bssid);
                    if (networkDetail != null) {
                        break;
                    }
                }
                if (networkDetail == null) {
                    throw new IOException("Failed to find information for current network");
                }
                Map<Constants.ANQPElementType, ANQPElement> anqpData =
                        networkDetail.getANQPElements();
                if (anqpData == null || !anqpData.containsKey(ANQPDomName)) {
                    anqpData = mWifiConfigStore.getANQPData(networkDetail);
                }
                if (anqpData == null || !anqpData.containsKey(ANQPDomName)) {
                    throw new IOException("Can't determine current network capabilities");
                }
                PasspointMatch match = homeSP.match(networkDetail, anqpData,
                        mWifiConfigStore.getSIMAccessor());
                if (match == PasspointMatch.HomeProvider ||
                        match == PasspointMatch.RoamingProvider) {
                    doRemediate(updateInfo.getURI(), network, homeSP, policy);
                } else {
                    throw new IOException("No roaming network match: " + match);
                }
                break;
            }
            case Unrestricted: {
                ConnectivityService connService = (ConnectivityService)
                        mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network network = connService.getActiveNetwork();

                doRemediate(updateInfo.getURI(), network, homeSP, policy);
                break;
            }
        }
    }

    private void doRemediate(String url, Network network, HomeSP homeSP, boolean policy)
            throws IOException {
        synchronized (mWifiNetworkAdapter) {
            OSUThread existing = mServiceThreads.get(homeSP.getFQDN());
            if (existing != null) {
                if (System.currentTimeMillis() - existing.getLaunchTime() > REMEDIATION_TIMEOUT) {
                    throw new IOException("Ignoring recurring remediation request");
                }
                else {
                    existing.connect(null);
                }
            }

            try {
                OSUThread osuThread = new OSUThread(url, this,
                        getKeyManager(mWifiNetworkAdapter.getWifiConfig(homeSP)),
                        homeSP, policy ? FlowType.Policy : FlowType.Remediation);
                osuThread.start();
                osuThread.connect(network);
                mServiceThreads.put(homeSP.getFQDN(), osuThread);
            }
            catch (MalformedURLException me) {
                throw new IOException("Failed to start remediation: " + me);
            }
        }
    }

    public MOTree getMOTree(HomeSP homeSP) throws IOException {
        return mMOManager.getMOTree(homeSP);
    }

    public void notifyIconReceived(IconEvent iconEvent) {
        mIconCache.notifyIconReceived(iconEvent);
    }

    public void notifyIconFailed(long bssid) {
        mIconCache.notifyIconFailed(bssid);
    }

    public void iconResult(OSUInfo osuInfo) {

    }

    // SCAN_RESULTS_AVAILABLE_ACTION

    protected URL prepareUserInput() throws IOException {
        mRedirectListener = new RedirectListener(this);
        return mRedirectListener.getURL();
    }

    protected boolean startUserInput(URL target, Network network) throws IOException {
        mRedirectListener.startService();
        mUserInputListener.requestUserInput(target, network, mRedirectListener.getURL());
        return mRedirectListener.waitForUser();
    }

    public String notifyUser(OSUOperationStatus status, String message) {
        return mUserInputListener.operationStatus(status, message);
    }

    public void provisioningFailed(String message, HomeSP homeSP, FlowType flowType) {
        synchronized (mWifiNetworkAdapter) {
            switch (flowType) {
                case Provisioning:
                    mProvisioningThread = null;
                    if (mRedirectListener != null) {
                        mRedirectListener.abort();
                        mRedirectListener = null;
                    }
                    break;
                case Remediation:
                    mServiceThreads.remove(homeSP.getFQDN());
                    if (mServiceThreads.isEmpty() && mRedirectListener != null) {
                        mRedirectListener.abort();
                        mRedirectListener = null;
                    }
                    break;
            }
        }
        mUserInputListener.operationStatus(OSUOperationStatus.ProvisioningFailure, message);
    }

    public void provisioningComplete(MOData moData, Map<OSUCertType, List<X509Certificate>> certs,
                                     PrivateKey privateKey, Network osuNetwork) {
        synchronized (mWifiNetworkAdapter) {
            mProvisioningThread = null;
        }
        try {
            HomeSP homeSP = mMOManager.addSP(moData.getMOTree(), this);

            WifiConfiguration config = ConfigBuilder.buildConfig(homeSP,
                    certs.get(OSUCertType.AAA).iterator().next(),
                    certs.get(OSUCertType.Client), privateKey);

            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            int nwkId = wifiManager.addNetwork(config);
            boolean saved = false;
            if (nwkId >= 0) {
                saved = wifiManager.saveConfiguration();
            }
            Log.d(TAG, "Wifi configuration " + nwkId + " " + (saved ? "saved" : "not saved"));

            if (!saved) {
                mUserInputListener.operationStatus(OSUOperationStatus.ProvisioningFailure,
                        "Failed to save network configuration " + nwkId);
                mMOManager.removeSP(homeSP.getFQDN(), this);
                mWifiNetworkAdapter.detachOSUNetwork(osuNetwork,
                        WifiConfiguration.INVALID_NETWORK_ID);
                return;
            }

            mUserInputListener.operationStatus(OSUOperationStatus.ProvisioningSuccess, null);

            mWifiNetworkAdapter.detachOSUNetwork(osuNetwork, nwkId);

            Log.d(TAG, "Done, done.");
            Log.d(TAG, "Private key: " + privateKey);
            for (Map.Entry<OSUCertType, List<X509Certificate>> certEntry : certs.entrySet()) {
                StringBuilder sb = new StringBuilder();
                sb.append(certEntry.getKey()).append('\n');
                for (X509Certificate cert : certEntry.getValue()) {
                    sb.append("   issued by ").append(cert.getIssuerX500Principal()).
                            append(" to ").append(cert.getSubjectX500Principal()).append('\n');
                }
                Log.d(TAG, sb.toString());
            }
        }
        catch (IOException | GeneralSecurityException e) {
            mUserInputListener.operationStatus(OSUOperationStatus.ProvisioningFailure, e.toString());
        }
    }

    public void remediationComplete(HomeSP homeSP, Collection<MOData> mods,
                                    Map<OSUCertType, List<X509Certificate>> certs,
                                    PrivateKey privateKey)
            throws IOException, GeneralSecurityException {

        HomeSP altSP = mMOManager.modifySP(homeSP, mods, this);
        X509Certificate caCert = null;
        List<X509Certificate> clientCerts = null;
        if (certs != null) {
            List<X509Certificate> certList = certs.get(OSUCertType.AAA);
            caCert = certList != null && !certList.isEmpty() ? certList.iterator().next() : null;
            clientCerts = certs.get(OSUCertType.Client);
        }
        if (altSP != null || certs != null) {
            if (altSP == null) {
                altSP = homeSP;     // No MO mods, only certs and key
            }
            mWifiNetworkAdapter.updateNetwork(altSP, caCert, clientCerts, privateKey);
        }
        mUserInputListener.operationStatus(OSUOperationStatus.ProvisioningSuccess, null);
    }

    /*
        TLS Server cert (from handshake):
                Issuer: C=US, O=WFA Hotspot 2.0, CN=Hotspot 2.0 Trust Root CA - 01
                Subject: C=US, O=NetworkFX, Inc., OU=OSU CA - 01, CN=NetworkFX, Inc. Hotspot 2.0 Intermediate CA
                X509v3 extensions:
                    X509v3 Key Usage: critical
                        Certificate Sign, CRL Sign
                    Authority Information Access:
                        OCSP - URI:http://wfa-ocsp.symauth.com
                    X509v3 Basic Constraints: critical
                        CA:TRUE, pathlen:0
                    X509v3 Subject Alternative Name:
                        DirName:/CN=SymantecPKI-1-557
                    X509v3 Subject Key Identifier:
                        5D:0A:92:01:8F:4D:3F:05:11:5D:1F:1D:25:2A:19:49:A7:EE:07:EA
                    X509v3 Authority Key Identifier:
                        keyid:F9:18:C6:55:96:DE:6E:3A:73:10:F7:ED:85:A4:CB:A9:BB:D7:32:21


        AAA:
                Issuer: C=US, ST=California, L=Santa Clara, O=Wi-Fi Alliance, CN=WFA Root Certificate/emailAddress=support@wi-fi.org
                Subject: C=US, ST=California, L=Santa Clara, O=Wi-Fi Alliance, CN=WFA Root Certificate/emailAddress=support@wi-fi.org
                X509v3 extensions:
                    X509v3 Subject Key Identifier:
                        0B:03:C2:3E:54:A2:28:BD:3E:49:DE:72:F1:5F:8E:AB:0E:97:67:82
                    X509v3 Authority Key Identifier:
                        keyid:0B:03:C2:3E:54:A2:28:BD:3E:49:DE:72:F1:5F:8E:AB:0E:97:67:82
                    X509v3 Basic Constraints:
                        CA:TRUE

        CA:
                Issuer: C=US, ST=California, L=Santa Clara, O=Wi-Fi Alliance, CN=WFA Root Certificate/emailAddress=support@wi-fi.org
                Subject: C=US, ST=California, L=Santa Clara, O=Wi-Fi Alliance, CN=WFA Root Certificate/emailAddress=support@wi-fi.org
                X509v3 extensions:
                    X509v3 Subject Key Identifier:
                        0B:03:C2:3E:54:A2:28:BD:3E:49:DE:72:F1:5F:8E:AB:0E:97:67:82
                    X509v3 Authority Key Identifier:
                        keyid:0B:03:C2:3E:54:A2:28:BD:3E:49:DE:72:F1:5F:8E:AB:0E:97:67:82
                    X509v3 Basic Constraints:
                        CA:TRUE

        Client (for EAP-TLS):
                Issuer: CN=RuckusCA, O=Ruckus, C=IL
                Subject: CN=ccbba6cd-ecf4-47f3-85b4-fc85c2f6c5d1, O=Google, C=US
                X509v3 extensions:
                    X509v3 Subject Key Identifier:
                        63:7C:DE:C6:FB:C4:F5:8B:38:D4:16:E2:BD:0C:58:78:E7:74:63:DC
                    X509v3 Basic Constraints: critical
                        CA:FALSE
                    X509v3 Authority Key Identifier:
                        keyid:C8:A3:66:47:55:A4:2C:3A:4F:42:23:75:F0:66:2B:6A:0B:E8:47:DE
                    X509v3 Key Usage: critical
                        Digital Signature, Non Repudiation, Key Encipherment
                    X509v3 Extended Key Usage:
                        TLS Web Client Authentication, E-mail Protection

        Policy:
                Issuer: C=US, O=WFA Hotspot 2.0, CN=Hotspot 2.0 Trust Root CA - 01
                Validity
                Subject: C=US, O=WFA Hotspot 2.0, CN=Hotspot 2.0 Trust Root CA - 01
                X509v3 extensions:
                    X509v3 Basic Constraints: critical
                        CA:TRUE
                    X509v3 Key Usage: critical
                        Certificate Sign, CRL Sign
                    X509v3 Subject Key Identifier:
                        F9:18:C6:55:96:DE:6E:3A:73:10:F7:ED:85:A4:CB:A9:BB:D7:32:21

        Remediation:
                Issuer: C=US, O=WFA Hotspot 2.0, CN=Hotspot 2.0 Trust Root CA - 01
                Subject: C=US, O=WFA Hotspot 2.0, CN=Hotspot 2.0 Trust Root CA - 01
                X509v3 extensions:
                    X509v3 Basic Constraints: critical
                        CA:TRUE
                    X509v3 Key Usage: critical
                        Certificate Sign, CRL Sign
                    X509v3 Subject Key Identifier:
                        F9:18:C6:55:96:DE:6E:3A:73:10:F7:ED:85:A4:CB:A9:BB:D7:32:21
     */

    protected OMADMAdapter getOMADMAdapter() {
        return OMADMAdapter.getInstance(mContext);
    }
}
