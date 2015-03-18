package com.android.server.wifi.hotspot2.omadm;

import android.util.Log;

import com.android.server.wifi.anqp.eap.EAP;
import com.android.server.wifi.anqp.eap.EAPMethod;
import com.android.server.wifi.anqp.eap.ExpandedEAPMethod;
import com.android.server.wifi.anqp.eap.InnerAuthEAP;
import com.android.server.wifi.anqp.eap.NonEAPInnerAuth;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.pps.Credential;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Handles provisioning of PerProviderSubscription data.
 */
public class MOManager {
    private final File mPpsFile;
    private final Map<String, HomeSP> mSPs;

    public MOManager(File ppsFile) throws IOException {
        mPpsFile = ppsFile;
        mSPs = new HashMap<>();
    }

    public File getPpsFile() {
        return mPpsFile;
    }

    public Map<String, HomeSP> getLoadedSPs() {
        return mSPs;
    }

    public List<HomeSP> loadAllSPs() throws IOException {
        List<MOTree> trees = new ArrayList<MOTree>();
        List<HomeSP> sps = new ArrayList<HomeSP>();

        if (!mPpsFile.exists()) {
            return sps;
        }

        BufferedInputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(mPpsFile));
            while (in.available() > 0) {
                MOTree tree = MOTree.unmarshal(in);
                if (tree != null) {
                    Log.d("PARSE-LOG", "adding tree no " + trees.size());
                    trees.add(tree);
                } else {
                    break;
                }
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ioe) {
                    /**/
                }
            }
        }

        Log.d("PARSE-LOG", "number of trees " + trees.size());
        for (MOTree moTree : trees) {
            Log.d("PARSE-LOG", "pasring a moTree");
            List<HomeSP> sp = buildSPs(moTree);
            if (sp != null) {
                Log.d("PARSE-LOG", "built " + sp.size() + " HomeSPs");
                sps.addAll(sp);
            } else {
                Log.d("PARSE-LOG", "failed to build HomeSP");
            }
        }

        Log.d("PARSE-LOG", "collected " + sps.size());
        for (HomeSP sp : sps) {
            Log.d("PARSE-LOG", "adding " + sp.getFQDN());
            if (mSPs.put(sp.getFQDN(), sp) != null) {
                Log.d("PARSE-LOG", "failed to add " + sp.getFQDN());
                throw new OMAException("Multiple SPs for FQDN '" + sp.getFQDN() + "'");
            } else {
                Log.d("PARSE-LOG", "added " + sp.getFQDN() + " to list");
            }
        }

        Log.d("PARSE-LOG", "found " + mSPs.size() + " configurations");
        return sps;
    }

    public HomeSP addSP(InputStream xmlIn) throws IOException, SAXException {
        OMAParser omaParser = new OMAParser();
        MOTree tree = omaParser.parse(xmlIn, OMAConstants.LOC_PPS + ":1.0");
        List<HomeSP> spList = buildSPs(tree);
        if (spList.size() != 1) {
            throw new OMAException("Expected exactly one HomeSP, got " + spList.size());
        }
        HomeSP sp = spList.iterator().next();
        String fqdn = sp.getFQDN();
        if (mSPs.put(fqdn, sp) != null) {
            throw new OMAException("SP " + fqdn + " already exists");
        }

        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(mPpsFile, true));
            tree.marshal(out);
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    /**/
                }
            }
        }

        return sp;
    }

    public void saveAllSps(Collection<HomeSP> homeSPs) throws IOException {

        OMAConstructed root = new OMAConstructed(null, "MgmtTree", "");

        for (HomeSP homeSP : homeSPs) {
            OMANode providerNode = root.addChild(TAG_PerProviderSubscription, null, null, null);
            OMANode providerSubNode = providerNode.addChild("Node", null, null, null);

            Log.d("PARSE-LOG", "creating node homeSP for " + homeSP.getFQDN());

            if (mSPs.put(homeSP.getFQDN(), homeSP) != null) {
                throw new OMAException("SP " + homeSP.getFQDN() + " already exists");
            }
            OMANode homeSpNode = providerSubNode.addChild(TAG_HomeSP, null, null, null);
            homeSpNode.addChild(TAG_FQDN, null, homeSP.getFQDN(), null);
            homeSpNode.addChild(TAG_FriendlyName, null, homeSP.getFriendlyName(), null);

            OMANode credentialNode = providerSubNode.addChild(TAG_Credential, null, null, null);
            Credential cred = homeSP.getCredential();
            EAPMethod method = cred.getEAPMethod();

            if (method == null) {
                throw new OMAException("SP " + homeSP.getFQDN() + " already exists");
            }

            OMANode credRootNode;
            if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_SIM
                    || method.getEAPMethodID() == EAP.EAPMethodID.EAP_AKA
                    || method.getEAPMethodID() == EAP.EAPMethodID.EAP_AKAPrim) {

                Log.d("PARSE-LOG", "Saving SIM credential");
                credRootNode = credentialNode.addChild(TAG_SIM, null, null, null);
                credRootNode.addChild(TAG_IMSI, null, cred.getImsi(), null);

            } else if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_TTLS) {

                Log.d("PARSE-LOG", "Saving TTLS Credential");
                credRootNode = credentialNode.addChild(TAG_UsernamePassword, null, null, null);
                credRootNode.addChild(TAG_Username, null, cred.getUserName(), null);

            } else if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_TLS) {

                Log.d("PARSE-LOG", "Saving TLS Credential");
                credRootNode = credentialNode.addChild(TAG_DigitalCertificate, null, null, null);

            } else {
                throw new OMAException("Invalid credential on " + homeSP.getFQDN());
            }

            credentialNode.addChild(TAG_Realm, null, homeSP.getCredential().getRealm(), null);
            credentialNode.addChild(TAG_CheckAAAServerCertStatus, null, "true", null);
            OMANode eapMethodNode = credRootNode.addChild(TAG_EAPMethod, null, null, null);
            OMANode eapTypeNode = eapMethodNode.addChild(TAG_EAPType,
                    null, EAP.mapEAPMethod(method.getEAPMethodID()).toString(), null);

            if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_TTLS) {
                OMANode innerEAPType = eapMethodNode.addChild(TAG_InnerEAPType,
                        null, EAP.mapEAPMethod(EAP.EAPMethodID.EAP_MSCHAPv2).toString(), null);
            }

            StringBuilder builder = new StringBuilder();
            for (Long roamingConsortium : homeSP.getRoamingConsortiums()) {
                builder.append(roamingConsortium.toString());
            }
            credentialNode.addChild(TAG_RoamingConsortiumOI, null, builder.toString(), null);
        }

        Log.d("PARSE-LOG", "Saving all SPs");

        MOTree tree = new MOTree(OMAConstants.LOC_PPS + ":1.0", "1.2", root);
        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(mPpsFile, true));
            tree.marshal(out);
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    /**/
                }
            }
        }
    }

    private static final DateFormat DTFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    static {
        DTFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static final String TAG_AAAServerTrustRoot = "AAAServerTrustRoot";
    public static final String TAG_AbleToShare = "AbleToShare";
    public static final String TAG_CertificateType = "CertificateType";
    public static final String TAG_CertSHA256Fingerprint = "CertSHA256Fingerprint";
    public static final String TAG_CertURL = "CertURL";
    public static final String TAG_CheckAAAServerCertStatus = "CheckAAAServerCertStatus";
    public static final String TAG_Country = "Country";
    public static final String TAG_CreationDate = "CreationDate";
    public static final String TAG_Credential = "Credential";
    public static final String TAG_CredentialPriority = "CredentialPriority";
    public static final String TAG_DataLimit = "DataLimit";
    public static final String TAG_DigitalCertificate = "DigitalCertificate";
    public static final String TAG_DLBandwidth = "DLBandwidth";
    public static final String TAG_EAPMethod = "EAPMethod";
    public static final String TAG_EAPType = "EAPType";
    public static final String TAG_ExpirationDate = "ExpirationDate";
    public static final String TAG_Extension = "Extension";
    public static final String TAG_FQDN = "FQDN";
    public static final String TAG_FQDN_Match = "FQDN_Match";
    public static final String TAG_FriendlyName = "FriendlyName";
    public static final String TAG_HESSID = "HESSID";
    public static final String TAG_HomeOI = "HomeOI";
    public static final String TAG_HomeOIList = "HomeOIList";
    public static final String TAG_HomeOIRequired = "HomeOIRequired";
    public static final String TAG_HomeSP = "HomeSP";
    public static final String TAG_IconURL = "IconURL";
    public static final String TAG_IMSI = "IMSI";
    public static final String TAG_InnerEAPType = "InnerEAPType";
    public static final String TAG_InnerMethod = "InnerMethod";
    public static final String TAG_InnerVendorID = "InnerVendorID";
    public static final String TAG_InnerVendorType = "InnerVendorType";
    public static final String TAG_IPProtocol = "IPProtocol";
    public static final String TAG_MachineManaged = "MachineManaged";
    public static final String TAG_MaximumBSSLoadValue = "MaximumBSSLoadValue";
    public static final String TAG_MinBackhaulThreshold = "MinBackhaulThreshold";
    public static final String TAG_NetworkID = "NetworkID";
    public static final String TAG_NetworkType = "NetworkType";
    public static final String TAG_Other = "Other";
    public static final String TAG_OtherHomePartners = "OtherHomePartners";
    public static final String TAG_Password = "Password";
    public static final String TAG_PerProviderSubscription = "PerProviderSubscription";
    public static final String TAG_Policy = "Policy";
    public static final String TAG_PolicyUpdate = "PolicyUpdate";
    public static final String TAG_PortNumber = "PortNumber";
    public static final String TAG_PreferredRoamingPartnerList = "PreferredRoamingPartnerList";
    public static final String TAG_Priority = "Priority";
    public static final String TAG_Realm = "Realm";
    public static final String TAG_RequiredProtoPortTuple = "RequiredProtoPortTuple";
    public static final String TAG_Restriction = "Restriction";
    public static final String TAG_RoamingConsortiumOI = "RoamingConsortiumOI";
    public static final String TAG_SIM = "SIM";
    public static final String TAG_SoftTokenApp = "SoftTokenApp";
    public static final String TAG_SPExclusionList = "SPExclusionList";
    public static final String TAG_SSID = "SSID";
    public static final String TAG_StartDate = "StartDate";
    public static final String TAG_SubscriptionParameters = "SubscriptionParameters";
    public static final String TAG_SubscriptionUpdate = "SubscriptionUpdate";
    public static final String TAG_TimeLimit = "TimeLimit";
    public static final String TAG_TrustRoot = "TrustRoot";
    public static final String TAG_TypeOfSubscription = "TypeOfSubscription";
    public static final String TAG_ULBandwidth = "ULBandwidth";
    public static final String TAG_UpdateIdentifier = "UpdateIdentifier";
    public static final String TAG_UpdateInterval = "UpdateInterval";
    public static final String TAG_UpdateMethod = "UpdateMethod";
    public static final String TAG_URI = "URI";
    public static final String TAG_UsageLimits = "UsageLimits";
    public static final String TAG_UsageTimePeriod = "UsageTimePeriod";
    public static final String TAG_Username = "Username";
    public static final String TAG_UsernamePassword = "UsernamePassword";
    public static final String TAG_VendorId = "VendorId";
    public static final String TAG_VendorType = "VendorType";

    private static List<HomeSP> buildSPs(MOTree moTree) throws OMAException {
        List<String> spPath = Arrays.asList(TAG_PerProviderSubscription);
        OMAConstructed spList = moTree.getRoot().getListValue(spPath.iterator());

        List<HomeSP> homeSPs = new ArrayList<HomeSP>();

        Log.d("PARSE-LOG", " node-name = " + spList.getName());
        if (spList == null) {
            return homeSPs;
        }

        Log.d("PARSE-LOG", " num_children = " + spList.getChildren().size());
        for (OMANode spRoot : spList.getChildren()) {
            Log.d("PARSE-LOG", " node-name = " + spRoot.getName());
            homeSPs.add(buildHomeSP(spRoot));
        }

        return homeSPs;
    }

    private static HomeSP buildHomeSP(OMANode ppsRoot) throws OMAException {
        Log.d("PARSE-LOG", " node-name = " + ppsRoot.getName());
        OMANode spRoot = ppsRoot.getChild(TAG_HomeSP);

        String fqdn = spRoot.getScalarValue(Arrays.asList(TAG_FQDN).iterator());
        String friendlyName = spRoot.getScalarValue(Arrays.asList(TAG_FriendlyName).iterator());
        System.out.println("FQDN: " + fqdn + ", friendly: " + friendlyName);
        String iconURL = spRoot.getScalarValue(Arrays.asList(TAG_IconURL).iterator());

        Set<Long> roamingConsortiums = new HashSet<Long>();
        String oiString = spRoot.getScalarValue(Arrays.asList(TAG_RoamingConsortiumOI).iterator());
        if (oiString != null) {
            for (String oi : oiString.split(",")) {
                roamingConsortiums.add(Long.parseLong(oi.trim(), 16));
            }
        }

        Map<String, Long> ssids = new HashMap<String, Long>();

        OMANode ssidListNode = spRoot.getListValue(Arrays.asList(TAG_NetworkID).iterator());
        if (ssidListNode != null) {
            for (OMANode ssidRoot : ssidListNode.getChildren()) {
                OMANode hessidNode = ssidRoot.getChild(TAG_HESSID);
                ssids.put(ssidRoot.getChild(TAG_SSID).getValue(), getMac(hessidNode));
            }
        }

        Set<Long> matchAnyOIs = new HashSet<Long>();
        List<Long> matchAllOIs = new ArrayList<Long>();
        OMANode homeOIListNode = spRoot.getListValue(Arrays.asList(TAG_HomeOIList).iterator());
        if (homeOIListNode != null) {
            for (OMANode homeOIRoot : homeOIListNode.getChildren()) {
                String homeOI = homeOIRoot.getChild(TAG_HomeOI).getValue();
                if (Boolean.parseBoolean(homeOIRoot.getChild(TAG_HomeOIRequired).getValue())) {
                    matchAllOIs.add(Long.parseLong(homeOI, 16));
                } else {
                    matchAnyOIs.add(Long.parseLong(homeOI, 16));
                }
            }
        }

        Set<String> otherHomePartners = new HashSet<String>();
        OMANode otherListNode =
                spRoot.getListValue(Arrays.asList(TAG_OtherHomePartners).iterator());
        if (otherListNode != null) {
            for (OMANode fqdnNode : otherListNode.getChildren()) {
                otherHomePartners.add(fqdnNode.getChild(TAG_FQDN).getValue());
            }
        }

        Credential credential = buildCredential(ppsRoot.getChild(TAG_Credential));

        Log.d("PARSE-LOG", " Building a new HomeSP for " + fqdn);
        return new HomeSP(ssids, fqdn, roamingConsortiums, otherHomePartners,
                matchAnyOIs, matchAllOIs, friendlyName, iconURL, credential);
    }

    private static Credential buildCredential(OMANode credNode) throws OMAException {
        Log.d("PARSE-LOG", " Reading credential from " + credNode.getName());
        long ctime = getTime(credNode.getChild(TAG_CreationDate));
        long expTime = getTime(credNode.getChild(TAG_ExpirationDate));
        String realm = getString(credNode.getChild(TAG_Realm));
        boolean checkAAACert = getBoolean(credNode.getChild(TAG_CheckAAAServerCertStatus));

        OMANode unNode = credNode.getChild(TAG_UsernamePassword);
        OMANode certNode = credNode.getChild(TAG_DigitalCertificate);
        OMANode simNode = credNode.getChild(TAG_SIM);

        int alternatives = 0;
        alternatives += unNode != null ? 1 : 0;
        alternatives += certNode != null ? 1 : 0;
        alternatives += simNode != null ? 1 : 0;
        if (alternatives != 1) {
            throw new OMAException("Expected exactly one credential type, got " + alternatives);
        }

        if (unNode != null) {
            String userName = getString(unNode.getChild(TAG_Username));
            String password = getString(unNode.getChild(TAG_Password));
            boolean machineManaged = getBoolean(unNode.getChild(TAG_MachineManaged));
            String softTokenApp = getString(unNode.getChild(TAG_SoftTokenApp));
            boolean ableToShare = getBoolean(unNode.getChild(TAG_AbleToShare));

            OMANode eapMethodNode = unNode.getChild(TAG_EAPMethod);
            EAP.EAPMethodID eapMethodID =
                    EAP.mapEAPMethod(getInteger(eapMethodNode.getChild(TAG_EAPType)));
            if (eapMethodID == null) {
                throw new OMAException("Unknown EAP method");
            }

            Long vid = getOptionalInteger(eapMethodNode.getChild(TAG_VendorId));
            Long vtype = getOptionalInteger(eapMethodNode.getChild(TAG_VendorType));
            Long innerEAPType = getOptionalInteger(eapMethodNode.getChild(TAG_InnerEAPType));
            EAP.EAPMethodID innerEAPMethod = null;
            if (innerEAPType != null) {
                innerEAPMethod = EAP.mapEAPMethod(innerEAPType.intValue());
                if (innerEAPMethod == null) {
                    throw new OMAException("Bad inner EAP method: " + innerEAPType);
                }
            }

            Long innerVid = getOptionalInteger(eapMethodNode.getChild(TAG_InnerVendorID));
            Long innerVtype = getOptionalInteger(eapMethodNode.getChild(TAG_InnerVendorType));
            String innerNonEAPMethod = getString(eapMethodNode.getChild(TAG_InnerMethod));

            EAPMethod eapMethod;
            if (innerEAPMethod != null) {
                eapMethod = new EAPMethod(eapMethodID, new InnerAuthEAP(innerEAPMethod));
            } else if (vid != null) {
                eapMethod = new EAPMethod(eapMethodID,
                        new ExpandedEAPMethod(EAP.AuthInfoID.ExpandedEAPMethod,
                                vid.intValue(), vtype));
            } else if (innerVid != null) {
                eapMethod =
                        new EAPMethod(eapMethodID, new ExpandedEAPMethod(EAP.AuthInfoID
                                .ExpandedInnerEAPMethod, innerVid.intValue(), innerVtype));
            } else if (innerNonEAPMethod != null) {
                eapMethod = new EAPMethod(eapMethodID, new NonEAPInnerAuth(innerNonEAPMethod));
            } else {
                throw new OMAException("Incomplete set of EAP parameters");
            }

            return new Credential(ctime, expTime, realm, checkAAACert, eapMethod, userName,
                    password, machineManaged, softTokenApp, ableToShare);
        }
        if (certNode != null) {
            try {
                String certTypeString = getString(certNode.getChild(TAG_CertificateType));
                byte[] fingerPrint = getOctets(certNode.getChild(TAG_CertSHA256Fingerprint));

                EAPMethod eapMethod = new EAPMethod(EAP.EAPMethodID.EAP_TLS, null);

                return new Credential(ctime, expTime, realm, checkAAACert, eapMethod,
                        Credential.mapCertType(certTypeString), fingerPrint);
            }
            catch (NumberFormatException nfe) {
                throw new OMAException("Bad hex string: " + nfe.toString());
            }
        }
        if (simNode != null) {

            String imsi = getString(simNode.getChild(TAG_IMSI));
            EAPMethod eapMethod =
                    new EAPMethod(EAP.mapEAPMethod(getInteger(simNode.getChild(TAG_EAPType))),
                            null);

            return new Credential(ctime, expTime, realm, checkAAACert, eapMethod, imsi);
        }
        throw new OMAException("Missing credential parameters");
    }

    private static boolean getBoolean(OMANode boolNode) {
        return boolNode != null && Boolean.parseBoolean(boolNode.getValue());
    }

    private static String getString(OMANode stringNode) {
        return stringNode != null ? stringNode.getValue() : null;
    }

    private static int getInteger(OMANode intNode) throws OMAException {
        if (intNode == null) {
            throw new OMAException("Missing integer value");
        }
        try {
            return Integer.parseInt(intNode.getValue());
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid integer: " + intNode.getValue());
        }
    }

    private static Long getMac(OMANode macNode) throws OMAException {
        if (macNode == null) {
            return null;
        }
        try {
            return Long.parseLong(macNode.getValue(), 16);
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid MAC: " + macNode.getValue());
        }
    }

    private static Long getOptionalInteger(OMANode intNode) throws OMAException {
        if (intNode == null) {
            return null;
        }
        try {
            return Long.parseLong(intNode.getValue());
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid integer: " + intNode.getValue());
        }
    }

    private static long getTime(OMANode timeNode) throws OMAException {
        if (timeNode == null) {
            return -1;
        }
        String timeText = timeNode.getValue();
        try {
            Date date = DTFormat.parse(timeText);
            return date.getTime();
        } catch (ParseException pe) {
            throw new OMAException("Badly formatted time: " + timeText);
        }
    }

    private static byte[] getOctets(OMANode octetNode) throws OMAException {
        if (octetNode == null) {
            // throw new OMAException("Missing byte value");
            return null;
        }
        return Utils.hexToBytes(octetNode.getValue());
    }
}
