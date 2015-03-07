package com.android.server.wifi.anqp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test ANQP code by talking to an ANQP server of a socket.
 */
public class TestDriver {

    private static final Constants.ANQPElementType[] QueryElements = {
            Constants.ANQPElementType.ANQPCapabilityList,
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPEmergencyNumber,
            Constants.ANQPElementType.ANQPNwkAuthType,
            Constants.ANQPElementType.ANQPRoamingConsortium,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPGeoLoc,
            Constants.ANQPElementType.ANQPCivicLoc,
            Constants.ANQPElementType.ANQPLocURI,
            Constants.ANQPElementType.ANQPDomName,
            Constants.ANQPElementType.ANQPEmergencyAlert,
            Constants.ANQPElementType.ANQPTDLSCap,
            Constants.ANQPElementType.ANQPEmergencyNAI,
            Constants.ANQPElementType.ANQPNeighborReport,

            Constants.ANQPElementType.HSCapabilityList,
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability,
            Constants.ANQPElementType.HSNAIHomeRealmQuery,
            Constants.ANQPElementType.HSOperatingclass,
            Constants.ANQPElementType.HSOSUProviders
    };

    public static void runTest() throws IOException {

        Set<Constants.ANQPElementType> elements =
                new HashSet<Constants.ANQPElementType>(QueryElements.length);
        elements.addAll(Arrays.asList(QueryElements));

        ByteBuffer request = ByteBuffer.allocate(8192);
        request.order(ByteOrder.LITTLE_ENDIAN);
        int lenPos = request.position();
        request.putShort((short) 0);
        ANQPFactory.buildQueryRequest(elements, request);
        request.putShort(lenPos, (short)( request.limit() - lenPos - Constants.BYTES_IN_SHORT ));

        byte[] requestBytes = new byte[request.remaining()];
        request.get(requestBytes);

        System.out.println( "Connecting...");
        Socket sock = new Socket(InetAddress.getLoopbackAddress(), 6104);
        BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream());
        out.write(requestBytes);
        out.flush();

        BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
        ByteBuffer lengthBuffer = read( in, 2 );
        int length = lengthBuffer.getShort() & Constants.SHORT_MASK;

        System.out.println( "Expecting another " + length);
        ByteBuffer payload = read(in, length);

        List<ANQPElement> anqpResult = ANQPFactory.parsePayload(payload);
        for ( ANQPElement element : anqpResult ) {
            System.out.println( element );
        }
    }

    private static ByteBuffer read(InputStream in, int length) throws IOException {
        byte[] payload = new byte[length];
        int position = 0;
        while ( position < length ) {
            int amount = in.read(payload, position, length - position);
            if ( amount <= 0 ) {
                throw new EOFException("Got " + amount);
            }
            position += amount;
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static void main(String[] args) throws IOException {
        runTest();
    }
}
