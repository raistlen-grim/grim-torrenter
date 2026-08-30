package com.grimtorrenter.engine.engine;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.InMemoryEventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.magnet.MagnetLink;
import com.grimtorrenter.engine.metadata.MetadataData;
import com.grimtorrenter.engine.metadata.UtMetadataCodec;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.storage.FileHandlePool;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * localhost - regardless of the request, which is fine when a test only needs one
     * announce to matter. */
    private String startFakeTrackerServer(int peerPort) throws IOException {
        return startFakeTrackerServer(List.of(peerPort));
    }

    /** Same as the single-peer overload above, generalized to serve any number of peers
     * (including zero) in one compact-format response, always the same regardless of how
     * many times /announce is hit. */
    private String startFakeTrackerServer(List<Integer> peerPorts) throws IOException {
        trackerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = BencodeEncoder.encode(trackerResponse(peerPorts));
        trackerServer.createContext("/announce", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        trackerServer.start();
        return "http://127.0.0.1:" + trackerServer.getAddress().getPort() + "/announce";
    }

    /** Replies with firstRoundPeerPorts on the very first /announce hit, and
     * subsequentRoundPeerPorts on every hit after that - lets a test prove the retry loop
     * (design_docs/0028's addendum) actually re-announces for a fresh batch, rather than only
     * ever trying whatever the first response offered. */
    private String startFakeTrackerServerWithChangingResponse(
            List<Integer> firstRoundPeerPorts, List<Integer> subsequentRoundPeerPorts) throws IOException {
        trackerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] firstBody = BencodeEncoder.encode(trackerResponse(firstRoundPeerPorts));
        byte[] subsequentBody = BencodeEncoder.encode(trackerResponse(subsequentRoundPeerPorts));
        AtomicInteger hitCount = new AtomicInteger(0);
        trackerServer.createContext("/announce", exchange -> {
            byte[] body = hitCount.getAndIncrement() == 0 ? firstBody : subsequentBody;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        trackerServer.start();
        return "http://127.0.0.1:" + trackerServer.getAddress().getPort() + "/announce";
    }

    private static BDictionary trackerResponse(List<Integer> peerPorts) {
        byte[] compactPeers = new byte[peerPorts.size() * 6];
        for (int i = 0; i < peerPorts.size(); i++) {
            int port = peerPorts.get(i);
            int offset = i * 6;
            compactPeers[offset] = 127;
            compactPeers[offset + 1] = 0;
            compactPeers[offset + 2] = 0;
            compactPeers[offset + 3] = 1;
            compactPeers[offset + 4] = (byte) (port >> 8);
            compactPeers[offset + 5] = (byte) port;
        }
        return new BDictionary(Map.of(
                BString.of("interval"), new BInteger(3600),
                BString.of("peers"), BString.of(compactPeers)));
    }

    /** A closed port (bound then immediately released) - connecting to it fails fast with
     * "connection refused" rather than a slow timeout, same trick
     * addMagnetRecordsMagnetAddFailedWhenNoPeerHasTheMetadata already used. */
    private static int unusedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    /** A short magnetFetchTimeBudgetSeconds (a couple of seconds, not production's default 90)
     * so these tests stay fast regardless of how many retry rounds they end up needing -
     * candidates-per-round/concurrency-limit are left at their own defaults (0, normalized by
     * Settings' own compact constructor) since no test here has enough real candidates for
     * either to matter. See design_docs/0028's addendum. */
    private static Settings settingsForMagnetTest(EncryptionMode encryptionMode) {
        return new Settings(false, false, 0, 0, false, "23:00", "07:00", 0, 0, encryptionMode, 0,
                false, 2.0, false, 1440, 30, false, 7, null, 3, 0, 0);
    }

    /** name becomes both the fetched torrent's real name (the info-dict's own "name" field -
     * MagnetLink's displayName is just a dn= hint, never what a caller should assert against)
     * and, since it's part of what gets hashed, a distinct InfoHash per caller - each test
     * using its own name means each gets its own infoHash too, without needing a separate
     * content seed per test as well. */
    private static byte[] realInfoDictBytes(String name) {
        byte[] content = fill(20, 1);
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of(name),
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

    /** encryptionMode is forced DISABLED (not the real PREFERRED default) - startFakePeerServer
     * below only ever speaks a plain handshake, and MSE negotiation/fallback is PeerConnection's
     * own well-tested concern (design_docs/0052), not something this test needs to exercise
     * too. Since design_docs/0052's own addendum (2026-08-30), MetadataFetcher genuinely
     * respects whatever mode is configured - PREFERRED here would otherwise make
     * PeerConnection.connect() try an MSE handshake first, which this fake peer can't answer
     * (it only knows how to parse a plain handshake), consume the fixture's one-shot
     * ServerSocket.accept(), and leave no listener for the plaintext fallback's fresh
     * connection attempt. */
    @Test
    void addMagnetFetchesMetadataAndAddsTheTorrent(@TempDir Path tempDir) throws Exception {
        byte[] infoDictBytes = realInfoDictBytes("magnet-test.bin");
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));

        int peerPort = startFakePeerServer(infoHash, infoDictBytes);
        String announceUrl = startFakeTrackerServer(peerPort);

        MagnetLink magnet = new MagnetLink(infoHash, "magnet-test.bin", List.of(announceUrl));
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(settingsForMagnetTest(EncryptionMode.DISABLED)),
                FileHandlePool.unbounded(), Integer.MAX_VALUE, new InMemoryEventStore());

        engine.addMagnet(magnet);

        TorrentSession session = awaitTorrent(engine, infoHash);
        assertEquals("magnet-test.bin", session.metadata().name());
        session.stop();
    }

    /** Concurrent racing (design_docs/0028's addendum), not the original sequential design -
     * one round offers two candidates, an early unreachable one and a later real one, and the
     * add still succeeds. Proves raceOneRound() actually finds a working candidate regardless
     * of its position in the list, not just index 0. */
    @Test
    void addMagnetRacesMultipleCandidatesAndUsesWhicheverSucceeds(@TempDir Path tempDir) throws Exception {
        byte[] infoDictBytes = realInfoDictBytes("race-test.bin");
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));
        int workingPeerPort = startFakePeerServer(infoHash, infoDictBytes);
        int deadPeerPort = unusedPort();
        String announceUrl = startFakeTrackerServer(List.of(deadPeerPort, workingPeerPort));

        MagnetLink magnet = new MagnetLink(infoHash, "race-test.bin", List.of(announceUrl));
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(settingsForMagnetTest(EncryptionMode.DISABLED)),
                FileHandlePool.unbounded(), Integer.MAX_VALUE, new InMemoryEventStore());

        engine.addMagnet(magnet);

        TorrentSession session = awaitTorrent(engine, infoHash);
        assertEquals("race-test.bin", session.metadata().name());
        session.stop();
    }

    /** The retry loop itself (design_docs/0028's addendum), not just concurrent racing within
     * one round - the fake tracker's first /announce response only offers an unreachable peer,
     * and its second (and every later) response offers a real one. Proves a magnet-add can
     * still succeed after an initial round finds nothing, well within this test's short
     * (few-second) configured budget rather than production's 90s. */
    @Test
    void addMagnetSucceedsOnASubsequentRoundAfterAnEmptyFirstOne(@TempDir Path tempDir) throws Exception {
        byte[] infoDictBytes = realInfoDictBytes("retry-test.bin");
        InfoHash infoHash = InfoHash.of(sha1(infoDictBytes));
        int workingPeerPort = startFakePeerServer(infoHash, infoDictBytes);
        int deadPeerPort = unusedPort();
        String announceUrl = startFakeTrackerServerWithChangingResponse(
                List.of(deadPeerPort), List.of(workingPeerPort));

        MagnetLink magnet = new MagnetLink(infoHash, "retry-test.bin", List.of(announceUrl));
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(settingsForMagnetTest(EncryptionMode.DISABLED)),
                FileHandlePool.unbounded(), Integer.MAX_VALUE, new InMemoryEventStore());

        engine.addMagnet(magnet);

        TorrentSession session = awaitTorrent(engine, infoHash);
        assertEquals("retry-test.bin", session.metadata().name());
        session.stop();
    }

    /** Also asserts the MAGNET_ADD_FAILED library event this same synchronous path now
     * records (design_docs/0060) alongside the throw - the one recording point that's
     * trivially deterministic to test, unlike the three async ones below, which each need
     * real (if fake/local) network I/O to fail in a controlled way. */
    @Test
    void addMagnetThrowsSynchronouslyWhenNoUsableTrackerAndDhtDisabled(@TempDir Path tempDir) {
        MagnetLink magnet = new MagnetLink(InfoHash.of(fill(20, 1)), "no-trackers", List.of());
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);

        assertThrows(TorrentEngineException.class, () -> engine.addMagnet(magnet));

        List<LibraryEvent> events = eventStore.forTorrent(magnet.infoHash().hex());
        assertEquals(1, events.stream().filter(e -> e.type() == EventType.MAGNET_ADD_FAILED).count());
    }

    /** Deterministic (no real internet needed) coverage for raceOneRound()'s per-round
     * failure and the retry loop's final give-up: the fake tracker hands back one peer
     * address pointing at a port nothing is listening on (bound then immediately closed, so
     * the connect fails fast with "connection refused" rather than a slow timeout) on every
     * announce, so every round's one candidate fails and the loop exhausts its short test
     * budget with nothing to show for it. See design_docs/0060. */
    @Test
    void addMagnetRecordsMagnetAddFailedWhenNoPeerHasTheMetadata(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer(unusedPort());
        InfoHash infoHash = InfoHash.of(fill(20, 3));
        MagnetLink magnet = new MagnetLink(infoHash, "unreachable-peer", List.of(announceUrl));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(settingsForMagnetTest(EncryptionMode.DISABLED)),
                FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);

        engine.addMagnet(magnet);

        List<LibraryEvent> events = awaitMagnetAddFailedEvent(eventStore, infoHash);
        assertEquals(1, events.size());
        assertTrue(events.get(0).message().contains("metadata"));
    }

    /** The retry loop's empty-candidates guard (raceOneRound() returning immediately rather
     * than calling invokeAny() on an empty task list, which would throw
     * IllegalArgumentException) - the fake tracker always reports zero peers, every round,
     * until the short test budget is exhausted. See design_docs/0028's addendum. */
    @Test
    void addMagnetRecordsMagnetAddFailedWhenTrackerReturnsNoPeersAtAll(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer(List.<Integer>of());
        InfoHash infoHash = InfoHash.of(fill(20, 4));
        MagnetLink magnet = new MagnetLink(infoHash, "no-peers-at-all", List.of(announceUrl));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(settingsForMagnetTest(EncryptionMode.PREFERRED)),
                FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);

        engine.addMagnet(magnet);

        List<LibraryEvent> events = awaitMagnetAddFailedEvent(eventStore, infoHash);
        assertEquals(1, events.size());
        assertTrue(events.get(0).message().contains("tried 0"));
    }

    private static List<LibraryEvent> awaitMagnetAddFailedEvent(InMemoryEventStore eventStore, InfoHash infoHash)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<LibraryEvent> events = eventStore.forTorrent(infoHash.hex()).stream()
                    .filter(e -> e.type() == EventType.MAGNET_ADD_FAILED)
                    .toList();
            if (!events.isEmpty()) {
                return events;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No MAGNET_ADD_FAILED event recorded for " + infoHash + " within the deadline");
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
