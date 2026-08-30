package com.grimtorrenter.engine.metadata;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Uses a raw ServerSocket as a fake remote peer (same approach as PeerConnectionTest),
 * driving the full handshake + BEP10 extended handshake + ut_metadata exchange manually
 * so this tests MetadataFetcher's orchestration on top of already-tested layers
 * (PeerConnection, UtMetadataCodec). Every fetch() call passes EncryptionMode.DISABLED - the
 * fake peer here only ever speaks a plain handshake, and MSE negotiation/fallback behavior is
 * PeerConnection's own concern, already covered by its own tests (design_docs/0052).
 */
class MetadataFetcherTest {

    private static final int BLOCK_SIZE = 16 * 1024;
    private static final int PEER_UT_METADATA_ID = 7;
    /** Must match MetadataFetcher's own private OUR_EXTENSION_ID - the fake peer plays
     * the role of "the other side," which has to address responses using the id *we*
     * advertised, not one of the fake peer's own choosing. */
    private static final int OUR_UT_METADATA_ID = 1;

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

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

    private static byte[] smallInfoDictBytes() {
        byte[] content = fill(20, 1);
        return encodeInfo("test.bin", content.length, sha1(content), content.length);
    }

    /** A "pieces" field large enough to push the encoded info-dict past one 16KiB
     * ut_metadata block, so tests using this exercise the multi-piece fetch/assembly
     * path, not just the single-piece case. */
    private static byte[] largeInfoDictBytes() {
        byte[] manyPieceHashes = fill(20 * 2000, 3);
        long totalLength = 16384L * 2000;
        return encodeInfo("big.bin", 16384, manyPieceHashes, totalLength);
    }

