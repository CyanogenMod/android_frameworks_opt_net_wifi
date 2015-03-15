package com.android.server.wifi.hotspot2;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.HSConnectionCapabilityElement;
import com.android.server.wifi.anqp.HSWanMetricsElement;
import com.android.server.wifi.anqp.IPAddressTypeAvailabilityElement;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import static com.android.server.wifi.anqp.Constants.ANQPElementType;
import static com.android.server.wifi.anqp.IPAddressTypeAvailabilityElement.IPv4Availability;
import static com.android.server.wifi.anqp.IPAddressTypeAvailabilityElement.IPv6Availability;

public class PasspointMatchInfo implements Comparable<PasspointMatchInfo> {
    private final PasspointMatch mPasspointMatch;
    private final NetworkDetail mNetworkDetail;
    private final HomeSP mHomeSP;

    private static final Map<NetworkDetail.Ant, Integer> sAntScores = new HashMap<>();

    static {
        sAntScores.put(NetworkDetail.Ant.FreePublic, 7);
        sAntScores.put(NetworkDetail.Ant.ChargeablePublic, 6);
        sAntScores.put(NetworkDetail.Ant.PrivateWithGuest, 5);
        sAntScores.put(NetworkDetail.Ant.Private, 4);
        sAntScores.put(NetworkDetail.Ant.Personal, 3);
        sAntScores.put(NetworkDetail.Ant.EmergencyOnly, 2);
        sAntScores.put(NetworkDetail.Ant.Wildcard, 1);
        sAntScores.put(NetworkDetail.Ant.TestOrExperimental, 0);
    }

    public PasspointMatchInfo(PasspointMatch passpointMatch,
                              NetworkDetail networkDetail, HomeSP homeSP) {
        mPasspointMatch = passpointMatch;
        mNetworkDetail = networkDetail;
        mHomeSP = homeSP;
    }

    public PasspointMatch getPasspointMatch() {
        return mPasspointMatch;
    }

    public NetworkDetail getNetworkDetail() {
        return mNetworkDetail;
    }

    public HomeSP getHomeSP() {
        return mHomeSP;
    }

    @Override
    public int compareTo(PasspointMatchInfo that) {
        if (getPasspointMatch() != that.getPasspointMatch()) {
            return getPasspointMatch().compareTo(that.getPasspointMatch());
        }

        // IP Address Type Availability (802.11)

        NetworkDetail n1 = getNetworkDetail();
        NetworkDetail n2 = that.getNetworkDetail();

        if (n1.isInternet() != n2.isInternet()) {
            return n1.isInternet() ? 1 : -1;
        }

        if (n1.hasInterworking() && n2.hasInterworking() && n1.getAnt() != n2.getAnt()) {
            int an1 = sAntScores.get(n1.getAnt());
            int an2 = sAntScores.get(n2.getAnt());
            return an1 - an2;
        }

        long score1 = n1.getCapacity() * n1.getChannelUtilization() * n1.getStationCount();
        long score2 = n2.getCapacity() * n2.getChannelUtilization() * n2.getStationCount();

        if (score1 != score2) {
            return score1 < score2 ? 1 : -1;
        }

        int comp = subCompare(n1.getANQPElements(), n2.getANQPElements());
        if (comp != 0) {
            return comp;
        }

        return Utils.compare(n1.getHSRelease(), n2.getHSRelease());
    }

    private static final Map<IPv4Availability, Integer> sIP4Scores =
            new EnumMap<>(IPv4Availability.class);
    private static final Map<IPv6Availability, Integer> sIP6Scores =
            new EnumMap<>(IPv6Availability.class);

    private static final Map<Integer, Map<Integer, Integer>> sPortScores = new HashMap<>();

    private static final int IPPROTO_ICMP = 1;
    private static final int IPPROTO_TCP = 6;
    private static final int IPPROTO_UDP = 17;
    private static final int IPPROTO_ESP = 50;

    static {
        sIP4Scores.put(IPv4Availability.NotAvailable, 0);
        sIP4Scores.put(IPv4Availability.PortRestricted, 1);
        sIP4Scores.put(IPv4Availability.PortRestrictedAndSingleNAT, 1);
        sIP4Scores.put(IPv4Availability.PortRestrictedAndDoubleNAT, 1);
        sIP4Scores.put(IPv4Availability.Unknown, 1);
        sIP4Scores.put(IPv4Availability.Public, 2);
        sIP4Scores.put(IPv4Availability.SingleNAT, 2);
        sIP4Scores.put(IPv4Availability.DoubleNAT, 2);

        sIP6Scores.put(IPv6Availability.NotAvailable, 0);
        sIP6Scores.put(IPv6Availability.Reserved, 1);
        sIP6Scores.put(IPv6Availability.Unknown, 1);
        sIP6Scores.put(IPv6Availability.Available, 2);

        Map<Integer, Integer> tcpMap = new HashMap<>();
        tcpMap.put(20, 1);
        tcpMap.put(21, 1);
        tcpMap.put(22, 3);
        tcpMap.put(23, 2);
        tcpMap.put(25, 8);
        tcpMap.put(26, 8);
        tcpMap.put(53, 3);
        tcpMap.put(80, 10);
        tcpMap.put(110, 6);
        tcpMap.put(143, 6);
        tcpMap.put(443, 10);
        tcpMap.put(993, 6);
        tcpMap.put(1723, 7);

        Map<Integer, Integer> udpMap = new HashMap<>();
        udpMap.put(53, 10);
        udpMap.put(500, 7);
        udpMap.put(5060, 10);
        udpMap.put(4500, 4);

        sPortScores.put(IPPROTO_TCP, tcpMap);
        sPortScores.put(IPPROTO_UDP, udpMap);
    }

