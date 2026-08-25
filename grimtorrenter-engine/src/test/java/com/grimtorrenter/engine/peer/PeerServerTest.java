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

import java.net.InetAddress;
import java.net.Socket;
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
     * than routing it through - see design_docs/0052. */
    @Test
    void requiredEncryptionRejectsAPlaintextConnectionAttempt() throws Exception {
        InfoHash knownHash = infoHashOf(1);
        server = new PeerServer(0, hash -> Optional.<IncomingConnectionHandler>of((socket, in, out, handshake) -> {
                    throw new AssertionError("should never be routed - REQUIRED must reject this connection first");
                }),
                () -> EncryptionMode.REQUIRED, () -> Set.of(knownHash));

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            PeerWireCodec.writeHandshake(client.getOutputStream(), Handshake.of(knownHash, peerIdOf(50)));

            assertEquals(-1, client.getInputStream().read());
        }
    }
}
