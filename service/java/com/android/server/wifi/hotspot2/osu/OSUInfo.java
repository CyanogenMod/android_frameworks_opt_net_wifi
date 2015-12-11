package com.android.server.wifi.hotspot2.osu;

import android.util.Log;

import com.android.server.wifi.anqp.HSIconFileElement;
import com.android.server.wifi.anqp.I18Name;
import com.android.server.wifi.anqp.IconInfo;
import com.android.server.wifi.anqp.OSUProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OSUInfo {
    public static final String GenericLocale = "zxx";

    public enum IconStatus {
        NotQueried,     //
        InProgress,     // Query pending
        NotAvailable,   // Deterministically unavailable
        Available       // Icon data retrieved
    }

    private final long mBSSID;
    private final String mSSID;
    private final OSUProvider mOSUProvider;
    private final int mOsuID;
    private IconStatus mIconStatus = IconStatus.NotQueried;
    private HSIconFileElement mIconFileElement;
    private IconInfo mIconInfo;

    public OSUInfo(long bssid, String ssid, OSUProvider osuProvider, int osuID) {
        mOsuID = osuID;
        mBSSID = bssid;
        mSSID = ssid;
        mOSUProvider = osuProvider;
    }

    public Set<Locale> getNameLocales() {
        Set<Locale> locales = new HashSet<>(mOSUProvider.getNames().size());
        for (I18Name name : mOSUProvider.getNames()) {
            locales.add(name.getLocale());
        }
        return locales;
    }

    public Set<Locale> getServiceLocales() {
        Set<Locale> locales = new HashSet<>(mOSUProvider.getServiceDescriptions().size());
        for (I18Name name : mOSUProvider.getServiceDescriptions()) {
            locales.add(name.getLocale());
        }
        return locales;
    }

    public Set<String> getIconLanguages() {
        Set<String> locales = new HashSet<>(mOSUProvider.getIcons().size());
        for (IconInfo iconInfo : mOSUProvider.getIcons()) {
            locales.add(iconInfo.getLanguage());
        }
        return locales;
    }

    public String getName(Locale locale) {
        for (I18Name name : mOSUProvider.getNames()) {
            if (locale == null || name.getLocale().equals(locale)) {
                return name.getText();
            }
        }
        return null;
    }

    public String getServiceDescription(Locale locale) {
        for (I18Name service : mOSUProvider.getServiceDescriptions()) {
            if (locale == null || service.getLocale().equals(locale)) {
                return service.getText();
            }
        }
        return null;
    }

    public int getOsuID() {
        return mOsuID;
    }

    public void setIconStatus(IconStatus iconStatus) {
        synchronized (mOSUProvider) {
            mIconStatus = iconStatus;
        }
    }

    public IconStatus getIconStatus() {
        synchronized (mOSUProvider) {
            return mIconStatus;
        }
    }

    public HSIconFileElement getIconFileElement() {
        synchronized (mOSUProvider) {
            return mIconFileElement;
        }
    }

    public IconInfo getIconInfo() {
        synchronized (mOSUProvider) {
            return mIconInfo;
        }
    }

    public void setIconFileElement(HSIconFileElement iconFileElement, String fileName) {
        synchronized (mOSUProvider) {
            mIconFileElement = iconFileElement;
            for (IconInfo iconInfo : mOSUProvider.getIcons()) {
                if (iconInfo.getFileName().equals(fileName)) {
                    mIconInfo = iconInfo;
                    break;
                }
            }
            mIconStatus = IconStatus.Available;
        }
    }

    private static class ScoredIcon implements Comparable<ScoredIcon> {
        private final IconInfo mIconInfo;
        private final int mScore;

        private ScoredIcon(IconInfo iconInfo, int score) {
            mIconInfo = iconInfo;
            mScore = score;
        }

        public IconInfo getIconInfo() {
            return mIconInfo;
        }

        @Override
        public int compareTo(ScoredIcon other) {
            return Integer.compare(mScore, other.mScore);
        }
    }

    public List<IconInfo> getIconInfo(Locale locale, Set<String> types, int width, int height) {
        if (mOSUProvider.getIcons().isEmpty()) {
            return null;
        }
        Log.d(OSUManager.TAG, "Matching icons against " + locale + ", types " + types + ", " + width + "*" + height);

        List<ScoredIcon> matches = new ArrayList<>();
        for (IconInfo iconInfo : mOSUProvider.getIcons()) {
            Log.d(OSUManager.TAG, "Checking icon " + iconInfo.toString());
            if (!types.contains(iconInfo.getIconType())) {
                continue;
            }

            int score = languageScore(iconInfo, locale);
            int delta = iconInfo.getWidth() - width;
            // Best size score is 1024 for a exact match, i.e. 2048 if both sides match
            if (delta >= 0) {
                score += (256 - delta) * 4;  // Prefer down-scaling
            }
            else {
                score += 256 + delta;    // Before up-scaling
            }
            delta = iconInfo.getHeight() - height;
            if (delta >= 0) {
                score += (256 - delta) * 4;
            }
            else {
                score += 256 + delta;
            }
            matches.add(new ScoredIcon(iconInfo, score));
        }
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(matches);
        List<IconInfo> icons = new ArrayList<>(matches.size());
        for (ScoredIcon scoredIcon : matches) {
            icons.add(scoredIcon.getIconInfo());
        }
        return icons;
    }

    private static int languageScore(IconInfo iconInfo, Locale locale) {
        String iconLanguage = iconInfo.getLanguage();
        if (iconLanguage.length() == 3 && iconLanguage.equalsIgnoreCase(locale.getISO3Language()) ||
            iconLanguage.length() == 2 && iconLanguage.equalsIgnoreCase(locale.getLanguage())) {
            return 4096;
        }
        else if (iconLanguage.equalsIgnoreCase(GenericLocale)) {
            return 3072;
        }
        else if (iconLanguage.equalsIgnoreCase("eng")) {
            return 2048;
        }
        else {
            return 1024;
        }
    }

    public long getBSSID() {
        return mBSSID;
    }

    public String getSSID() {
        return mSSID;
    }

    public OSUProvider getOSUProvider() {
        return mOSUProvider;
    }

    @Override
    public String toString() {
        return String.format("OSU Info '%s' %012x -> %s",
                mSSID, mBSSID, mOSUProvider.getOSUServer());
    }
}
