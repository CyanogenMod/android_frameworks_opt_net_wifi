package com.android.server.wifi.hotspot2.osu;

import android.util.Log;

import com.android.server.wifi.anqp.ANQPFactory;
import com.android.server.wifi.anqp.HSIconFileElement;
import com.android.server.wifi.anqp.IconInfo;
import com.android.server.wifi.hotspot2.SupplicantBridge;
import com.android.server.wifi.hotspot2.Utils;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.android.server.wifi.anqp.Constants.ANQPElementType.*;

public class IconCache {
    private static final int CacheSize = 64;

    private final OSUManager mOSUManager;
    private final Map<Long, LinkedList<IconQuery>> mPendingQueries = new HashMap<>();
    private final Map<IconKey, HSIconFileElement> mCache = new LinkedHashMap<IconKey, HSIconFileElement>() {
        @Override
        protected boolean removeEldestEntry(Entry eldest) {
            return size() > CacheSize;
        }
    };

    public IconCache(OSUManager osuManager) {
        mOSUManager = osuManager;
    }

    /**
     * Enqueue a query for this BSSID
     * @param query the query to stash in current BSSID bucket. Queries need to be serialized per
     *              BSSID since there is no way of tying a response back to the file name.
     * @return true if the added query is the current one.
     */
    private boolean enqueueQuery(IconQuery query) {
        long bssid = query.getBSSID();
        LinkedList<IconQuery> requests = mPendingQueries.get(bssid);
        if (requests == null || requests.isEmpty()) {
            requests = new LinkedList<>();
            requests.add(query);
            mPendingQueries.put(bssid, requests);
            return true;
        }
        else {
            requests.addLast(query);
            return false;
        }
    }

    private IconQuery dropCurrentQuery(long bssid) {
        IconQuery current = null;
        LinkedList<IconQuery> requests = mPendingQueries.get(bssid);
        if (requests != null) {
            if (!requests.isEmpty()) {
                current = requests.removeFirst();     // Remove the associated request
            }

            if (requests.isEmpty()) {
                mPendingQueries.remove(bssid);
            }
        }
        return current;
    }

    private IconQuery getNextQuery(long bssid) {
        LinkedList<IconQuery> requests = mPendingQueries.get(bssid);
        if (requests != null) {
            if (!requests.isEmpty()) {
                return requests.getFirst();
            }
            else {
                mPendingQueries.remove(bssid);
            }
        }
        return null;
    }

    public void startIconQuery(OSUInfo osuInfo, List<IconInfo> icons) {
        Log.d(Utils.hs2LogTag(getClass()),
                String.format("Icon query on %012x for %s", osuInfo.getBSSID(), icons));
        if (icons == null || icons.isEmpty()) {
            return;
        }

        HSIconFileElement cached;
        IconQuery query = null;

        synchronized (mCache) {
            IconInfo iconInfo = icons.iterator().next();
            IconKey key = new IconKey(osuInfo.getBSSID(), iconInfo.getFileName());
            cached = mCache.get(key);
            if (cached != null) {
                osuInfo.setIconFileElement(cached, iconInfo.getFileName());
            }
            else {
                query = new IconQuery(osuInfo, icons);
                if (!enqueueQuery(new IconQuery(osuInfo, icons))) {
                    query = null;
                }
                osuInfo.setIconStatus(OSUInfo.IconStatus.InProgress);
            }
        }
        if (cached != null) {
            mOSUManager.iconResult(osuInfo);
        }

        Log.d(Utils.hs2LogTag(getClass()),
                String.format("Instant icon query for %012x: %s", osuInfo.getBSSID(), query));

        if (query != null) {
            doIconQuery(osuInfo.getBSSID(), query);
        }
    }

    public void notifyIconReceived(long bssid, byte[] iconData) {
        Log.d(Utils.hs2LogTag(getClass()),
                String.format("Icon data for %012x: %d",
                        bssid, iconData != null ? iconData.length : 0));

        if (iconData == null) {
            doIconQuery(bssid, null);
            return;
        }

        IconQuery current;
        synchronized (mCache) {
            current = dropCurrentQuery(bssid);
            if (current == null) {
                Log.w(Utils.hs2LogTag(getClass()), "Spurious icon data");
                return;
            }

            try {
                HSIconFileElement iconFileElement = (HSIconFileElement)
                        ANQPFactory.buildHS20Element(HSIconFile,
                                ByteBuffer.wrap(iconData).order(ByteOrder.LITTLE_ENDIAN));

                String fileName = current.getIconInfo().getFileName();
                Log.d(Utils.hs2LogTag(getClass()), "Icon file " + fileName + ": " + iconFileElement);
                current.getOSUInfo().setIconFileElement(iconFileElement, fileName);

                mCache.put(new IconKey(bssid, fileName), iconFileElement);
            }
            catch (ProtocolException | BufferUnderflowException e) {
                Log.e(Utils.hs2LogTag(SupplicantBridge.class),
                        "Failed to parse ANQP icon file: " + e);
                current.getOSUInfo().setIconStatus(OSUInfo.IconStatus.NotAvailable);
            }
        }

        mOSUManager.iconResult(current.getOSUInfo());

        IconQuery next;
        synchronized (mCache) {
            next = getNextQuery(bssid);
        }

        if (next != null) {
            doIconQuery(bssid, next);
        }
    }

