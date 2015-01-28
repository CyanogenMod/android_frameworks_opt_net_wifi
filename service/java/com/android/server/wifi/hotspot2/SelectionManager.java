package com.android.server.wifi.hotspot2;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.ANQPFactory;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.android.server.wifi.anqp.Constants.ANQPElementType;

public class SelectionManager {
    private final List<HomeSP> mHomeSPs;
    private final Map<NetworkKey, ANQPData> mANQPCache;
    private final Map<NetworkKey, NetworkInfo> mPendingANQP;
    private final List<ScoredNetwork> mScoredNetworks;

    private static class NetworkKey {
        private final String mSSID;
        private final long mBSSID;
        private final int mANQPDomainID;

        public NetworkKey(String SSID, long BSSID, int ANQPDomainID) {
            mSSID = SSID;
            mBSSID = BSSID;
            mANQPDomainID = ANQPDomainID;
        }

        public String getSSID() {
            return mSSID;
        }

        public long getBSSID() {
            return mBSSID;
        }

        public int getANQPDomainID() {
            return mANQPDomainID;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            NetworkKey that = (NetworkKey) o;

            if (mANQPDomainID != that.mANQPDomainID) return false;
            if (mBSSID != that.mBSSID) return false;
            if (!mSSID.equals(that.mSSID)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = mSSID.hashCode();
            result = 31 * result + (int) (mBSSID ^ (mBSSID >>> 32));
            result = 31 * result + mANQPDomainID;
            return result;
        }

        @Override
        public String toString() {
            return String.format("<%s>:0x%012x id %d", mSSID, mBSSID, mANQPDomainID);
        }
    }

    private static class ScoredNetwork implements Comparable<ScoredNetwork> {
        private final PasspointMatch mMatch;
        private final NetworkInfo mNetworkInfo;

        private ScoredNetwork(PasspointMatch match, NetworkInfo networkInfo) {
            mMatch = match;
            mNetworkInfo = networkInfo;
            // !!! Further score on BSS Load, ANT, "Internet" and HSRelease
        }

        public PasspointMatch getMatch() {
            return mMatch;
        }

        public NetworkInfo getNetworkInfo() {
            return mNetworkInfo;
        }

        @Override
        public int compareTo(ScoredNetwork other) {
            if (getMatch() == other.getMatch()) {
                return 0;
            } else {
                return getMatch().ordinal() > other.getMatch().ordinal() ? 1 : -1;
            }
        }
    }

    public SelectionManager(List<HomeSP> homeSPs) {
        mHomeSPs = homeSPs;
        mANQPCache = new HashMap<NetworkKey, ANQPData>();
        mPendingANQP = new HashMap<NetworkKey, NetworkInfo>();
        mScoredNetworks = new ArrayList<ScoredNetwork>();
    }

    public Map<HomeSP, PasspointMatch> matchNetwork(NetworkInfo networkInfo) {
        NetworkKey networkKey = new NetworkKey(networkInfo.getSSID(), networkInfo.getBSSID(),
                networkInfo.getAnqpDomainID());
        ANQPData anqpData = mANQPCache.get(networkKey);
        List<ANQPElement> anqpElements = anqpData != null ? anqpData.getANQPElements() : null;

        Map<HomeSP, PasspointMatch> matches = new HashMap<HomeSP, PasspointMatch>(mHomeSPs.size());
        for (HomeSP homeSP : mHomeSPs) {
            PasspointMatch match = homeSP.match(networkInfo, anqpElements);

            if (match == PasspointMatch.Incomplete && networkInfo.isInterworking()) {
                mPendingANQP.put(networkKey, networkInfo);
            }
            matches.put(homeSP, match);
        }
        return matches;
    }

    public NetworkInfo findNetwork(NetworkInfo networkInfo) {

        NetworkKey networkKey = new NetworkKey(networkInfo.getSSID(), networkInfo.getBSSID(),
                networkInfo.getAnqpDomainID());
        ANQPData anqpData = mANQPCache.get(networkKey);
        List<ANQPElement> anqpElements = anqpData != null ? anqpData.getANQPElements() : null;
        for (HomeSP homeSP : mHomeSPs) {
            PasspointMatch match = homeSP.match(networkInfo, anqpElements);
            if (match == PasspointMatch.HomeProvider || match == PasspointMatch.RoamingProvider) {
                mScoredNetworks.add(new ScoredNetwork(match, networkInfo));
            } else if (match == PasspointMatch.Incomplete && networkInfo.getAnt() != null) {
                mPendingANQP.put(networkKey, networkInfo);
            }
        }

        // !!! Should really return a score-sorted list.
        Collections.sort(mScoredNetworks);
        if (!mScoredNetworks.isEmpty() &&
                mScoredNetworks.get(0).getMatch() == PasspointMatch.HomeProvider) {
            return mScoredNetworks.get(0).getNetworkInfo();
        } else {
            return null;
        }
    }

    public Map<HomeSP, PasspointMatch> notifyANQPResponse(NetworkInfo networkInfo,
                                                          List<ANQPElement> anqpElements) {
        NetworkKey networkKey = new NetworkKey(networkInfo.getSSID(), networkInfo.getBSSID(),
                networkInfo.getAnqpDomainID());
        mPendingANQP.remove(networkKey);
        mANQPCache.put(networkKey, new ANQPData(anqpElements));
        System.out.println("Caching " + networkKey);

        return matchNetwork(networkInfo);
    }

    private static final ANQPElementType[] BaseANQPSet = new ANQPElementType[]{
            ANQPElementType.ANQPVenueName,
            ANQPElementType.ANQPNwkAuthType,
            ANQPElementType.ANQPRoamingConsortium,
            ANQPElementType.ANQPIPAddrAvailability,
            ANQPElementType.ANQPNAIRealm,
            ANQPElementType.ANQP3GPPNetwork,
            ANQPElementType.ANQPDomName
    };

    private static final ANQPElementType[] HS20ANQPSet = new ANQPElementType[]{
            ANQPElementType.HSFriendlyName,
            ANQPElementType.HSWANMetrics,
            ANQPElementType.HSConnCapability
    };

    private static final Set<ANQPElementType> IWElements = new HashSet<ANQPElementType>();
    private static final Set<ANQPElementType> HS20Elements = new HashSet<ANQPElementType>();

    static {
        Collections.addAll(IWElements, BaseANQPSet);
        HS20Elements.addAll(IWElements);
        Collections.addAll(HS20Elements, HS20ANQPSet);
    }

    public ByteBuffer generateANQPQuery(NetworkInfo network, int dialogToken) {
        ByteBuffer request = ByteBuffer.allocate(1024);

        request.order(ByteOrder.LITTLE_ENDIAN);
        int lenPos = request.position();
        request.putShort((short) 0);

        if (network.getHSRelease() != null) {
            return prepRequest(lenPos, ANQPFactory.buildQueryRequest(HS20Elements, request));
        } else if (network.isInterworking()) {
            return prepRequest(lenPos, ANQPFactory.buildQueryRequest(IWElements, request));
        } else {
            return null;
        }
    }

    private static ByteBuffer prepRequest(int pos0, ByteBuffer request) {
        return request.putShort(pos0, (short) (request.limit() - pos0 - Constants.BYTES_IN_SHORT));
    }
}
