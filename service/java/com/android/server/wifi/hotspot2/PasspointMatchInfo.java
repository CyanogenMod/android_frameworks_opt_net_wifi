package com.android.server.wifi.hotspot2;

import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.util.HashMap;
import java.util.Map;

public class PasspointMatchInfo implements Comparable<PasspointMatchInfo> {
    private final PasspointMatch mPasspointMatch;
    private final NetworkDetail mNetworkDetail;
    private final HomeSP mHomeSP;

    private static final Map<NetworkDetail.Ant, Integer> sAntScores =
            new HashMap<NetworkDetail.Ant, Integer>();

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

        // WAN Metrics
        // IP Address Type Availability (802.11)
        // Connection capability

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

        return Utils.compare(n1.getHSRelease(), n2.getHSRelease());
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
