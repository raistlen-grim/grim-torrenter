package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.mse.MseHandshake;
import com.grimtorrenter.engine.mse.MseInboundResult;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.Have;
import com.grimtorrenter.engine.peerwire.Interested;
import com.grimtorrenter.engine.peerwire.PeerMessage;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.peerwire.Unchoke;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses a raw ServerSocket as a fake remote peer, driving the handshake and
 * message exchange manually with PeerWireCodec (already covered by its own
 * tests) so this class tests PeerConnection's behavior built on top of it.
 */
class PeerConnectionTest {

    private ServerSocket serverSocket;

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

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

    private PeerAddress startFakePeerServer() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        return new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
    }

    private static final class RecordingListener implements PeerConnectionListener {
        final List<PeerMessage> messages = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> disconnectCause = new AtomicReference<>();
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        final AtomicInteger disconnectCount = new AtomicInteger();
        volatile CountDownLatch messageLatch = new CountDownLatch(0);

        @Override
        public void onMessage(PeerConnection connection, PeerMessage message) {
            messages.add(message);
            messageLatch.countDown();
        }

        @Override
        public void onDisconnected(PeerConnection connection, Throwable cause) {
            disconnectCause.set(cause);
            disconnectCount.incrementAndGet();
            disconnectLatch.countDown();
        }
    }

    @Test
    void connectsAndExchangesHandshake() throws Exception {
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                Handshake received = PeerWireCodec.readHandshake(socket.getInputStream());
                assertEquals(fakeInfoHash(), received.infoHash());
                assertEquals(ourPeerId(), received.peerId());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
                Thread.sleep(200);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        try (PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of())) {
            assertEquals(theirPeerId(), connection.remotePeerId());
            assertTrue(connection.amChoking());
            assertTrue(connection.peerChoking());
            assertFalse(connection.amInterested());
            assertFalse(connection.peerInterested());
        }
        fakePeer.join(2000);
    }

    /** Mirrors connectsAndExchangesHandshake but with the roles reversed: the fake remote
     * peer is the one who dials in and sends its handshake first (matching a real inbound
     * connection - the initiator always speaks first), and accept() is the side completing
     * it from ours, the way PeerServer would after already reading the remote handshake
     * itself to route the connection. Everything past the handshake (read loop, state)
     * shares the exact same code as connect(), so this doesn't re-test all of that - just
     * that accept() itself does the reversed handshake correctly. See design_docs/0038. */
    @Test
    void acceptsInboundConnectionAndExchangesHandshake() throws Exception {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int ourPort = serverSocket.getLocalPort();

        AtomicReference<Handshake> theirReceivedHandshake = new AtomicReference<>();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), ourPort)) {
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
                theirReceivedHandshake.set(PeerWireCodec.readHandshake(socket.getInputStream()));
                Thread.sleep(200);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        Socket accepted = serverSocket.accept();
        Handshake remoteHandshake = PeerWireCodec.readHandshake(accepted.getInputStream());

        RecordingListener listener = new RecordingListener();
        try (PeerConnection connection = PeerConnection.accept(accepted, remoteHandshake, ourPeerId(), listener, Map.of())) {
            assertEquals(theirPeerId(), connection.remotePeerId());
            assertTrue(connection.amChoking());
            assertTrue(connection.peerChoking());
        }
        fakePeer.join(2000);
        assertEquals(ourPeerId(), theirReceivedHandshake.get().peerId());
    }

    /** Uses MseHandshake.negotiateInbound() directly (real production code, not a hand-rolled
     * stub) to stand in for a real MSE-capable remote peer - exercises connect()'s PREFERRED
     * path all the way through a successful negotiation. See design_docs/0052. */
    @Test
    void connectWithPreferredEncryptionNegotiatesMseWhenThePeerSupportsIt() throws Exception {
        PeerAddress address = startFakePeerServer();
        SecureRandom random = new SecureRandom();
        RecordingListener listener = new RecordingListener();

        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                MseInboundResult negotiated = MseHandshake.negotiateInbound(
                        socket.getInputStream(), socket.getOutputStream(), Set.of(fakeInfoHash()), false, random);
                Handshake received = PeerWireCodec.readHandshake(negotiated.in());
                assertEquals(fakeInfoHash(), received.infoHash());
                PeerWireCodec.writeHandshake(negotiated.out(), Handshake.of(fakeInfoHash(), theirPeerId()));
                Thread.sleep(200);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        try (PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener,
                Map.of(), RateLimiters.unlimited(), EncryptionMode.PREFERRED)) {
            assertEquals(theirPeerId(), connection.remotePeerId());
        }
        fakePeer.join(2000);
    }

    /** A legacy peer with no MSE awareness at all can't parse our DH public key as a
     * plaintext handshake and just gives up on that first connection - connect()'s PREFERRED
     * fallback needs to notice that failure and retry with a fresh, plain connection rather
     * than giving up outright. See design_docs/0052. */
    @Test
    void connectWithPreferredEncryptionFallsBackToPlaintextWhenThePeerDoesNotSupportMse() throws Exception {
        serverSocket = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        PeerAddress address = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        RecordingListener listener = new RecordingListener();

        Thread fakePeer = new Thread(() -> {
            try {
                try (Socket firstAttempt = serverSocket.accept()) {
                    try {
                        PeerWireCodec.readHandshake(firstAttempt.getInputStream());
                    } catch (Exception expected) {
                        // Not a valid plaintext handshake (it's our MSE Diffie-Hellman public
                        // key) - exactly what a legacy peer would see and give up on.
                    }
                }
                try (Socket secondAttempt = serverSocket.accept()) {
                    Handshake received = PeerWireCodec.readHandshake(secondAttempt.getInputStream());
                    assertEquals(fakeInfoHash(), received.infoHash());
                    PeerWireCodec.writeHandshake(secondAttempt.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        try (PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener,
                Map.of(), RateLimiters.unlimited(), EncryptionMode.PREFERRED)) {
            assertEquals(theirPeerId(), connection.remotePeerId());
        }
        fakePeer.join(3000);
    }

    /** Same legacy-peer scenario as the PREFERRED fallback test above, but REQUIRED has no
     * fallback to retry with - the connection attempt just fails. See design_docs/0052. */
    @Test
    void connectWithRequiredEncryptionFailsWhenThePeerDoesNotSupportMse() throws Exception {
        PeerAddress address = startFakePeerServer();
        RecordingListener listener = new RecordingListener();

        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                try {
                    PeerWireCodec.readHandshake(socket.getInputStream());
                } catch (Exception expected) {
                    // See connectWithPreferredEncryptionFallsBackToPlaintextWhenThePeerDoesNotSupportMse.
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        assertThrows(IOException.class, () -> PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener,
                Map.of(), RateLimiters.unlimited(), EncryptionMode.REQUIRED));
        fakePeer.join(2000);
    }

    @Test
    void rejectsMismatchedInfoHash() throws Exception {
        PeerAddress address = startFakePeerServer();
        InfoHash wrongHash = InfoHash.of(fill(20, 9));
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(wrongHash, theirPeerId()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        assertThrows(PeerConnectionException.class,
                () -> PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of()));
        fakePeer.join(2000);
    }

    @Test
    void updatesStateFromIncomingMessages() throws Exception {
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Have(3));
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        listener.messageLatch = new CountDownLatch(2);
        try (PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of())) {
            assertTrue(listener.messageLatch.await(2, TimeUnit.SECONDS));
            assertFalse(connection.peerChoking());
            assertTrue(connection.peerHasPiece(3));
            assertFalse(connection.peerHasPiece(4));
        }
        fakePeer.join(2000);
    }

    @Test
    void sendInterestedUpdatesLocalStateAndWritesMessage() throws Exception {
        PeerAddress address = startFakePeerServer();
        AtomicReference<PeerMessage> received = new AtomicReference<>();
        CountDownLatch receivedLatch = new CountDownLatch(1);
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
                received.set(PeerWireCodec.readMessage(socket.getInputStream()));
                receivedLatch.countDown();
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        try (PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of())) {
            connection.sendInterested();
            assertTrue(connection.amInterested());
            assertTrue(receivedLatch.await(2, TimeUnit.SECONDS));
            assertEquals(new Interested(), received.get());
        }
        fakePeer.join(2000);
    }

    @Test
    void notifiesListenerWhenPeerClosesConnection() throws Exception {
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of());

        assertTrue(listener.disconnectLatch.await(2, TimeUnit.SECONDS));
        assertNotNull(listener.disconnectCause.get());
        fakePeer.join(2000);
    }

    @Test
    void closeIsIdempotentAndReportsNullCause() throws Exception {
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
                Thread.sleep(1000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of());

        connection.close();
        connection.close();

        assertNull(listener.disconnectCause.get());
        assertEquals(1, listener.disconnectCount.get());
        fakePeer.join(2000);
    }

    @Test
    void exchangesExtendedHandshakeWhenBothSidesSupportIt() throws Exception {
        PeerAddress address = startFakePeerServer();
        AtomicReference<PeerMessage> receivedFromUs = new AtomicReference<>();
        CountDownLatch receivedLatch = new CountDownLatch(1);
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(),
                        Handshake.withExtensionProtocol(fakeInfoHash(), theirPeerId()));

                BDictionary theirHandshake = new BDictionary(Map.of(
                        BString.of("m"), new BDictionary(Map.of(BString.of("ut_metadata"), new BInteger(3)))));
                PeerWireCodec.writeMessage(socket.getOutputStream(),
                        new Extended(0, BencodeEncoder.encode(theirHandshake)));

                receivedFromUs.set(PeerWireCodec.readMessage(socket.getInputStream()));
                receivedLatch.countDown();
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        try (PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of())) {
            assertTrue(receivedLatch.await(2, TimeUnit.SECONDS));
            // {"m": {}} bencoded: d + "1:m" (key) + "de" (empty dict value) + e
            assertEquals(new Extended(0, "d1:mdee".getBytes()), receivedFromUs.get());

            assertTrue(awaitRemoteExtensionId(connection, "ut_metadata").isPresent());
            assertEquals(3, connection.remoteExtensionId("ut_metadata").orElseThrow());
            assertTrue(connection.remoteMetadataSize().isEmpty());
        }
        fakePeer.join(2000);
    }

    @Test
    void sendsAdvertisedExtensionsInOurExtendedHandshake() throws Exception {
        PeerAddress address = startFakePeerServer();
        AtomicReference<PeerMessage> receivedFromUs = new AtomicReference<>();
        CountDownLatch receivedLatch = new CountDownLatch(1);
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(),
                        Handshake.withExtensionProtocol(fakeInfoHash(), theirPeerId()));
                receivedFromUs.set(PeerWireCodec.readMessage(socket.getInputStream()));
                receivedLatch.countDown();
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        try (PeerConnection connection = PeerConnection.connect(
                address, fakeInfoHash(), ourPeerId(), listener, Map.of("ut_metadata", 1))) {
            assertTrue(receivedLatch.await(2, TimeUnit.SECONDS));
            // {"m": {"ut_metadata": 1}} bencoded
            assertEquals(new Extended(0, "d1:md11:ut_metadatai1eee".getBytes()), receivedFromUs.get());
        }
        fakePeer.join(2000);
    }

    @Test
    void capturesRemoteMetadataSizeFromExtendedHandshake() throws Exception {
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(),
                        Handshake.withExtensionProtocol(fakeInfoHash(), theirPeerId()));

                BDictionary theirHandshake = new BDictionary(Map.of(
                        BString.of("m"), new BDictionary(Map.of(BString.of("ut_metadata"), new BInteger(1))),
                        BString.of("metadata_size"), new BInteger(1234)));
                PeerWireCodec.writeMessage(socket.getOutputStream(),
                        new Extended(0, BencodeEncoder.encode(theirHandshake)));
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        try (PeerConnection connection = PeerConnection.connect(
                address, fakeInfoHash(), ourPeerId(), listener, Map.of("ut_metadata", 1))) {
            long deadline = System.currentTimeMillis() + 2000;
            while (connection.remoteMetadataSize().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1234, connection.remoteMetadataSize().orElseThrow());
        }
        fakePeer.join(2000);
    }

    @Test
    void doesNotSendExtendedHandshakeWhenPeerDoesNotSupportIt() throws Exception {
        // sendInterestedUpdatesLocalStateAndWritesMessage already asserts the very next
        // message the fake peer reads after the handshake is exactly Interested - this
        // test makes the "no extra message beforehand" guarantee explicit instead of
        // just implicit in that one.
        PeerAddress address = startFakePeerServer();
        AtomicReference<PeerMessage> receivedFromUs = new AtomicReference<>();
        CountDownLatch receivedLatch = new CountDownLatch(1);
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(fakeInfoHash(), theirPeerId()));
                receivedFromUs.set(PeerWireCodec.readMessage(socket.getInputStream()));
                receivedLatch.countDown();
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        RecordingListener listener = new RecordingListener();
        try (PeerConnection connection = PeerConnection.connect(address, fakeInfoHash(), ourPeerId(), listener, Map.of())) {
            connection.sendInterested();
            assertTrue(receivedLatch.await(2, TimeUnit.SECONDS));
            assertEquals(new Interested(), receivedFromUs.get());
        }
        fakePeer.join(2000);
    }

    private static OptionalInt awaitRemoteExtensionId(PeerConnection connection, String name)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        OptionalInt id;
        while ((id = connection.remoteExtensionId(name)).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return id;
    }
}
