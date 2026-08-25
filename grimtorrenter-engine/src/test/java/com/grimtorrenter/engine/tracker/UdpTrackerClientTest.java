package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses a raw DatagramSocket as a fake UDP tracker, speaking BEP 15
 * directly - same testing philosophy as HttpTrackerClientTest/
 * PeerConnectionTest (a real local server, no mocking library).
 */
class UdpTrackerClientTest {

    private static final long PROTOCOL_ID = 0x41727101980L;
    private static final long FAKE_CONNECTION_ID = 123456789L;

    private DatagramSocket fakeTracker;
    private Thread fakeTrackerThread;

    @AfterEach
    void tearDown() {
        if (fakeTracker != null && !fakeTracker.isClosed()) {
            fakeTracker.close();
        }
    }

    private static byte[] fill(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    private static TrackerRequest fakeRequest() {
        return new TrackerRequest(
                InfoHash.of(fill(20, 1)), PeerId.of(fill(20, 2)), 6881, 0, 0, 1000, TrackerEvent.STARTED, 50);
    }

    private int startFakeTracker() throws IOException {
        fakeTracker = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        return fakeTracker.getLocalPort();
    }

    private DatagramPacket receive() throws IOException {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        fakeTracker.receive(packet);
        return packet;
    }

    private void reply(DatagramPacket toWhom, byte[] data) throws IOException {
        fakeTracker.send(new DatagramPacket(data, data.length, toWhom.getAddress(), toWhom.getPort()));
    }

    @Test
    void announceReturnsCompactPeersFromFakeTracker() throws Exception {
        int port = startFakeTracker();
        fakeTrackerThread = new Thread(() -> {
            try {
                DatagramPacket connectPacket = receive();
                ByteBuffer connectReq = ByteBuffer.wrap(connectPacket.getData(), 0, connectPacket.getLength());
                assertEquals(PROTOCOL_ID, connectReq.getLong());
                assertEquals(0, connectReq.getInt()); // action = connect
                int connectTransactionId = connectReq.getInt();

                ByteBuffer connectResp = ByteBuffer.allocate(16);
                connectResp.putInt(0);
                connectResp.putInt(connectTransactionId);
                connectResp.putLong(FAKE_CONNECTION_ID);
                reply(connectPacket, connectResp.array());

                DatagramPacket announcePacket = receive();
                ByteBuffer announceReq = ByteBuffer.wrap(announcePacket.getData(), 0, announcePacket.getLength());
                assertEquals(FAKE_CONNECTION_ID, announceReq.getLong());
                assertEquals(1, announceReq.getInt()); // action = announce
                int announceTransactionId = announceReq.getInt();

                byte[] compactPeer = {10, 0, 0, 1, 0x1A, (byte) 0xE1}; // 10.0.0.1:6881
                ByteBuffer announceResp = ByteBuffer.allocate(20 + compactPeer.length);
                announceResp.putInt(1);
                announceResp.putInt(announceTransactionId);
                announceResp.putInt(1800); // interval
                announceResp.putInt(2); // leechers
                announceResp.putInt(5); // seeders
                announceResp.put(compactPeer);
                reply(announcePacket, announceResp.array());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        fakeTrackerThread.start();

        UdpTrackerClient client = new UdpTrackerClient("udp://127.0.0.1:" + port + "/announce");
        TrackerResponse response = client.announce(fakeRequest());

        assertEquals(1800, response.interval());
        assertEquals(5, response.complete());
        assertEquals(2, response.incomplete());
        assertEquals(1, response.peers().size());
        assertEquals("10.0.0.1", response.peers().get(0).address().getHostAddress());
        assertEquals(6881, response.peers().get(0).port());

        fakeTrackerThread.join(2000);
    }

    @Test
    void throwsWithTrackerErrorMessage() throws Exception {
        int port = startFakeTracker();
        fakeTrackerThread = new Thread(() -> {
            try {
                DatagramPacket connectPacket = receive();
                ByteBuffer connectReq = ByteBuffer.wrap(connectPacket.getData(), 0, connectPacket.getLength());
                connectReq.getLong();
                connectReq.getInt();
                int transactionId = connectReq.getInt();

                byte[] message = "bad request".getBytes(StandardCharsets.UTF_8);
                ByteBuffer errorResp = ByteBuffer.allocate(8 + message.length);
                errorResp.putInt(3); // action = error
                errorResp.putInt(transactionId);
                errorResp.put(message);
                reply(connectPacket, errorResp.array());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        fakeTrackerThread.start();

        UdpTrackerClient client = new UdpTrackerClient("udp://127.0.0.1:" + port + "/announce");
        TrackerException ex = assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        assertTrue(ex.getMessage().contains("bad request"));

        fakeTrackerThread.join(2000);
    }

    @Test
    void throwsAfterRetriesExhaustedWhenTrackerNeverResponds() throws IOException {
        int port = startFakeTracker(); // bound, but nothing ever reads/replies

        UdpTrackerClient client = new UdpTrackerClient("udp://127.0.0.1:" + port + "/announce", 100, 2);

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
    }

    @Test
    void rejectsNonUdpUrl() {
        assertThrows(IllegalArgumentException.class, () -> new UdpTrackerClient("http://example.com/announce"));
    }
}
