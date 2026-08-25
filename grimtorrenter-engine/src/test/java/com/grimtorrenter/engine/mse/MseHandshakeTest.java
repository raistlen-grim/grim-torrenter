package com.grimtorrenter.engine.mse;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Drives negotiateOutbound/negotiateInbound against each other over real loopback sockets -
 * same real-socket, real-thread convention PeerConnectionTest/PeerServerTest already use, so
 * this exercises MseHandshake exactly as PeerConnection/PeerServer will call it (design_docs/0052).
 * Some tests hand-roll a "fake initiator" directly against the wire (mirroring how those two
 * test classes drive one side with PeerWireCodec directly) specifically to exercise behavior
 * MseHandshake's own negotiateOutbound would never produce on its own - a peer that embeds its
 * handshake in IA, or one that offers only plaintext.
 */
class MseHandshakeTest {

    private final SecureRandom random = new SecureRandom();
    private final InfoHash infoHash = InfoHash.of(sha1(bytes("a real torrent")));
    private ServerSocket serverSocket;

    @AfterEach
    void closeServer() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    @Test
    void bothSidesPreferringEncryptionNegotiateRc4AndCanExchangeRealMessagesBothWays() throws Exception {
        assertRoundTrip(false, false, Set.of(infoHash));
    }

    @Test
    void bothSidesRequiringEncryptionNegotiateRc4AndCanExchangeRealMessagesBothWays() throws Exception {
        assertRoundTrip(true, true, Set.of(infoHash));
    }

    @Test
    void receiverMatchesTheRightInfoHashAmongSeveralCandidates() throws Exception {
        InfoHash other1 = InfoHash.of(sha1(bytes("some other torrent")));
        InfoHash other2 = InfoHash.of(sha1(bytes("yet another torrent")));

        MseInboundResult result = negotiateOverLoopback(infoHash, Set.of(other1, infoHash, other2), false, false);

        assertEquals(infoHash, result.infoHash());
    }

    /** The receiver rejects before ever sending its reply (Packet4) - so the still-running
     * outbound side legitimately fails too, as an ordinary connection failure (it can't tell
     * "peer rejected" from "peer never replied" apart on the wire). Both sides run
     * concurrently and only the receiver's own diagnosis is asserted on, since a first attempt
     * at this test ran the outbound call synchronously first and saw its own EOFException
     * instead of ever reaching the inbound assertion. */
    @Test
    void receiverRejectsAConnectionForAnInfoHashItDoesNotRecognize() throws Exception {
        InfoHash unrelated = InfoHash.of(sha1(bytes("an unrelated torrent")));

        assertThrows(MseNegotiationException.class,
                () -> negotiateOverLoopback(infoHash, Set.of(unrelated), false, false));
    }

    /** The interop-critical case: a real peer initiating a connection to us commonly embeds
     * its own plaintext BT handshake in IA to save a round trip (MseHandshake's own
     * negotiateOutbound deliberately never does this - see design_docs/0052 - but a real
     * remote peer connecting to us might). negotiateInbound must hand that embedded content
     * back as the first bytes of the result stream, not silently discard it - discarding it
     * would leave the caller hanging, waiting for a handshake the peer already sent. */
    @Test
    void embeddedIaContentIsDeliveredAsTheFirstBytesOfTheResultStreamNotDiscarded() throws Exception {
        byte[] embeddedHandshake = "this stands in for a real embedded BT handshake"
                .getBytes(StandardCharsets.US_ASCII);

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        CompletableFuture<MseInboundResult> inboundFuture = CompletableFuture.supplyAsync(() -> {
            try (Socket accepted = serverSocket.accept()) {
                return MseHandshake.negotiateInbound(accepted.getInputStream(), accepted.getOutputStream(),
                        Set.of(infoHash), false, random);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
            sendFakeInitiatorHandshake(client, infoHash, PLAINTEXT_BIT | RC4_BIT, embeddedHandshake);

            MseInboundResult result = inboundFuture.get();

            byte[] received = result.in().readNBytes(embeddedHandshake.length);
            assertArrayEquals(embeddedHandshake, received);
        }
    }

    @Test
    void requiringEncryptionRejectsAPeerThatOnlyOffersPlaintext() throws Exception {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        CompletableFuture<MseInboundResult> inboundFuture = CompletableFuture.supplyAsync(() -> {
            try (Socket accepted = serverSocket.accept()) {
                return MseHandshake.negotiateInbound(accepted.getInputStream(), accepted.getOutputStream(),
                        Set.of(infoHash), true, random);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
            sendFakeInitiatorHandshake(client, infoHash, PLAINTEXT_BIT, new byte[0]);

            var executionException = assertThrows(java.util.concurrent.ExecutionException.class, inboundFuture::get);
            assertEquals(MseNegotiationException.class, executionException.getCause().getCause().getClass());
        }
    }

    private void assertRoundTrip(boolean outboundRequires, boolean inboundRequires, Set<InfoHash> candidates)
            throws Exception {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        CompletableFuture<byte[]> inboundReceived = new CompletableFuture<>();
        byte[] fromOutbound = bytes("hello from the outbound side");
        byte[] fromInbound = bytes("hello back from the inbound side");

        Thread acceptThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept()) {
                MseInboundResult result = MseHandshake.negotiateInbound(
                        accepted.getInputStream(), accepted.getOutputStream(), candidates, inboundRequires, random);
                byte[] received = result.in().readNBytes(fromOutbound.length);
                inboundReceived.complete(received);
                result.out().write(fromInbound);
                result.out().flush();
            } catch (IOException e) {
                inboundReceived.completeExceptionally(e);
            }
        }, "mse-test-acceptor");
        acceptThread.start();

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
            MseOutboundResult result = MseHandshake.negotiateOutbound(
                    client.getInputStream(), client.getOutputStream(), infoHash, outboundRequires, random);
            result.out().write(fromOutbound);
            result.out().flush();

            byte[] reply = result.in().readNBytes(fromInbound.length);
            assertArrayEquals(fromInbound, reply);
        }

        assertArrayEquals(fromOutbound, inboundReceived.get());
        acceptThread.join();
    }

