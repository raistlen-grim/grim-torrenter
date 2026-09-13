package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.Interested;
import com.grimtorrenter.engine.peerwire.PeerMessage;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import com.grimtorrenter.engine.utp.UtpSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves design_docs/0074's slice 2 facade actually works end to end - a real UtpSocket pair
 * over real loopback DatagramSockets, carrying a real BT handshake and a real wire message,
 * exactly the thing slice 1's own tests (UtpSocket in isolation) couldn't prove. Uses the
 * package-private connectViaUtp()/acceptViaUtp() (not yet a production entry point - slices 3-4
 * decide that).
 */
class PeerConnectionUtpTransportTest {

    private static byte[] fill(int length, int start) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }

    private static InfoHash fakeInfoHash() {
        return InfoHash.of(fill(20, 0));
    }

    private static PeerId ourPeerId() {
        return PeerId.of(fill(20, 50));
    }

    private static PeerId theirPeerId() {
        return PeerId.of(fill(20, 100));
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    /** No CountDownLatch here, deliberately - unlike every existing PeerConnectionTest case
     * (where the fake peer side hand-writes Handshake.of(), never advertising extension-protocol
     * support), both sides here are real PeerConnections built from Handshake.
     * withExtensionProtocol() - each automatically fires its own Extended(0, ...) handshake
     * message right after the BT handshake completes (sendExtendedHandshakeIfSupported()),
     * racing with whatever this test explicitly sends next. Polling for a specific message
     * (mirroring PeerConnectionTest's own awaitRemoteExtensionId()) sidesteps both the exact
     * ordering and exact count of messages actually received. */
    private static final class RecordingListener implements PeerConnectionListener {
        final List<PeerMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(PeerConnection connection, PeerMessage message) {
            messages.add(message);
        }

        @Override
        public void onDisconnected(PeerConnection connection, Throwable cause) {
        }
    }

    private static void awaitMessage(RecordingListener listener, PeerMessage expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!listener.messages.contains(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(listener.messages.contains(expected), "never received " + expected);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aUtpBackedConnectionCompletesARealHandshakeAndExchangesAMessage() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<UtpSocket> acceptorUtpFuture = executor.submit(() -> UtpSocket.accept(acceptorSocket));
            UtpSocket initiatorUtp = UtpSocket.connect(initiatorSocket, loopback(acceptorSocket.getLocalPort()));
            UtpSocket acceptorUtp = acceptorUtpFuture.get(10, TimeUnit.SECONDS);

            RecordingListener initiatorListener = new RecordingListener();
            RecordingListener acceptorListener = new RecordingListener();

            // acceptViaUtp() mirrors PeerServer's real role: read the remote's BT handshake
            // first (needed in real life to route the connection by info hash - design_docs/
            // 0038), then hand it to PeerConnection to write ours back and take over.
            Future<PeerConnection> acceptorConnectionFuture = executor.submit(() -> {
                Handshake remoteHandshake = PeerWireCodec.readHandshake(acceptorUtp.getInputStream());
                return PeerConnection.acceptViaUtp(acceptorUtp, remoteHandshake, theirPeerId(), acceptorListener,
                        Map.of(), RateLimiters.unlimited());
            });

            PeerAddress acceptorAddress = new PeerAddress(InetAddress.getLoopbackAddress(),
                    acceptorSocket.getLocalPort());
            try (PeerConnection initiatorConnection = PeerConnection.connectViaUtp(initiatorUtp, acceptorAddress,
                    fakeInfoHash(), ourPeerId(), initiatorListener, Map.of(), RateLimiters.unlimited(),
                    PeerSource.UNKNOWN);
                 PeerConnection acceptorConnection = acceptorConnectionFuture.get(10, TimeUnit.SECONDS)) {

                // The initiator's remote is the acceptor (identifies as theirPeerId()); the
                // acceptor's remote is the initiator (identifies as ourPeerId()) - each side
                // sees the other's own declared identity, not its own.
                assertEquals(theirPeerId(), initiatorConnection.remotePeerId());
                assertEquals(ourPeerId(), acceptorConnection.remotePeerId());

                initiatorConnection.sendInterested();
                awaitMessage(acceptorListener, new Interested());
            }
        } finally {
            executor.shutdown();
        }
    }
}
