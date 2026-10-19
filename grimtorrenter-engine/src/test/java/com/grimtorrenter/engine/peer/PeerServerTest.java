package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.mse.MseHandshake;
import com.grimtorrenter.engine.mse.MseOutboundResult;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.tracker.PeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives PeerServer with a raw client Socket writing a handshake directly (PeerWireCodec
 * already has its own tests) - this class tests PeerServer's own job: read just enough to
 * learn the info hash, then route or reject. See design_docs/0038.
 */
class PeerServerTest {

    private PeerServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static byte[] fill(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    private static InfoHash infoHashOf(int seed) {
        return InfoHash.of(fill(20, seed));
    }

    private static PeerId peerIdOf(int seed) {
        return PeerId.of(fill(20, seed));
    }

    @Test
    void routesAnAcceptedConnectionToTheHandlerForItsInfoHash() throws Exception {
        InfoHash knownHash = infoHashOf(1);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<Handshake> receivedHandshake = new AtomicReference<>();
        AtomicReference<Socket> receivedSocket = new AtomicReference<>();

        server = new PeerServer(0, hash -> hash.equals(knownHash)
                ? Optional.<IncomingConnectionHandler>of((socket, in, out, handshake) -> {
                    receivedSocket.set(socket);
                    receivedHandshake.set(handshake);
                    handled.countDown();
                })
                : Optional.empty());

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            PeerWireCodec.writeHandshake(client.getOutputStream(), Handshake.of(knownHash, peerIdOf(50)));

            assertTrue(handled.await(2, TimeUnit.SECONDS));
            assertEquals(knownHash, receivedHandshake.get().infoHash());
            assertEquals(peerIdOf(50), receivedHandshake.get().peerId());
            assertNotNull(receivedSocket.get());
        }
    }

    /** design_docs/0078 - a blocked address is closed before a single byte is read or a handler is
     * looked up (so it never costs an MSE Diffie-Hellman exchange). */
    @Test
    void aBlockedAddressIsClosedBeforeAnythingIsReadOrRouted() throws Exception {
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger recorded = new java.util.concurrent.atomic.AtomicInteger();
        server = new PeerServer(0, hash -> {
            lookups.incrementAndGet();
            return Optional.empty();
        });
        server.setIpFilter(new com.grimtorrenter.engine.blocklist.IpFilter() {
            @Override
            public boolean isBlocked(InetAddress address) {
                return true;
            }

            @Override
            public void recordBlocked() {
                recorded.incrementAndGet();
            }
        });

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            try {
                PeerWireCodec.writeHandshake(client.getOutputStream(), Handshake.of(infoHashOf(1), peerIdOf(50)));
                assertEquals(-1, client.getInputStream().read());
            } catch (java.net.SocketException expected) {
                // The server closes the moment it accepts, which is the point. Whether the OS then
                // reports that on our write (broken pipe - a fast machine closes before we've
                // written) or our read (reset - our handshake was left unread) is a race.
                // recordBlocked() runs before the close, so the counts below are final either way.
            }
        }

        assertEquals(0, lookups.get());
        assertEquals(1, recorded.get());
    }

    @Test
    void closesTheConnectionWhenNoHandlerKnowsTheInfoHash() throws Exception {
        server = new PeerServer(0, hash -> Optional.empty());

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            PeerWireCodec.writeHandshake(client.getOutputStream(), Handshake.of(infoHashOf(9), peerIdOf(50)));

            // PeerServer closes its end when nothing recognizes the info hash - the next
            // read here hits EOF rather than hanging or a protocol-level rejection.
            assertEquals(-1, client.getInputStream().read());
        }
    }

    /** Each silent connection holds a socket and a thread for the handshake timeout - past
     * MAX_PENDING_HANDSHAKES at once, a new one is closed straight away rather than queued. See
     * design_docs/0092. */
    @Test
    void closesConnectionsBeyondThePendingHandshakeCapImmediately() throws Exception {
        server = new PeerServer(0, hash -> Optional.empty());
        java.util.List<Socket> silent = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < PeerServer.MAX_PENDING_HANDSHAKES; i++) {
                silent.add(new Socket(InetAddress.getLoopbackAddress(), server.port()));
            }
            try (Socket overflow = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                overflow.setSoTimeout(5000); // well under the 10s handshake timeout
                assertEquals(-1, overflow.getInputStream().read());
            }
        } finally {
            for (Socket socket : silent) {
                socket.close();
            }
        }
    }

    /** Confirms the peek-and-branch (design_docs/0052) correctly recovers the info hash from
     * a real MSE negotiation (not a plaintext handshake) and still routes to the right
     * handler - the same assertion routesAnAcceptedConnectionToTheHandlerForItsInfoHash makes
     * for the plaintext path, but driven by MseHandshake.negotiateOutbound() (real production
     * code standing in for a real MSE-capable remote peer) instead of PeerWireCodec directly. */
    @Test
    void routesAnMseNegotiatedConnectionToTheHandlerForItsInfoHash() throws Exception {
        InfoHash knownHash = infoHashOf(1);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<Handshake> receivedHandshake = new AtomicReference<>();

        server = new PeerServer(0, hash -> hash.equals(knownHash)
                ? Optional.<IncomingConnectionHandler>of((socket, in, out, handshake) -> {
                    receivedHandshake.set(handshake);
                    handled.countDown();
                })
                : Optional.empty(),
                () -> EncryptionMode.PREFERRED, () -> Set.of(knownHash));

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            MseOutboundResult negotiated = MseHandshake.negotiateOutbound(
                    client.getInputStream(), client.getOutputStream(), knownHash, false, new SecureRandom());
            PeerWireCodec.writeHandshake(negotiated.out(), Handshake.of(knownHash, peerIdOf(50)));

            assertTrue(handled.await(2, TimeUnit.SECONDS));
            assertEquals(knownHash, receivedHandshake.get().infoHash());
            assertEquals(peerIdOf(50), receivedHandshake.get().peerId());
        }
    }

    /** REQUIRED mode rejects anything that peeks as a plaintext handshake outright, rather
     * than routing it through - see design_docs/0052.
     *
     * <p>The server decides from the first 20 bytes and closes straight away, so the handshake
     * goes out in one write - written piece by piece, a later piece could hit the closed socket
     * and fail with a broken pipe. Closing with the rest of the handshake still unread makes the
     * server's side send a reset, so a reset counts as rejected as much as end-of-stream does. */
    @Test
    void requiredEncryptionRejectsAPlaintextConnectionAttempt() throws Exception {
        InfoHash knownHash = infoHashOf(1);
        server = new PeerServer(0, hash -> Optional.<IncomingConnectionHandler>of((socket, in, out, handshake) -> {
                    throw new AssertionError("should never be routed - REQUIRED must reject this connection first");
                }),
                () -> EncryptionMode.REQUIRED, () -> Set.of(knownHash));

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            PeerWireCodec.writeHandshake(handshake, Handshake.of(knownHash, peerIdOf(50)));
            client.getOutputStream().write(handshake.toByteArray());

            try {
                assertEquals(-1, client.getInputStream().read());
            } catch (SocketException reset) {
                // Rejected with a reset - see above.
            }
        }
    }
}
