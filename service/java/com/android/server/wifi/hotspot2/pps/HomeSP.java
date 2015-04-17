package com.android.server.wifi.hotspot2.pps;

import android.util.Log;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.CellularNetwork;
import com.android.server.wifi.anqp.DomainNameElement;
import com.android.server.wifi.anqp.NAIRealmData;
import com.android.server.wifi.anqp.NAIRealmElement;
import com.android.server.wifi.anqp.RoamingConsortiumElement;
import com.android.server.wifi.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.AuthMatch;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.android.server.wifi.anqp.Constants.ANQPElementType;

public class HomeSP {
    private final Map<String, Long> mSSIDs;        // SSID, HESSID, [0,N]
    private final String mFQDN;
    private final DomainMatcher mDomainMatcher;
    private final Set<String> mOtherHomePartners;
    private final HashSet<Long> mRoamingConsortiums;    // [0,N]
    private final Set<Long> mMatchAnyOIs;           // [0,N]
    private final List<Long> mMatchAllOIs;          // [0,N]

    private final Credential mCredential;

    // Informational:
    private final String mFriendlyName;             // [1]
    private final String mIconURL;                  // [0,1]

    public HomeSP(Map<String, Long> ssidMap,
                   /*@NotNull*/ String fqdn,
                   /*@NotNull*/ HashSet<Long> roamingConsortiums,
                   /*@NotNull*/ Set<String> otherHomePartners,
                   /*@NotNull*/ Set<Long> matchAnyOIs,
                   /*@NotNull*/ List<Long> matchAllOIs,
                   String friendlyName,
                   String iconURL,
                   Credential credential) {

        mSSIDs = ssidMap;
        List<List<String>> otherPartners = new ArrayList<List<String>>(otherHomePartners.size());
        for (String otherPartner : otherHomePartners) {
            otherPartners.add(Utils.splitDomain(otherPartner));
        }
        mOtherHomePartners = otherHomePartners;
        mFQDN = fqdn;
        mDomainMatcher = new DomainMatcher(Utils.splitDomain(fqdn), otherPartners);
        mRoamingConsortiums = roamingConsortiums;
        mMatchAnyOIs = matchAnyOIs;
        mMatchAllOIs = matchAllOIs;
        mFriendlyName = friendlyName;
        mIconURL = iconURL;
        mCredential = credential;
    }

    public PasspointMatch match(NetworkDetail networkDetail,
                                Map<ANQPElementType, ANQPElement> anqpElementMap,
                                List<String> imsis) {

        if (mSSIDs.containsKey(networkDetail.getSSID())) {
            Long hessid = mSSIDs.get(networkDetail.getSSID());
            if (hessid == null || networkDetail.getHESSID() == hessid) {
                Log.d("HS2J", "match SSID");
                return PasspointMatch.HomeProvider;
            }
        }

        Set<Long> anOIs = new HashSet<Long>();

        if (networkDetail.getRoamingConsortiums() != null) {
            for (long oi : networkDetail.getRoamingConsortiums()) {
                anOIs.add(oi);
            }
        }
        RoamingConsortiumElement rcElement = anqpElementMap != null ?
                (RoamingConsortiumElement) anqpElementMap.get(ANQPElementType.ANQPRoamingConsortium)
                : null;
        if (rcElement != null) {
            anOIs.addAll(rcElement.getOIs());
        }

        boolean authPossible = false;

        if (!mMatchAllOIs.isEmpty()) {
            boolean matchesAll = true;

            for (long spOI : mMatchAllOIs) {
                if (!anOIs.contains(spOI)) {
                    matchesAll = false;
                    break;
                }
            }
            if (matchesAll) {
                authPossible = true;
            }
            else {
                if (anqpElementMap != null || networkDetail.getAnqpOICount() == 0) {
                    return PasspointMatch.Declined;
                }
                else {
                    return PasspointMatch.Incomplete;
                }
            }
        }

        if (!authPossible &&
                (!Collections.disjoint(mMatchAnyOIs, anOIs) ||
                        !Collections.disjoint(mRoamingConsortiums, anOIs))) {
            authPossible = true;
        }

        if (anqpElementMap == null) {
            return PasspointMatch.Incomplete;
        }

        DomainNameElement domainNameElement =
                (DomainNameElement) anqpElementMap.get(ANQPElementType.ANQPDomName);
        NAIRealmElement naiRealmElement =
                (NAIRealmElement) anqpElementMap.get(ANQPElementType.ANQPNAIRealm);
        ThreeGPPNetworkElement threeGPPNetworkElement =
                (ThreeGPPNetworkElement) anqpElementMap.get(ANQPElementType.ANQP3GPPNetwork);

        if (domainNameElement != null) {
            for (String domain : domainNameElement.getDomains()) {
                List<String> anLabels = Utils.splitDomain(domain);
                DomainMatcher.Match match = mDomainMatcher.isSubDomain(anLabels);
                if (match != DomainMatcher.Match.None) {
                    return PasspointMatch.HomeProvider;
                }

                String mccMnc = Utils.getMccMnc(anLabels);
                if (mccMnc != null) {
                    for (String imsi : imsis) {
                        if (imsi.startsWith(mccMnc)) {
                            return PasspointMatch.HomeProvider;
                        }
                    }
                }
            }
        }

        if (!authPossible && naiRealmElement != null) {
            AuthMatch authMatch = matchRealms(naiRealmElement, threeGPPNetworkElement);
            if (authMatch != AuthMatch.None) {
                return PasspointMatch.RoamingProvider;
            }
        }

        return PasspointMatch.None;
    }