    private static final long RequeryTimeLow = 6000L;
    private static final long RequeryTimeHigh = 15000L;

    public void tickle(boolean wifiOff) {
        long now = System.currentTimeMillis();
        synchronized (mCache) {
            if (wifiOff) {
                mPendingQueries.clear();
                // Not sure it makes sense to clear the icon cache.
            }
            else {
                for (Map.Entry<Long, LinkedList<IconQuery>> entry : mPendingQueries.entrySet()) {
                    Iterator<IconQuery> queries = entry.getValue().iterator();
                    while (queries.hasNext()) {
                        IconQuery query = queries.next();
                        long age = now - query.getLastSent();
                        if (age > RequeryTimeHigh) {
                            queries.remove();
                        }
                        else if (age > RequeryTimeLow) {
                            doIconQuery(entry.getKey(), query);
                            break;
                        }
                    }
                    if (entry.getValue().isEmpty()) {
                        mPendingQueries.remove(entry.getKey());
                    }
                }
            }
        }
    }

    private void doIconQuery(long bssid, IconQuery query) {
        boolean success;
        do {
            IconQuery current = query;
            synchronized (mCache) {
                if (query == null) {
                    current = getNextQuery(bssid);
                }

                while (current != null) {
                    if (current.getRetry() < IconRetries) {
                        current.bumpRetry();
                        break;
                    } else if (current.hasRemaining()) {
                        current.bumpIndex();
                        break;
                    } else {
                        current.getOSUInfo().setIconStatus(OSUInfo.IconStatus.NotAvailable);
                        dropCurrentQuery(bssid);
                        current = getNextQuery(bssid);
                    }
                }
            }
            Log.d(Utils.hs2LogTag(getClass()), String.format("Resulting icon request to %012x: %s",
                    bssid, current));

            if (current != null) {
                HSIconFileElement iconFileElement;
                String fileName = current.getIconInfo().getFileName();
                synchronized (mCache) {
                    iconFileElement =
                            mCache.get(new IconKey(bssid, fileName));
                }

                if (iconFileElement != null) {
                    // A "sub-ordinate" icon could have been cached before.
                    current.getOSUInfo().setIconFileElement(iconFileElement, fileName);
                    mOSUManager.iconResult(current.getOSUInfo());
                    success = true;
                }
                else {
                    success = mOSUManager.getSupplicantBridge().doIconQuery(current.getBSSID(),
                                    current.getIconInfo().getFileName());
                }
            }
            else {
                break;
            }
        }
        while (!success);
    }

    private static class IconKey {
        private final long mBSSID;
        private final String mFileName;

        private IconKey(long bssid, String fileName) {
            mBSSID = bssid;
            mFileName = fileName;
        }

        @Override
        public boolean equals(Object thatObject) {
            if (this == thatObject) {
                return true;
            }
            if (thatObject == null || getClass() != thatObject.getClass()) {
                return false;
            }

            IconKey that = (IconKey) thatObject;
            return mBSSID == that.mBSSID && mFileName.equals(that.mFileName);
        }

        @Override
        public int hashCode() {
            int result = (int) (mBSSID ^ (mBSSID >>> 32));
            result = 31 * result + mFileName.hashCode();
            return result;
        }
    }

    private static final int IconRetries = 3;

    private static class IconQuery {
        private final OSUInfo mOSUInfo;
        private final List<IconInfo> mIconInfos;
        private int mIndex;
        private int mRetry;
        private long mLastSent;

        private IconQuery(OSUInfo osuInfo, List<IconInfo> iconInfos) {
            mOSUInfo = osuInfo;
            mIconInfos = iconInfos;
            mLastSent = System.currentTimeMillis();
        }

        public IconInfo getIconInfo() {
            return mIconInfos.get(mIndex);
        }

        private void bumpRetry() {
            mRetry++;
            mLastSent = System.currentTimeMillis();
        }

        private void bumpIndex() {
            mIndex++;
            mRetry = 0;
            mLastSent = System.currentTimeMillis();
        }

        public OSUInfo getOSUInfo() {
            return mOSUInfo;
        }

        private boolean hasRemaining() {
            return mIndex < mIconInfos.size() - 1;
        }

        public int getRetry() {
            return mRetry;
        }

        public long getBSSID() {
            return mOSUInfo.getBSSID();
        }

        private long getLastSent() {
            return mLastSent;
        }

        @Override
        public String toString() {
            return "IconQuery{" +
                    "OSUInfo=" + mOSUInfo +
                    ", IconInfos=" + mIconInfos +
                    ", Index=" + mIndex +
                    ", Retry=" + mRetry +
                    ", LastSent=" + mLastSent +
                    '}';
        }
    }

}