    /** Runs both sides concurrently (not outbound-then-inbound) - when the receiver is
     * expected to reject, it does so before ever sending its reply, so a still-running
     * outbound call would otherwise see its own ordinary connection failure first and never
     * let the inbound side's own diagnosis be asserted on. The outbound side's own outcome is
     * deliberately not inspected here - callers that care about it (the happy-path tests) get
     * it for free by simply not failing this method with a hung/leaked future. */
    private MseInboundResult negotiateOverLoopback(InfoHash initiatorInfoHash, Set<InfoHash> candidates,
                                                     boolean outboundRequires, boolean inboundRequires) throws Exception {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        CompletableFuture<MseInboundResult> inboundFuture = CompletableFuture.supplyAsync(() -> {
            try (Socket accepted = serverSocket.accept()) {
                return MseHandshake.negotiateInbound(accepted.getInputStream(), accepted.getOutputStream(),
                        candidates, inboundRequires, random);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
            CompletableFuture<Void> outboundFuture = CompletableFuture.runAsync(() -> {
                try {
                    MseHandshake.negotiateOutbound(client.getInputStream(), client.getOutputStream(),
                            initiatorInfoHash, outboundRequires, random);
                } catch (IOException ignored) {
                    // A rejecting receiver never sends its reply, so a legitimate connection
                    // failure here is expected fallout, not this method's concern - see the
                    // class-level Javadoc on this method.
                }
            });

            try {
                MseInboundResult result = inboundFuture.get();
                outboundFuture.get();
                return result;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() != null && e.getCause().getCause() instanceof MseNegotiationException mse) {
                    throw mse;
                }
                throw e;
            }
        }
    }

    // --- Hand-rolled fake initiator, deliberately independent of MseHandshake.negotiateOutbound
    // so it can do things that method never would (embed IA, offer only plaintext). ---

    private static final byte[] VC = new byte[8];
    private static final int PLAINTEXT_BIT = 0x01;
    private static final int RC4_BIT = 0x02;

    private void sendFakeInitiatorHandshake(Socket socket, InfoHash forInfoHash, int cryptoProvide, byte[] ia)
            throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        DiffieHellman dh = new DiffieHellman(random);
        out.write(dh.publicKeyBytes());
        out.flush();

        byte[] peerPublicKeyBytes = in.readNBytes(DiffieHellman.PUBLIC_KEY_LENGTH_BYTES);
        BigInteger peerPublicKey = DiffieHellman.publicKeyFromBytes(peerPublicKeyBytes);
        byte[] sBytes = dh.sharedSecretBytes(peerPublicKey);
        byte[] infoHashBytes = forInfoHash.bytes();

        byte[] keyA = sha1(concat(bytes("keyA"), sBytes, infoHashBytes));

        byte[] req1 = sha1(concat(bytes("req1"), sBytes));
        byte[] req2Xor3 = xor(sha1(concat(bytes("req2"), infoHashBytes)), sha1(concat(bytes("req3"), sBytes)));

        Rc4Cipher outgoingCipher = new Rc4Cipher(keyA);
        outgoingCipher.discard(Rc4Cipher.DISCARD_BYTES);

        byte[] body = concat(VC, int32(cryptoProvide), uint16(0), uint16(ia.length), ia);
        outgoingCipher.process(body);

        out.write(req1);
        out.write(req2Xor3);
        out.write(body);
        out.flush();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    private static byte[] int32(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static byte[] uint16(int value) {
        return new byte[]{(byte) (value >>> 8), (byte) value};
    }

    private static byte[] bytes(String literal) {
        return literal.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