    private AuthMatch matchRealms(NAIRealmElement naiRealmElement,
                                  ThreeGPPNetworkElement threeGPPNetworkElement) {
        List<String> credRealm = Utils.splitDomain(mCredential.getRealm());

        for (NAIRealmData naiRealmData : naiRealmElement.getRealmData()) {

            DomainMatcher.Match match = DomainMatcher.Match.None;
            for (String anRealm : naiRealmData.getRealms()) {
                List<String> anRealmLabels = Utils.splitDomain(anRealm);
                match = mDomainMatcher.isSubDomain(anRealmLabels);
                if (match != DomainMatcher.Match.None) {
                    break;
                }
                if (DomainMatcher.arg2SubdomainOfArg1(credRealm, anRealmLabels)) {
                    match = DomainMatcher.Match.Secondary;
                    break;
                }
            }

            if (match != DomainMatcher.Match.None) {
                if (mCredential.getImsi() != null) {
                    // All the device has is one of EAP-SIM, AKA or AKA',
                    // so a 3GPP element must appear and contain a matching MNC/MCC
                    if (threeGPPNetworkElement == null) {
                        return AuthMatch.None;
                    }
                    for (CellularNetwork network : threeGPPNetworkElement.getPlmns()) {
                        if (network.matchIMSI(mCredential.getImsi())) {
                            AuthMatch authMatch =
                                    naiRealmData.matchEAPMethods(mCredential.getEAPMethod());
                            if (authMatch != AuthMatch.None) {
                                return authMatch;
                            }
                        }
                    }
                } else {
                    AuthMatch authMatch = naiRealmData.matchEAPMethods(mCredential.getEAPMethod());
                    if (authMatch != AuthMatch.None) {
                        // Note: Something more intelligent could be done here based on the
                        // authMatch value. It may be useful to have a secondary score to
                        // distinguish more predictable EAP method/parameter matching.
                        return authMatch;
                    }
                }
            }
        }
        return AuthMatch.None;
    }

    public String getFQDN() { return mFQDN; }
    public String getFriendlyName() { return mFriendlyName; }
    public HashSet<Long> getRoamingConsortiums() { return mRoamingConsortiums; }
    public Credential getCredential() { return mCredential; }

    public Map<String, Long> getSSIDs() {
        return mSSIDs;
    }

    public Collection<String> getOtherHomePartners() {
        return mOtherHomePartners;
    }

    public Set<Long> getMatchAnyOIs() {
        return mMatchAnyOIs;
    }

    public List<Long> getMatchAllOIs() {
        return mMatchAllOIs;
    }

    public String getIconURL() {
        return mIconURL;
    }

    public boolean deepEquals(HomeSP other) {
        return mFQDN.equals(other.mFQDN) &&
                mSSIDs.equals(other.mSSIDs) &&
                mOtherHomePartners.equals(other.mOtherHomePartners) &&
                mRoamingConsortiums.equals(other.mRoamingConsortiums) &&
                mMatchAnyOIs.equals(other.mMatchAnyOIs) &&
                mMatchAllOIs.equals(other.mMatchAllOIs) &&
                mFriendlyName.equals(other.mFriendlyName) &&
                Utils.compare(mIconURL, other.mIconURL) == 0 &&
                mCredential.equals(other.mCredential);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        } else if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        HomeSP that = (HomeSP) thatObject;
        return mFQDN.equals(that.mFQDN);
    }

    @Override
    public int hashCode() {
        return mFQDN.hashCode();
    }

    @Override
    public String toString() {
        return "HomeSP{" +
                "mSSIDs=" + mSSIDs +
                ", mFQDN='" + mFQDN + '\'' +
                ", mDomainMatcher=" + mDomainMatcher +
                ", mRoamingConsortiums={" + Utils.roamingConsortiumsToString(mRoamingConsortiums) +
                '}' +
                ", mMatchAnyOIs={" + Utils.roamingConsortiumsToString(mMatchAnyOIs) + '}' +
                ", mMatchAllOIs={" + Utils.roamingConsortiumsToString(mMatchAllOIs) + '}' +
                ", mCredential=" + mCredential +
                ", mFriendlyName='" + mFriendlyName + '\'' +
                ", mIconURL='" + mIconURL + '\'' +
                '}';
    }
}