    private static byte[] encodeInfo(String name, long pieceLength, byte[] pieces, long totalLength) {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of(name),
                BString.of("piece length"), new BInteger(pieceLength),
                BString.of("pieces"), BString.of(pieces),
                BString.of("length"), new BInteger(totalLength)));
        return BencodeEncoder.encode(info);
    }

    private static BDictionary handshakeDict(boolean advertiseExtension, Integer metadataSize) {
        Map<BString, BValue> entries = new HashMap<>();
        entries.put(BString.of("m"), advertiseExtension
                ? new BDictionary(Map.of(BString.of("ut_metadata"), new BInteger(PEER_UT_METADATA_ID)))
                : new BDictionary(Map.of()));
        if (metadataSize != null) {
            entries.put(BString.of("metadata_size"), new BInteger(metadataSize));
        }
        return new BDictionary(entries);
    }

    /** Drives the peer side of the handshake and, if servedBytes is non-null, answers
     * every metadata request with the corresponding slice of it. Passing rejectPiece
     * instead answers that one piece index with a Reject and never reaches servedBytes. */
    private Thread startFakePeer(InfoHash infoHash, boolean advertiseExtension, Integer metadataSize,
                                  byte[] servedBytes, Integer rejectPiece) {
        return new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(),
                        Handshake.withExtensionProtocol(infoHash, theirPeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Extended(0,
                        BencodeEncoder.encode(handshakeDict(advertiseExtension, metadataSize))));

                if (servedBytes == null && rejectPiece == null) {
                    Thread.sleep(300);
                    return;
                }

                while (!socket.isClosed()) {
                    Extended request;
                    try {
                        request = (Extended) PeerWireCodec.readMessage(socket.getInputStream());
                    } catch (IOException closed) {
                        return;
                    }
                    if (request.extendedMessageId() == 0) {
                        // The client's own extended handshake - sent right after connect(),
                        // arrives before any metadata request. Not a ut_metadata message.
                        continue;
                    }
                    int piece = UtMetadataCodec.decode(request.payload()).piece();
                    if (rejectPiece != null && piece == rejectPiece) {
                        PeerWireCodec.writeMessage(socket.getOutputStream(),
                                new Extended(OUR_UT_METADATA_ID, UtMetadataCodec.encode(new MetadataReject(piece))));
                        continue;
                    }
                    int offset = piece * BLOCK_SIZE;
                    int length = Math.min(BLOCK_SIZE, servedBytes.length - offset);
                    byte[] block = Arrays.copyOfRange(servedBytes, offset, offset + length);
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Extended(OUR_UT_METADATA_ID,
                            UtMetadataCodec.encode(new MetadataData(piece, servedBytes.length, block))));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void fetchesAndVerifiesASinglePieceInfoDict() throws Exception {
        byte[] infoDictBytes = smallInfoDictBytes();
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = startFakePeer(infoHash, true, infoDictBytes.length, infoDictBytes, null);
        fakePeer.start();

        byte[] fetched = MetadataFetcher.fetch(address, infoHash, ourPeerId(), EncryptionMode.DISABLED);

        assertArrayEquals(infoDictBytes, fetched);
        fakePeer.join(2000);
    }

    @Test
    void fetchesAndVerifiesAMultiPieceInfoDict() throws Exception {
        byte[] infoDictBytes = largeInfoDictBytes();
        assertThatItsActuallyMultiplePieces(infoDictBytes);
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = startFakePeer(infoHash, true, infoDictBytes.length, infoDictBytes, null);
        fakePeer.start();

        byte[] fetched = MetadataFetcher.fetch(address, infoHash, ourPeerId(), EncryptionMode.DISABLED);

        assertArrayEquals(infoDictBytes, fetched);
        fakePeer.join(2000);
    }

    private static void assertThatItsActuallyMultiplePieces(byte[] infoDictBytes) {
        if (infoDictBytes.length <= BLOCK_SIZE) {
            throw new AssertionError("test fixture isn't actually multi-piece - fix largeInfoDictBytes()");
        }
    }

    @Test
    void failsWhenPeerDoesNotSupportUtMetadata() throws Exception {
        byte[] infoDictBytes = smallInfoDictBytes();
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = startFakePeer(infoHash, false, infoDictBytes.length, null, null);
        fakePeer.start();

        assertThrows(MetadataFetchException.class, () -> MetadataFetcher.fetch(address, infoHash, ourPeerId(), EncryptionMode.DISABLED));
        fakePeer.join(2000);
    }

    @Test
    void failsWhenPeerDoesNotReportMetadataSize() throws Exception {
        byte[] infoDictBytes = smallInfoDictBytes();
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = startFakePeer(infoHash, true, null, null, null);
        fakePeer.start();

        assertThrows(MetadataFetchException.class, () -> MetadataFetcher.fetch(address, infoHash, ourPeerId(), EncryptionMode.DISABLED));
        fakePeer.join(2000);
    }

    @Test
    void failsWhenPeerRejectsAPiece() throws Exception {
        byte[] infoDictBytes = smallInfoDictBytes();
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = startFakePeer(infoHash, true, infoDictBytes.length, infoDictBytes, 0);
        fakePeer.start();

        assertThrows(MetadataFetchException.class, () -> MetadataFetcher.fetch(address, infoHash, ourPeerId(), EncryptionMode.DISABLED));
        fakePeer.join(2000);
    }

    @Test
    void failsWhenAssembledBytesDoNotMatchTheRequestedInfoHash() throws Exception {
        byte[] requestedInfoDict = smallInfoDictBytes();
        InfoHash requestedInfoHash = InfoHash.of(sha1(requestedInfoDict));
        byte[] actuallyServedBytes = encodeInfo("different.bin", 20, fill(20, 99), 20);
        PeerAddress address = startFakePeerServer();
        Thread fakePeer = startFakePeer(requestedInfoHash, true, actuallyServedBytes.length, actuallyServedBytes, null);
        fakePeer.start();

        assertThrows(MetadataFetchException.class,
                () -> MetadataFetcher.fetch(address, requestedInfoHash, ourPeerId(), EncryptionMode.DISABLED));
        fakePeer.join(2000);
    }
}
