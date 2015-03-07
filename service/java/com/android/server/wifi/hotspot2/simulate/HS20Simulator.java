package com.android.server.wifi.hotspot2.simulate;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.ANQPFactory;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.VenueNameElement;
import com.android.server.wifi.hotspot2.NetworkInfo;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.SelectionManager;
import com.android.server.wifi.hotspot2.omadm.MOManager;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

public class HS20Simulator {

    private static final File SPSDir = new File(System.getProperty("user.home"), "sps-test");
    private static final String PPSFile = "pps.data";

    private static final NetworkInfo[] Networks = {
            new NetworkInfo(
                    "Horse", "Animals",
                    0x112233445560L,
                    1, 1, 1, NetworkInfo.Ant.FreePublic, true,
                    VenueNameElement.VenueGroup.Business, VenueNameElement.VenueType.AmusementPark,
                    NetworkInfo.HSRelease.R1, 42, new long[]{0x111111L, 0x222222L}),

            new NetworkInfo(
                    "Dog", "Animals",
                    0x112233445561L,
                    1, 1, 1, NetworkInfo.Ant.FreePublic, true,
                    VenueNameElement.VenueGroup.Business, VenueNameElement.VenueType.AmusementPark,
                    NetworkInfo.HSRelease.R1, 43, new long[]{0x111112L, 0x222223L})
    };

    private final MOManager mMOManager;
    private final SelectionManager mSelectionManager;

    public HS20Simulator(String... credFiles) throws IOException, SAXException {
        if (!SPSDir.exists()) {
            if (!SPSDir.mkdir()) {
                throw new IOException("Failed to create " + SPSDir);
            }
        }

        File ppsFile = new File(SPSDir, PPSFile);

        MOManager tempManager = new MOManager(ppsFile);

        ppsFile = tempManager.getPpsFile();
        if (ppsFile.exists()) {
            if (!ppsFile.delete()) {
                throw new IOException("Failed to delete old file " + ppsFile);
            }
        }

        System.out.println("Provisioning...");
        for (String credFile : credFiles) {
            BufferedInputStream in = new BufferedInputStream(new FileInputStream(credFile));
            tempManager.addSP(in);
            in.close();
        }

        System.out.println("Reloading from persistent storage...");
        mMOManager = new MOManager(ppsFile);

        mSelectionManager = new SelectionManager(mMOManager.loadAllSPs());
    }

    private void executeTests() throws IOException {
        System.out.println("--- Running matching...");
        for (NetworkInfo networkInfo : Networks) {
            checkNetwork(networkInfo);
        }
    }

    private void checkNetwork(NetworkInfo networkInfo) throws IOException {
        Map<HomeSP, PasspointMatch> result = mSelectionManager.matchNetwork(networkInfo);

        boolean incomplete = false;
        System.out.println("Initial match of " + networkInfo.getSSID() + ":");
        for (Map.Entry<HomeSP, PasspointMatch> entry : result.entrySet()) {
            if (entry.getValue() == PasspointMatch.Incomplete) {
                incomplete = true;
            }
            System.out.println(entry.getKey().getFQDN() + ": " + entry.getValue());
        }

        if (incomplete) {
            List<ANQPElement> anqp =
                    performANQPQuery(mSelectionManager.generateANQPQuery(networkInfo, 0));
            Map<HomeSP, PasspointMatch> result2 =
                    mSelectionManager.notifyANQPResponse(networkInfo, anqp);

            System.out.println("After ANQP on " + networkInfo.getSSID() + ":");
            for (Map.Entry<HomeSP, PasspointMatch> entry2 : result2.entrySet()) {
                System.out.println(entry2.getKey().getFQDN() + ": " + entry2.getValue());
            }
        }
    }

    private List<ANQPElement> performANQPQuery(ByteBuffer request) throws IOException {
        System.out.println("Connecting...");
        Socket sock = new Socket(InetAddress.getLoopbackAddress(), 6104);
        BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream());
        byte[] requestBytes = new byte[request.remaining()];
        request.get(requestBytes);
        out.write(requestBytes);
        out.flush();

        BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
        ByteBuffer payload = getResponse(in);

        return ANQPFactory.parsePayload(payload);
    }

    private static ByteBuffer getResponse(InputStream in) throws IOException {
        ByteBuffer lengthBuffer = read(in, 2);
        int length = lengthBuffer.getShort() & Constants.SHORT_MASK;
        System.out.println("Length " + length);

        return read(in, length);
    }

    private static ByteBuffer read(InputStream in, int length) throws IOException {
        byte[] payload = new byte[length];
        int position = 0;
        while (position < length) {
            int amount = in.read(payload, position, length - position);
            if (amount <= 0) {
                throw new EOFException("Got " + amount);
            }
            position += amount;
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static void main(String[] args) throws IOException, SAXException {
        HS20Simulator hs20Simulator = new HS20Simulator(args);
        hs20Simulator.executeTests();
    }
}
