package com.grimtorrenter.engine.engine;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.magnet.MagnetLink;
import com.grimtorrenter.engine.metadata.MetadataData;
import com.grimtorrenter.engine.metadata.UtMetadataCodec;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import com.grimtorrenter.engine.tracker.PeerId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end: magnet -> tracker announce -> real peer connection -> BEP9 metadata
 * fetch -> addTorrent. Each lower layer (MagnetLink, PeerConnection's extension
 * protocol, MetadataFetcher) already has its own focused tests - this only proves
 * TorrentEngine wires them together correctly. See design_docs/0028.
 */
class TorrentEngineMagnetTest {

    private HttpServer trackerServer;
    private ServerSocket peerServerSocket;

    @AfterEach
    void tearDown() throws IOException {
        if (trackerServer != null) {
            trackerServer.stop(0);
        }
        if (peerServerSocket != null && !peerServerSocket.isClosed()) {
            peerServerSocket.close();
        }
    }

    private static byte[] fill(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (seed + i);
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

    private static final class NoOpListener implements TorrentSessionListener {
        @Override
        public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
        }

        @Override
        public void onPieceCompleted(TorrentSession session, int pieceIndex) {
        }
    }

    /** Always replies with exactly one peer (compact format), pointing at peerPort on
     * localhost - regardless of the request, which is fine since this test only needs
     * one announce to matter. */
    private String startFakeTrackerServer(int peerPort) throws IOException {
        trackerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] compactPeer = {127, 0, 0, 1, (byte) (peerPort >> 8), (byte) peerPort};
        BDictionary response = new BDictionary(Map.of(
                BString.of("interval"), new BInteger(3600),
                BString.of("peers"), BString.of(compactPeer)));
        byte[] body = BencodeEncoder.encode(response);
        trackerServer.createContext("/announce", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        trackerServer.start();
        return "http://127.0.0.1:" + trackerServer.getAddress().getPort() + "/announce";
    }

    private static byte[] realInfoDictBytes() {
        byte[] content = fill(20, 1);
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of("magnet-test.bin"),
                BString.of("piece length"), new BInteger(content.length),
                BString.of("pieces"), BString.of(sha1(content)),
                BString.of("length"), new BInteger(content.length)));
        return BencodeEncoder.encode(info);
    }

    /** Serves a single-piece BEP 9 exchange (infoDictBytes here is always small enough to
     * be one piece) - mirrors MetadataFetcherTest's fake peer, kept local since each test
     * class in this project owns its own self-contained fixtures rather than a shared one.
     * Returns the port it bound to. */
    private int startFakePeerServer(InfoHash infoHash, byte[] infoDictBytes) throws IOException {
        peerServerSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = peerServerSocket.getLocalPort();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = peerServerSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(),
                        Handshake.withExtensionProtocol(infoHash, PeerId.of(fill(20, 100))));

                BDictionary theirHandshake = new BDictionary(Map.of(
                        BString.of("m"), new BDictionary(Map.of(BString.of("ut_metadata"), new BInteger(7))),
                        BString.of("metadata_size"), new BInteger(infoDictBytes.length)));
                PeerWireCodec.writeMessage(socket.getOutputStream(),
                        new Extended(0, BencodeEncoder.encode(theirHandshake)));

                while (!socket.isClosed()) {
                    Extended request;
                    try {
                        request = (Extended) PeerWireCodec.readMessage(socket.getInputStream());
                    } catch (IOException closed) {
                        return;
                    }
                    if (request.extendedMessageId() == 0) {
                        continue; // our own extended handshake, not a metadata request
                    }
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Extended(1,
                            UtMetadataCodec.encode(new MetadataData(0, infoDictBytes.length, infoDictBytes))));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();
        return port;
    }

    @Test
    void addMagnetFetchesMetadataAndAddsTheTorrent(@TempDir Path tempDir) throws Exception {
        byte[] infoDictBytes = realInfoDictBytes();
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));

        int peerPort = startFakePeerServer(infoHash, infoDictBytes);
        String announceUrl = startFakeTrackerServer(peerPort);

        MagnetLink magnet = new MagnetLink(infoHash, "magnet-test.bin", List.of(announceUrl));
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        engine.addMagnet(magnet);

        TorrentSession session = awaitTorrent(engine, infoHash);
        assertEquals("magnet-test.bin", session.metadata().name());
        session.stop();
    }

    @Test
    void addMagnetThrowsSynchronouslyWhenNoUsableTrackerAndDhtDisabled(@TempDir Path tempDir) {
        MagnetLink magnet = new MagnetLink(InfoHash.of(fill(20, 1)), "no-trackers", List.of());
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        assertThrows(TorrentEngineException.class, () -> engine.addMagnet(magnet));
    }

    /**
     * DHT peer discovery itself is already thoroughly covered at a lower level
     * (DhtNodeTest, PeerLookupTest) against real local DhtNodes - this only proves
     * TorrentEngine's synchronous dispatch defers to DHT instead of rejecting outright
     * once it's enabled, not a full successful fetch (which would need a real reachable
     * swarm). Port 0 (ephemeral) avoids any conflict with other tests' engines; DHT's
     * background bootstrap still reaches out to the real internet, tolerated here the
     * same way design_docs/0028 already accepts for Bootstrap/DhtNode in general.
     */
    @Test
    void addMagnetDoesNotThrowSynchronouslyWhenNoUsableTrackerButDhtEnabled(@TempDir Path tempDir) {
        MagnetLink magnet = new MagnetLink(InfoHash.of(fill(20, 2)), "no-trackers-but-dht", List.of());
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true);
        try {
            engine.addMagnet(magnet);
        } finally {
            engine.shutdown();
        }
    }

    private static TorrentSession awaitTorrent(TorrentEngine engine, InfoHash infoHash) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Optional<TorrentSession> session = engine.getTorrent(infoHash);
            if (session.isPresent()) {
                return session.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Torrent " + infoHash + " never appeared after addMagnet()");
    }
}
