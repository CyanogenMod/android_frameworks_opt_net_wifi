package com.android.server.wifi.hotspot2.pps;

import com.android.server.wifi.hotspot2.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DomainMatcher {

    public enum Match {None, Primary, Secondary}

    private final Label mRoot;

    private static class Label {
        private final Map<String, Label> mSubDomains;
        private final Match mMatch;

        private Label(Match match) {
            mMatch = match;
            mSubDomains = match == Match.None ? new HashMap<String, Label>() : null;
        }

        private void addDomain(Iterator<String> labels, Match match) {
            String labelName = labels.next();
            if (labels.hasNext()) {
                Label subLabel = new Label(Match.None);
                mSubDomains.put(labelName, subLabel);
                subLabel.addDomain(labels, match);
            } else {
                mSubDomains.put(labelName, new Label(match));
            }
        }

        private Label getSubLabel(String labelString) {
            return mSubDomains.get(labelString);
        }

        public Match getMatch() {
            return mMatch;
        }

        private void toString(StringBuilder sb) {
            if (mSubDomains != null) {
                sb.append(".{");
                for (Map.Entry<String, Label> entry : mSubDomains.entrySet()) {
                    sb.append(entry.getKey());
                    entry.getValue().toString(sb);
                }
                sb.append('}');
            } else {
                sb.append('=').append(mMatch);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            return sb.toString();
        }
    }

    public DomainMatcher(List<String> primary, List<List<String>> secondary) {
        mRoot = new Label(Match.None);
        for (List<String> secondaryLabel : secondary) {
            mRoot.addDomain(secondaryLabel.iterator(), Match.Secondary);
        }
        System.out.println("Primary: " + primary);
        // Primary overwrites secondary.
        mRoot.addDomain(primary.iterator(), Match.Primary);
    }

    public Match isSubDomain(List<String> domain) {

        Label label = mRoot;
        for (String labelString : domain) {
            label = label.getSubLabel(labelString);
            if (label == null) {
                return Match.None;
            } else if (label.getMatch() != Match.None) {
                return label.getMatch();
            }
        }
        return Match.None;  // Domain is a super domain
    }

    @Override
    public String toString() {
        return "Domain matcher " + mRoot;
    }

    private static final String[] TestDomains = {
            "garbage.apple.com",
            "apple.com",
            "com",
            "jan.android.google.com.",
            "jan.android.google.com",
            "android.google.com",
            "google.com",
            "jan.android.google.net.",
            "jan.android.google.net",
            "android.google.net",
            "google.net",
            "net.",
            "."
    };

    public static void main(String[] args) {
        DomainMatcher dm1 = new DomainMatcher(Utils.splitDomain("android.google.com"),
                Collections.<List<String>>emptyList());
        for (String domain : TestDomains) {
            System.out.println(domain + ": " + dm1.isSubDomain(Utils.splitDomain(domain)));
        }
        List<List<String>> secondaries = new ArrayList<List<String>>();
        secondaries.add(Utils.splitDomain("apple.com"));
        secondaries.add(Utils.splitDomain("net"));
        DomainMatcher dm2 = new DomainMatcher(Utils.splitDomain("android.google.com"), secondaries);
        for (String domain : TestDomains) {
            System.out.println(domain + ": " + dm2.isSubDomain(Utils.splitDomain(domain)));
        }
    }
}