    private int subCompare(Map<Constants.ANQPElementType, ANQPElement> anqp1,
                           Map<Constants.ANQPElementType, ANQPElement> anqp2) {
        if (anqp1 == null || anqp2 == null) {
            return 0;
        }

        int cmp;

        HSWanMetricsElement w1 = (HSWanMetricsElement) anqp1.get(ANQPElementType.HSWANMetrics);
        HSWanMetricsElement w2 = (HSWanMetricsElement) anqp2.get(ANQPElementType.HSWANMetrics);
        if (w1 != null && w2 != null) {

            boolean u1 = w1.getStatus() == HSWanMetricsElement.LinkStatus.Up;
            boolean u2 = w2.getStatus() == HSWanMetricsElement.LinkStatus.Up;
            cmp = Boolean.compare(u1, u2);
            if (cmp != 0) {
                return cmp;
            }

            cmp = Boolean.compare(w1.isCapped(), w2.isCapped());
            if (cmp != 0) {
                return cmp;
            }

            long bw1 = (w1.getDlSpeed() * (long)w1.getDlLoad()) * 8 +
                       (w1.getUlSpeed() * (long)w1.getUlLoad()) * 2;
            long bw2 = (w2.getDlSpeed() * (long)w2.getDlLoad()) * 8 +
                       (w2.getUlSpeed() * (long)w2.getUlLoad()) * 2;
            cmp = Long.compare(bw1, bw2);
            if (cmp != 0) {
                return cmp;
            }
        }

        IPAddressTypeAvailabilityElement a1 =
                (IPAddressTypeAvailabilityElement)anqp1.get(ANQPElementType.ANQPIPAddrAvailability);
        IPAddressTypeAvailabilityElement a2 =
                (IPAddressTypeAvailabilityElement)anqp2.get(ANQPElementType.ANQPIPAddrAvailability);
        if (a1 != null && a2 != null) {
            Integer as14 = sIP4Scores.get(a1.getV4Availability());
            Integer as16 = sIP6Scores.get(a1.getV6Availability());
            Integer as24 = sIP4Scores.get(a2.getV4Availability());
            Integer as26 = sIP6Scores.get(a2.getV6Availability());
            as14 = as14 != null ? as14 : 1;
            as16 = as16 != null ? as16 : 1;
            as24 = as24 != null ? as24 : 1;
            as26 = as26 != null ? as26 : 1;
            // Is IPv4 twice as important as IPv6???
            int s1 = as14 * 2 + as16;
            int s2 = as24 * 2 + as26;
            cmp = Integer.compare(s1, s2);
            if (cmp != 0) {
                return cmp;
            }
        }

        HSConnectionCapabilityElement cc1 =
                (HSConnectionCapabilityElement) anqp1.get(ANQPElementType.HSConnCapability);
        HSConnectionCapabilityElement cc2 =
                (HSConnectionCapabilityElement) anqp2.get(ANQPElementType.HSConnCapability);

        if (cc1 != null && cc2 != null) {
            cmp = Integer.compare(protoScore(cc1), protoScore(cc2));
            if (cmp != 0) {
                return cmp;
            }
        }

        return 0;
    }

    private static int protoScore(HSConnectionCapabilityElement cce) {
        int score = 0;
        for (HSConnectionCapabilityElement.ProtocolTuple tuple : cce.getStatusList()) {
            int sign = tuple.getStatus() == HSConnectionCapabilityElement.ProtoStatus.Open ?
                    1 : -1;

            int elementScore = 1;
            if (tuple.getProtocol() == IPPROTO_ICMP) {
                elementScore = 1;
            }
            else if (tuple.getProtocol() == IPPROTO_ESP) {
                elementScore = 5;
            }
            else {
                Map<Integer, Integer> protoMap = sPortScores.get(tuple.getProtocol());
                if (protoMap != null) {
                    Integer portScore = protoMap.get(tuple.getPort());
                    elementScore = portScore != null ? portScore : 0;
                }
            }
            score += elementScore * sign;
        }
        return score;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        PasspointMatchInfo that = (PasspointMatchInfo)thatObject;

        return getNetworkDetail().equals(that.getNetworkDetail()) &&
                getHomeSP().equals(that.getHomeSP()) &&
                getPasspointMatch().equals(that.getPasspointMatch());
    }

    @Override
    public int hashCode() {
        int result = mPasspointMatch != null ? mPasspointMatch.hashCode() : 0;
        result = 31 * result + mNetworkDetail.hashCode();
        result = 31 * result + (mHomeSP != null ? mHomeSP.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "PasspointMatchInfo{" +
                ", mPasspointMatch=" + mPasspointMatch +
                ", mNetworkInfo=" + mNetworkDetail.getSSID() +
                ", mHomeSP=" + mHomeSP.getFQDN() +
                '}';
    }
}
