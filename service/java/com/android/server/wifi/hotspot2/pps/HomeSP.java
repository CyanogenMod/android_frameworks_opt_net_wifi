package com.android.server.wifi.hotspot2.pps;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.CellularNetwork;
import com.android.server.wifi.anqp.DomainNameElement;
import com.android.server.wifi.anqp.HSConnectionCapabilityElement;
import com.android.server.wifi.anqp.HSWanMetricsElement;
import com.android.server.wifi.anqp.IPAddressTypeAvailabilityElement;
import com.android.server.wifi.anqp.NAIRealmData;
import com.android.server.wifi.anqp.NAIRealmElement;
import com.android.server.wifi.anqp.RoamingConsortiumElement;
import com.android.server.wifi.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.AuthMatch;
import com.android.server.wifi.hotspot2.NetworkInfo;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.Utils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.android.server.wifi.anqp.Constants.ANQPElementType;

public class HomeSP {
    private final Map<String, String> mSSIDs;        // SSID, HESSID, [0,N]
    private final String mFQDN;
    private final DomainMatcher mDomainMatcher;
    private final Set<Long> mRoamingConsortiums;    // [0,N]
    private final Set<Long> mMatchAnyOIs;           // [0,N]
    private final List<Long> mMatchAllOIs;          // [0,N]

    private final Credential mCredential;

    // Informational:
    private final String mFriendlyName;             // [1]
    private final String mIconURL;                  // [0,1]

    public HomeSP(Map<String, String> ssidMap,
                   /*@NotNull*/ String fqdn,
                   /*@NotNull*/ Set<Long> roamingConsortiums,
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
        mFQDN = fqdn;
        mDomainMatcher = new DomainMatcher(Utils.splitDomain(fqdn), otherPartners);
        mRoamingConsortiums = roamingConsortiums;
        mMatchAnyOIs = matchAnyOIs;
        mMatchAllOIs = matchAllOIs;
        mFriendlyName = friendlyName;
        mIconURL = iconURL;
        mCredential = credential;
    }

    public PasspointMatch match(NetworkInfo networkInfo, List<ANQPElement> anqpElements) {

        if (mSSIDs.containsKey(networkInfo.getSSID())) {
            String hessid = mSSIDs.get(networkInfo.getSSID());
            if (hessid == null || networkInfo.getHESSID().equals(hessid)) {
                System.out.println("-- SSID");
                return PasspointMatch.HomeProvider;
            }
        }

        List<Long> allOIs = null;

        if (networkInfo.getRoamingConsortiums() != null) {
            allOIs = new ArrayList<Long>();
            for (long oi : networkInfo.getRoamingConsortiums()) {
                allOIs.add(oi);
            }
        }

        Map<ANQPElementType, ANQPElement> anqpElementMap = null;

        if (anqpElements != null) {
            anqpElementMap = new EnumMap<ANQPElementType, ANQPElement>(ANQPElementType.class);
            for (ANQPElement element : anqpElements) {
                anqpElementMap.put(element.getID(), element);
                if (element.getID() == ANQPElementType.ANQPRoamingConsortium) {
                    RoamingConsortiumElement rcElement = (RoamingConsortiumElement) element;
                    if (!rcElement.getOIs().isEmpty()) {
                        if (allOIs == null) {
                            allOIs = new ArrayList<Long>(rcElement.getOIs());
                        } else {
                            allOIs.addAll(rcElement.getOIs());
                        }
                    }
                }
            }
        }

        // !!! wlan.mnc<MNC>.mcc<MCC>.3gppnetwork.org

        if (allOIs != null) {
            if (!mRoamingConsortiums.isEmpty()) {
                for (long oi : allOIs) {
                    if (mRoamingConsortiums.contains(oi)) {
                        System.out.println("-- RC");
                        return PasspointMatch.HomeProvider;
                    }
                }
            }
            if (!mMatchAnyOIs.isEmpty() || !mMatchAllOIs.isEmpty()) {
                for (long anOI : allOIs) {

                    boolean oneMatchesAll = !mMatchAllOIs.isEmpty();

                    for (long spOI : mMatchAllOIs) {
                        if (spOI != anOI) {
                            oneMatchesAll = false;
                            break;
                        }
                    }

                    if (oneMatchesAll) {
                        System.out.println("-- 1inAll");
                        return PasspointMatch.HomeProvider;
                    }

                    if (mMatchAnyOIs.contains(anOI)) {
                        System.out.println("-- 1ofAll");
                        return PasspointMatch.HomeProvider;
                    }
                }
            }
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

        // For future policy decisions:
        IPAddressTypeAvailabilityElement ipAddressAvailabilityElement =
                (IPAddressTypeAvailabilityElement) anqpElementMap.get(
                        ANQPElementType.ANQPIPAddrAvailability);
        HSConnectionCapabilityElement hsConnCapElement =
                (HSConnectionCapabilityElement) anqpElementMap.get(
                        ANQPElementType.HSConnCapability);
        HSWanMetricsElement hsWanMetricsElement =
                (HSWanMetricsElement) anqpElementMap.get(ANQPElementType.HSWANMetrics);

        if (domainNameElement != null) {
            for (String domain : domainNameElement.getDomains()) {
                DomainMatcher.Match match = mDomainMatcher.isSubDomain(Utils.splitDomain(domain));
                if (match != DomainMatcher.Match.None) {
                    return PasspointMatch.HomeProvider;
                }
            }
        }

        if (naiRealmElement != null) {
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
                if (anRealmLabels.equals(credRealm)) {
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
    public Set<Long> getRoamingConsortiums() { return mRoamingConsortiums; }
    public Credential getCredential() { return mCredential; }

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
