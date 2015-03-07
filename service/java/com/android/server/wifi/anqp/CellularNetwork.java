package com.android.server.wifi.anqp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.android.server.wifi.anqp.Constants.BYTE_MASK;

public class CellularNetwork {
    private static final int PLMNListType = 0;
    private static final int MNC3Mask = 0x10000;    // Beware: marker bit for 3 digit MNC in MNC

    private final List<int[]> mMccMnc;

    private CellularNetwork(int plmnCount, ByteBuffer payload) {
        mMccMnc = new ArrayList<int[]>(plmnCount);

        while (plmnCount > 0) {

            byte[] plmn = new byte[3];
            payload.get(plmn);

            int mcc = ((plmn[0] << 16) & 0xf00) |
                              (plmn[0] & 0x0f0) |
                              (plmn[1] & 0x00f);

            int mnc = ((plmn[2] << 8) & 0xf0) |
                      ((plmn[2] >> 8) & 0x0f);

            int n2 = (plmn[1] >> 8) & 0x0f;
            if (n2 != 0xf) {
                mnc = (mnc << 8) | n2 | MNC3Mask;
            }

            mMccMnc.add(new int[]{mcc, mnc});
        }
    }

    public static CellularNetwork buildCellularNetwork(ByteBuffer payload) {
        int iei = payload.get() & BYTE_MASK;
        int plmnLen = payload.get() & 0x7f;

        if (iei != PLMNListType) {
            payload.position(payload.position() + plmnLen);
            return null;
        }

        int plmnCount = payload.get() & BYTE_MASK;
        return new CellularNetwork(plmnCount, payload);
    }

    public boolean matchIMSI(String imsi) {
        if (imsi.length() < 5) {
            return false;
        }

        int mcc = Integer.parseInt(imsi.substring(0, 3), 16);
        int mnc2 = -1;
        int mnc3 = -1;

        for (int[] mccMnc : mMccMnc) {
            if (mccMnc[0] == mcc) {
                if ((mccMnc[1] & MNC3Mask) != 0) {
                    if (mnc3 < 0) {
                        if (imsi.length() < 6) {
                            continue;
                        }
                        mnc3 = Integer.parseInt(imsi.substring(3, 6)) | MNC3Mask;
                    }
                    if (mccMnc[1] == mnc3) {
                        return true;
                    }
                } else {
                    if (mnc2 < 0) {
                        mnc2 = Integer.parseInt(imsi.substring(3, 5));
                    }
                    if (mccMnc[1] == mnc2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PLMN:");
        for (int[] mccMnc : mMccMnc) {
            if ((mccMnc[1] & MNC3Mask) != 0) {
                sb.append(String.format(" %03x/%03x", mccMnc[0], mccMnc[1] & 0xfff));
            }
            else {
                sb.append(String.format(" %03x/%02x", mccMnc[0], mccMnc[1]));
            }
        }
        return sb.toString();
    }
}
