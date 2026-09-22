package com.grimtorrenter.engine.engine;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.dht.DhtNode;
import com.grimtorrenter.engine.dht.NodeId;
import com.grimtorrenter.engine.dht.NodeInfo;
import com.grimtorrenter.engine.dht.RoutingTable;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.InMemoryEventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.label.Label;
import com.grimtorrenter.engine.lsd.LsdService;
import com.grimtorrenter.engine.magnet.MagnetLink;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.MetainfoParser;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.piece.FilePriorities;
import com.grimtorrenter.engine.proxy.FakeSocks5Proxy;
import com.grimtorrenter.engine.piece.FilePriority;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.storage.FileHandlePool;
import com.grimtorrenter.engine.torrent.SeedingLimitOverride;
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
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentEngineTest {

    private HttpServer trackerServer;

    @AfterEach
    void tearDown() {
        if (trackerServer != null) {
            trackerServer.stop(0);
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

    private String startFakeTrackerServer() throws IOException {
        trackerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        BDictionary response = new BDictionary(Map.of(
                BString.of("interval"), new BInteger(3600),
                BString.of("peers"), BString.of(new byte[0])));
        byte[] body = BencodeEncoder.encode(response);
        trackerServer.createContext("/announce", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        trackerServer.start();
        return "http://127.0.0.1:" + trackerServer.getAddress().getPort() + "/announce";
    }

    /** Just the bencoded 'info' dictionary, e.g. what a verified magnet metadata fetch hands
     * addFetchedTorrent() - not a full top-level torrent file (that's synthesizeTorrentFileBytes'
     * own job, exercised indirectly through addFetchedTorrent() itself). */
    private static byte[] infoDictBytes(String name, byte[] content) {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of(name),
                BString.of("piece length"), new BInteger(content.length),
                BString.of("pieces"), BString.of(sha1(content)),
                BString.of("length"), new BInteger(content.length)));
        return BencodeEncoder.encode(info);
    }

    private static byte[] torrentBytes(String name, byte[] content, String announceUrl) {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of(name),
                BString.of("piece length"), new BInteger(content.length),
                BString.of("pieces"), BString.of(sha1(content)),
                BString.of("length"), new BInteger(content.length)));
        BDictionary top = new BDictionary(Map.of(
                BString.of("announce"), BString.of(announceUrl),
                BString.of("info"), info));
        return BencodeEncoder.encode(top);
    }

    private static final class NoOpListener implements TorrentSessionListener {
        @Override
        public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
        }

        @Override
        public void onPieceCompleted(TorrentSession session, int pieceIndex) {
        }
    }

    @Test
    void selectTrackerTiersPrefersAnnounceListOverAnnounceWhenBothPresent() {
        TorrentMetadata metadata = new SingleFileTorrent("x", 1, 1, new PieceHashes(fill(20, 0)),
                InfoHash.of(fill(20, 1)), "http://legacy/announce",
                List.of(List.of("http://tier1a/announce", "http://tier1b/announce"), List.of("http://tier2/announce")));

        assertEquals(
                List.of(List.of("http://tier1a/announce", "http://tier1b/announce"), List.of("http://tier2/announce")),
                TorrentEngine.selectTrackerTiers(metadata));
    }

    @Test
    void selectTrackerTiersFallsBackToAnnounceWhenAnnounceListEmpty() {
        TorrentMetadata metadata = new SingleFileTorrent("x", 1, 1, new PieceHashes(fill(20, 0)),
                InfoHash.of(fill(20, 1)), "http://only/announce", List.of());

        assertEquals(List.of(List.of("http://only/announce")), TorrentEngine.selectTrackerTiers(metadata));
    }

    @Test
    void selectTrackerTiersKeepsUdpEntriesAndDropsOnlyUnsupportedSchemesAndEmptyTiers() {
        TorrentMetadata metadata = new SingleFileTorrent("x", 1, 1, new PieceHashes(fill(20, 0)),
                InfoHash.of(fill(20, 1)), null,
                List.of(List.of("udp://udp-only/announce"),
                        List.of("ftp://unsupported/announce", "http://mixed-http/announce")));

        assertEquals(
                List.of(List.of("udp://udp-only/announce"), List.of("http://mixed-http/announce")),
                TorrentEngine.selectTrackerTiers(metadata));
    }

    @Test
    void selectTrackerTiersThrowsWhenNoUsableTrackerAnywhere() {
        TorrentMetadata metadata = new SingleFileTorrent("x", 1, 1, new PieceHashes(fill(20, 0)),
                InfoHash.of(fill(20, 1)), null, List.of(List.of("ftp://tracker1/announce")));

        assertThrows(TorrentEngineException.class, () -> TorrentEngine.selectTrackerTiers(metadata));
    }

    /** Genuinely no trackers declared at all (not "declared but unsupported") is allowed
     * since design_docs/0028's DHT slice - createTrackerClient turns this into a
     * NoOpTrackerClient rather than TorrentEngine failing to add the torrent at all. */
    @Test
    void selectTrackerTiersReturnsEmptyRatherThanThrowingWhenNoneDeclaredAtAll() {
        TorrentMetadata metadata = new SingleFileTorrent("x", 1, 1, new PieceHashes(fill(20, 0)),
                InfoHash.of(fill(20, 1)), null, List.of());

        assertEquals(List.of(), TorrentEngine.selectTrackerTiers(metadata));
    }

    @Test
    void dhtStatusReportsDisabledWithZeroNodesWhenDhtNotEnabled(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        TorrentEngine.DhtStatus status = engine.dhtStatus();

        assertFalse(status.enabled());
        assertEquals(0, status.nodeCount());
    }

    @Test
    void dhtStatusReportsEnabledWhenDhtIsEnabled(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true);
        try {
            TorrentEngine.DhtStatus status = engine.dhtStatus();

            assertTrue(status.enabled());
            assertTrue(status.nodeCount() >= 0);
        } finally {
            engine.shutdown();
        }
    }

    /** Smoke-tests the maintenanceScheduler wiring itself (design_docs/0028's own 2026-08-30
     * addendum) - DhtNode.refreshRoutingTable()'s own actual bucket-refresh/replacement
     * behavior is already thoroughly covered at that lower level (DhtNodeTest), same
     * "don't re-test what a lower layer already proves" spirit as
     * addMagnetDoesNotThrowSynchronouslyWhenNoUsableTrackerButDhtEnabled. Package-private,
     * not private, purely so this can be called directly rather than waiting on the real
     * dhtRefreshIntervalSeconds-second scheduler tick - same spirit as
     * checkSeedingLimitsStopsASeedingTorrentThatHasReachedItsRatioLimit's own direct call. */
    @Test
    void refreshDhtRoutingTableRunsWithoutThrowingWhenDhtIsEnabled(@TempDir Path tempDir) throws InterruptedException {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true);
        try {
            engine.refreshDhtRoutingTable();
            // The refresh itself runs on its own virtual thread (never inline on the
            // maintenance scheduler) - give it a moment to actually run before shutdown.
            Thread.sleep(200);
        } finally {
            engine.shutdown();
        }
    }

    /** design_docs/0028's own 2026-08-30 addendum - a real save-then-reload round trip. A
     * contact gets into the first engine's routing table the same way every other dht-package
     * test seeds one (directly, bypassing the network - dhtNode() is package-private purely
     * for this kind of test access), saveDhtRoutingTable() persists it, then a *second* engine
     * pointed at the same baseDownloadDirectory loads and re-pings it on its own "start" -
     * proving the warm-start path actually reaches the routing table, not just that the file
     * gets written. */
    @Test
    void dhtRoutingTableSurvivesASaveThenAFreshEngineLoadingIt(@TempDir Path tempDir) throws Exception {
        DhtNode persistedContact = new DhtNode(NodeId.random(), 0);
        TorrentEngine engineOne = new TorrentEngine(tempDir, 0, new NoOpListener(), true);
        try {
            engineOne.dhtNode().routingTable().insert(
                    new NodeInfo(persistedContact.ourId(), InetAddress.getLoopbackAddress(), persistedContact.port()));

            engineOne.saveDhtRoutingTable();
        } finally {
            engineOne.shutdown();
        }

        TorrentEngine engineTwo = new TorrentEngine(tempDir, 0, new NoOpListener(), true);
        try {
            long deadline = System.currentTimeMillis() + 7000;
            while (engineTwo.dhtStatus().nodeCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertTrue(engineTwo.dhtStatus().nodeCount() >= 1);
        } finally {
            engineTwo.shutdown();
            persistedContact.close();
        }
    }

    /** design_docs/0028's own 2026-08-30 addendum (the config-directory follow-up) - the
     * two DHT marker files are engine-wide bookkeeping, not torrent data, so they belong in
     * configDirectory, not baseDownloadDirectory (the directory a user actually browses).
     * Uses the widest constructor directly with two genuinely different directories to prove
     * the split, rather than relying on the lower-arity constructors (which default
     * configDirectory to baseDownloadDirectory, matching every pre-existing caller's
     * unaffected behavior - see dhtRoutingTableSurvivesASaveThenAFreshEngineLoadingIt above,
     * which deliberately keeps that default). ".grimtorrenter-dht-nodes" matches
     * TorrentEngine's own private DHT_KNOWN_NODES_MARKER_FILENAME constant. */
    @Test
    void dhtRoutingTableIsPersistedUnderConfigDirectoryNotDownloadDirectory(@TempDir Path tempDir) {
        Path downloadDir = tempDir.resolve("downloads");
        Path configDir = tempDir.resolve("config");
        TorrentEngine engine = new TorrentEngine(downloadDir, 0, new NoOpListener(), true, false,
                new InMemorySettingsStore(Settings.defaults()), FileHandlePool.unbounded(), Integer.MAX_VALUE,
                new InMemoryEventStore(), tempDir.resolve("watch"), configDir);
        try {
            engine.saveDhtRoutingTable();

            assertTrue(Files.exists(configDir.resolve(".grimtorrenter-dht-nodes")));
            assertFalse(Files.exists(downloadDir.resolve(".grimtorrenter-dht-nodes")));
        } finally {
            engine.shutdown();
        }
    }

    /** Known gap: this only covers RUNNING/DISABLED - there's no cheap, deterministic way to
     * force a real DHT/peer-server bind failure in a unit test today, so serviceStatuses()'s
     * FAILED branch (and the DHT_UNAVAILABLE/PEER_SERVER_UNAVAILABLE event recording that goes
     * with it) has no automated coverage yet. See design_docs/0059. */
    @Test
    void serviceStatusesReportDisabledWhenNotEnabled(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        List<TorrentEngine.ServiceStatus> statuses = engine.serviceStatuses();

        assertEquals(
                List.of(
                        new TorrentEngine.ServiceStatus("dht", TorrentEngine.ServiceState.DISABLED),
                        new TorrentEngine.ServiceStatus("peerServer", TorrentEngine.ServiceState.DISABLED),
                        new TorrentEngine.ServiceStatus("lsd", TorrentEngine.ServiceState.DISABLED)),
                statuses);
    }

    /** A freshly-constructed engine's DHT node has an empty routing table (real bootstrap runs
     * asynchronously - see createDhtNode()), so it's genuinely DEGRADED, not RUNNING, at this
     * point - see design_docs/0059's DEGRADED-state addendum (a flat, live threshold check,
     * deliberately no startup grace period). serviceStatusesReportRunningOnceRoutingTableIsHealthy
     * below covers the RUNNING case. */
    @Test
    void serviceStatusesReportDegradedRightAfterEnabling(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true);
        try {
            List<TorrentEngine.ServiceStatus> statuses = engine.serviceStatuses();

            assertEquals(
                    new TorrentEngine.ServiceStatus("dht", TorrentEngine.ServiceState.DEGRADED),
                    statuses.get(0));
            assertEquals(
                    new TorrentEngine.ServiceStatus("peerServer", TorrentEngine.ServiceState.DISABLED),
                    statuses.get(1));
        } finally {
            engine.shutdown();
        }
    }

    /** Seeds the real DhtNode's routing table directly (no real bootstrap/network needed -
     * same "package-private dhtNode() for test access" seam its own Javadoc already
     * anticipates) past DhtNode.isDegraded()'s threshold, confirming serviceStatuses() reports
     * RUNNING once the table is no longer sparse. See design_docs/0059's DEGRADED-state
     * addendum. */
    @Test
    void serviceStatusesReportRunningOnceRoutingTableIsHealthy(@TempDir Path tempDir) throws Exception {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true);
        try {
            for (int i = 0; i < RoutingTable.BUCKET_SIZE; i++) {
                engine.dhtNode().routingTable().insert(
                        new NodeInfo(NodeId.random(), InetAddress.getLoopbackAddress(), 10000 + i));
            }

            List<TorrentEngine.ServiceStatus> statuses = engine.serviceStatuses();

            assertEquals(
                    new TorrentEngine.ServiceStatus("dht", TorrentEngine.ServiceState.RUNNING),
                    statuses.get(0));
        } finally {
            engine.shutdown();
        }
    }

    /** Confirms the real fix for a gap this project already hit once, for DHT
     * (design_docs/0036's own 2026-09-06 revision: "a freshly-started torrent shouldn't wait a
     * full dhtReannounceIntervalSeconds for its first DHT lookup"). announceViaLsd()'s periodic
     * sweep runs on a fixed engine-wide schedule (default 300s) with no relation to any
     * individual torrent's activation - without announceOnLsdActivation()'s immediate
     * per-torrent trigger, a freshly-added torrent would have to wait up to
     * lsdAnnounceIntervalSeconds for its first LSD announce, unlike DHT (zero-initial-delay
     * per-session schedule) or the tracker (start()'s own synchronous first announce). A
     * second, independent real LsdService stands in for a second LAN client and proves the
     * announce actually arrives within this test's own short timeout, not after a multi-minute
     * wait. Real, non-hermetic multicast socket activity - same accepted precedent as
     * LsdServiceTest's own loopback tests and this class's own DHT bootstrap tests. See
     * design_docs/0062's own addendum. */
    @Test
    void addingATorrentAnnouncesItViaLsdImmediatelyRatherThanWaitingForThePeriodicSweep(@TempDir Path tempDir)
            throws Exception {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("lsd-activation-test.bin", fill(20, 7), announceUrl);

        // Loopback, not auto-detected real interfaces, for both ends - a real physical
        // interface is exactly the kind of thing a local firewall/switch can silently swallow
        // multicast traffic on, even between two sockets on the same host. Found the hard way:
        // this test originally used the auto-detecting LsdService constructor on both ends and
        // failed in a real build environment for exactly that reason. See design_docs/0062's
        // own addendum.
        List<NetworkInterface> loopback = List.of(NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress()));
        CountDownLatch found = new CountDownLatch(1);
        AtomicReference<InfoHash> foundInfoHash = new AtomicReference<>();
        LsdService secondClient = new LsdService(6882, (infoHash, address) -> {
            foundInfoHash.set(infoHash);
            found.countDown();
        }, loopback);
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE,
                new InMemoryEventStore(), tempDir.resolve("watch"), tempDir, true, loopback);
        try {
            TorrentSession session = engine.addTorrent(torrentBytes).session();
            InfoHash infoHash = session.metadata().infoHash();

            assertTrue(found.await(2, TimeUnit.SECONDS),
                    "expected an immediate LSD announce, not a wait for the periodic sweep");
            assertEquals(infoHash, foundInfoHash.get());
        } finally {
            engine.shutdown();
            secondClient.close();
        }
    }

    /** End-to-end: a real external client dials the engine's own bound peer-server port
     * (constructed with port 0 - ephemeral - same convention as
     * dhtStatusReportsEnabledWhenDhtIsEnabled) and gets adopted by the right session,
     * purely by info hash. See design_docs/0038. */
    @Test
    void acceptsAnInboundConnectionForAKnownTorrent(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("inbound-test.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), false, true);
        try {
            TorrentSession session = engine.addTorrent(torrentBytes).session();
            InfoHash infoHash = session.metadata().infoHash();
            int peerServerPort = engine.peerServerPort().orElseThrow();

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), peerServerPort)) {
                PeerWireCodec.writeHandshake(client.getOutputStream(), Handshake.of(infoHash, PeerId.of(fill(20, 77))));
                Handshake ourHandshake = PeerWireCodec.readHandshake(client.getInputStream());
                assertEquals(infoHash, ourHandshake.infoHash());

                long deadline = System.currentTimeMillis() + 5000;
                while (session.peers().isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, session.peers().size());
            }
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void addTorrentParsesStartsAndRegistersSession(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession session = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = MetainfoParser.parse(torrentBytes).infoHash();

        assertEquals(TorrentState.DOWNLOADING, session.state());
        assertSame(session, engine.getTorrent(infoHash).orElseThrow());
        assertEquals(1, engine.listTorrents().size());

        session.stop();
    }

    @Test
    void addTorrentTwiceReturnsSameSession(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession first = engine.addTorrent(torrentBytes).session();
        TorrentSession second = engine.addTorrent(torrentBytes).session();

        assertSame(first, second);
        assertEquals(1, engine.listTorrents().size());

        first.stop();
    }

    @Test
    void addTorrentReportsAlreadyExistedOnlyOnASecondAdd(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentEngine.AddTorrentResult first = engine.addTorrent(torrentBytes);
        TorrentEngine.AddTorrentResult second = engine.addTorrent(torrentBytes);

        assertFalse(first.alreadyExisted());
        assertTrue(second.alreadyExisted());
        assertSame(first.session(), second.session());

        first.session().stop();
    }

    @Test
    void differentTorrentsWithSameDeclaredNameGetDisambiguatedDirectories(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentA = torrentBytes("same-name.bin", fill(20, 1), announceUrl);
        byte[] torrentB = torrentBytes("same-name.bin", fill(20, 99), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession sessionA = engine.addTorrent(torrentA).session();
        TorrentSession sessionB = engine.addTorrent(torrentB).session();

        assertEquals(2, engine.listTorrents().size());
        assertNotEquals(sessionA.metadata().infoHash(), sessionB.metadata().infoHash());

        // First torrent lands flat (no name collision yet, design_docs/0065); the second's
        // bare name collides with it, so it falls back to its own disambiguated subdirectory.
        Path fileA = tempDir.resolve("same-name.bin");
        Path fileB = tempDir.resolve("same-name.bin-2").resolve("same-name.bin");
        assertTrue(Files.exists(fileA));
        assertTrue(Files.exists(fileB));

        sessionA.stop();
        sessionB.stop();
    }

    @Test
    void reAddingSameTorrentFromAFreshEngineReusesItsExistingDirectory(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrent = torrentBytes("reused-name.bin", fill(20, 1), announceUrl);

        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession first = firstEngine.addTorrent(torrent).session();
        assertTrue(Files.exists(tempDir.resolve("reused-name.bin")));
        first.stop();

        // A brand new TorrentEngine (simulating a process restart, no in-memory session state)
        // re-adding the same torrent should land in the SAME directory, not get disambiguated
        // into "-2" just because the directory it wants already exists.
        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession second = secondEngine.addTorrent(torrent).session();

        assertFalse(Files.exists(tempDir.resolve("reused-name.bin-2")));
        second.stop();
    }

    @Test
    void sanitizeDirectoryNameReplacesUnsafeCharacters() {
        assertEquals("weird_name_with_bad_chars", TorrentEngine.sanitizeDirectoryName("weird/name:with*bad?chars"));
    }

    @Test
    void sanitizeDirectoryNameFallsBackForEmptyOrDotOnlyNames() {
        assertEquals("torrent", TorrentEngine.sanitizeDirectoryName(""));
        assertEquals("torrent", TorrentEngine.sanitizeDirectoryName("."));
        assertEquals("torrent", TorrentEngine.sanitizeDirectoryName(".."));
    }

    @Test
    void removeStopsAndUnregistersSession(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession session = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = session.metadata().infoHash();

        engine.removeTorrent(infoHash);

        assertEquals(TorrentState.STOPPED, session.state());
        assertTrue(engine.getTorrent(infoHash).isEmpty());
    }

    @Test
    void pauseAndResumeDelegateToSessionStopAndStart(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession session = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = session.metadata().infoHash();

        engine.pauseTorrent(infoHash);
        assertEquals(TorrentState.STOPPED, session.state());

        engine.resumeTorrent(infoHash);
        assertEquals(TorrentState.DOWNLOADING, session.state());

        session.stop();
    }

    @Test
    void removeTorrentDeletesResumeRecordButKeepsDownloadedFiles(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession session = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = session.metadata().infoHash();

        engine.removeTorrent(infoHash);

        // Content lands flat (design_docs/0065); config-side markers live under
        // configDirectory/torrents/<infoHash> - defaults to tempDir itself here, same as
        // every other pre-existing-constructor test.
        assertTrue(Files.exists(tempDir.resolve("file.bin")));
        Path configTorrentDirectory = tempDir.resolve("torrents").resolve(infoHash.hex());
        assertFalse(Files.exists(configTorrentDirectory.resolve(".grimtorrenter.torrent")));
        assertFalse(Files.exists(configTorrentDirectory.resolve(".grimtorrenter-state")));

        // A fresh engine (simulating a restart) should NOT pick this back up - the
        // resume record is gone even though the downloaded file is still sitting there.
        TorrentEngine freshEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        freshEngine.restore();
        assertTrue(freshEngine.listTorrents().isEmpty());
    }

    /** The bug this guards against: re-adding a torrent whose directory was reused (kept
     * on disk after an earlier "remove without delete data") used to always start a brand
     * new PieceManager with every piece NEEDED, silently re-downloading data that was
     * already correct on disk. There's deliberately no fake peer anywhere in this test -
     * if the bug regressed, the second session would have no way to ever complete and
     * awaitState's deadline would fail it. See design_docs/0037. */
    @Test
    void reAddingAPreviouslyRemovedButDataKeptTorrentVerifiesExistingDataInsteadOfRedownloading(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        String announceUrl = startFakeTrackerServer();
        byte[] content = fill(20, 5);
        byte[] torrentBytes = torrentBytes("reuse-data.bin", content, announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession first = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = first.metadata().infoHash();
        engine.removeTorrent(infoHash, false);

        // Simulate the torrent having actually finished downloading before it was removed -
        // same direct-write technique TorrentSessionTest's own restoreAsync tests use,
        // rather than driving a full peer-wire download just to get correct bytes on disk.
        Files.write(tempDir.resolve("reuse-data.bin"), content);

        TorrentSession second = engine.addTorrent(torrentBytes).session();

        awaitState(second, TorrentState.SEEDING);
        assertEquals(0, second.connectedPeerCount());
        second.stop();
    }

    @Test
    void removeTorrentWithDeleteDataDeletesEverything(@TempDir Path tempDir) throws IOException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession session = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = session.metadata().infoHash();

        engine.removeTorrent(infoHash, true);

        assertFalse(Files.exists(tempDir.resolve("file.bin")));
    }

    @Test
    void restoreRegistersTorrentImmediatelyAndAutoStartsWhenItWasRunning(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession original = firstEngine.addTorrent(torrentBytes).session();
        InfoHash infoHash = original.metadata().infoHash();
        original.stop();

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        // Registered synchronously by restore() itself - doesn't wait on the background re-hash.
        TorrentSession restored = secondEngine.getTorrent(infoHash).orElseThrow();
        awaitState(restored, TorrentState.DOWNLOADING);
        restored.stop();
    }

    @Test
    void restoredPausedTorrentSettlesToStoppedWithoutAnnouncing(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("file.bin", fill(20, 5), announceUrl);

        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession original = firstEngine.addTorrent(torrentBytes).session();
        InfoHash infoHash = original.metadata().infoHash();
        firstEngine.pauseTorrent(infoHash);

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        TorrentSession restored = secondEngine.getTorrent(infoHash).orElseThrow();
        awaitState(restored, TorrentState.STOPPED);
    }

    @Test
    void restoreSkipsDirectoriesWithoutATorrentFileMarker(@TempDir Path tempDir) throws IOException {
        // restore() scans configDirectory's torrents subdirectory, not the download
        // directory, since design_docs/0065 - configDirectory defaults to tempDir here.
        Files.createDirectories(tempDir.resolve("torrents").resolve("unrelated"));

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        engine.restore();

        assertTrue(engine.listTorrents().isEmpty());
    }

    private static Settings settingsWithSeedingLimits(boolean ratioEnabled, double ratio,
                                                        boolean timeEnabled, long timeMinutes) {
        return new Settings(true, true, 0, 0, false, "23:00", "07:00", 0, 0,
                EncryptionMode.PREFERRED, 0, ratioEnabled, ratio, timeEnabled, timeMinutes);
    }

    /** Same "remove with keep-files, rewrite the correct bytes, re-add" recipe
     * reAddingAPreviouslyRemovedButDataKeptTorrentVerifiesExistingDataInsteadOfRedownloading
     * already established, as a shared helper - the only way these tests get a session into
     * SEEDING without a real peer connection uploading real data. See design_docs/0037. */
    private TorrentSession addAlreadySeededTorrent(TorrentEngine engine, Path tempDir, String name, byte[] content,
                                                     String announceUrl) throws IOException, InterruptedException {
        byte[] torrentBytes = torrentBytes(name, content, announceUrl);
        TorrentSession first = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = first.metadata().infoHash();
        engine.removeTorrent(infoHash, false);
        Files.write(tempDir.resolve(name), content);
        TorrentSession second = engine.addTorrent(torrentBytes).session();
        awaitState(second, TorrentState.SEEDING);
        return second;
    }

    /** Ratio 0.0 is a deliberately degenerate but valid limit - a torrent that's never
     * uploaded anything already satisfies uploaded/downloaded (0) &gt;= 0.0, letting this test
     * assert deterministically without needing a real peer connection to generate real upload
     * traffic. checkSeedingLimits() is called directly rather than waiting on the real
     * SEEDING_LIMIT_CHECK_INTERVAL_SECONDS-second scheduler tick. See design_docs/0054. */
    @Test
    void checkSeedingLimitsStopsASeedingTorrentThatHasReachedItsRatioLimit(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemorySettingsStore settingsStore = new InMemorySettingsStore(settingsWithSeedingLimits(true, 0.0, false, 0));
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false, settingsStore);

        TorrentSession session = addAlreadySeededTorrent(engine, tempDir, "ratio-limit.bin", fill(20, 5), announceUrl);

        engine.checkSeedingLimits();

        awaitState(session, TorrentState.STOPPED);
        Path configTorrentDirectory = tempDir.resolve("torrents").resolve(session.metadata().infoHash().hex());
        assertEquals("STOPPED", Files.readString(configTorrentDirectory.resolve(".grimtorrenter-state")).strip());
    }

    /** Time limit 0 minutes is the same kind of deterministic degenerate boundary as ratio
     * 0.0 above - any elapsed time since completion (even a few real milliseconds) already
     * satisfies minutesSeeding &gt;= 0. */
    @Test
    void checkSeedingLimitsStopsASeedingTorrentThatHasReachedItsTimeLimit(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemorySettingsStore settingsStore = new InMemorySettingsStore(settingsWithSeedingLimits(false, 0, true, 0));
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false, settingsStore);

        TorrentSession session = addAlreadySeededTorrent(engine, tempDir, "time-limit.bin", fill(20, 6), announceUrl);

        engine.checkSeedingLimits();

        awaitState(session, TorrentState.STOPPED);
    }

    /** checkSeedingLimits() records the SEEDING_LIMIT_REACHED event itself, with the actual
     * reason (ratio vs. time), before ever calling pauseTorrent() - this is what lets a later
     * library-event reader tell an auto-pause apart from a manual one, which produces the same
     * generic state-changed transition but no event. See design_docs/0055. */
    @Test
    void checkSeedingLimitsRecordsALibraryEventWithTheReachedReason(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemorySettingsStore settingsStore = new InMemorySettingsStore(settingsWithSeedingLimits(true, 0.0, false, 0));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false, settingsStore,
                FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);

        TorrentSession session = addAlreadySeededTorrent(engine, tempDir, "ratio-event.bin", fill(20, 11), announceUrl);

        engine.checkSeedingLimits();
        awaitState(session, TorrentState.STOPPED);

        List<LibraryEvent> events = eventStore.forTorrent(session.metadata().infoHash().hex());
        assertEquals(1, events.stream().filter(e -> e.type() == EventType.SEEDING_LIMIT_REACHED).count());
        assertTrue(events.get(0).message().contains("ratio"));
    }

    /** A manual pause of a seeding torrent - as opposed to checkSeedingLimits()'s own
     * auto-pause above - produces the same STOPPED transition but is not itself an event a user
     * needs reviewing (they just did it), so nothing should be recorded for it. */
    @Test
    void manualPauseDoesNotRecordASeedingLimitEvent(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);

        TorrentSession session = addAlreadySeededTorrent(engine, tempDir, "manual-pause.bin", fill(20, 12), announceUrl);
        engine.pauseTorrent(session.metadata().infoHash());

        assertTrue(eventStore.forTorrent(session.metadata().infoHash().hex()).stream()
                .noneMatch(e -> e.type() == EventType.SEEDING_LIMIT_REACHED));
    }

    /** Exactly one TorrentEngine per running process in production, so recording this at
     * construction is equivalent to "the app started" - lets a timeline of events be
     * correlated against process restarts (e.g. an auto-updater like Watchtower recreating the
     * container). See design_docs/0055. */
    @Test
    void constructingAnEngineRecordsAServerStartedEvent(@TempDir Path tempDir) {
        InMemoryEventStore eventStore = new InMemoryEventStore();

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);

        List<LibraryEvent> started = eventStore.all().stream()
                .filter(e -> e.type() == EventType.SERVER_STARTED).toList();
        assertEquals(1, started.size());
        assertNull(started.get(0).infoHash());
        assertNull(started.get(0).torrentName());
    }

    /** addTorrent() records ADDED exactly once for a genuinely new torrent, and not again for
     * an idempotent re-add of the same info hash. */
    @Test
    void addTorrentRecordsAnAddedEventOnlyOnceForTheSameInfoHash(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);
        byte[] torrentBytes = torrentBytes("added-event.bin", fill(20, 13), announceUrl);

        TorrentSession session = engine.addTorrent(torrentBytes).session();
        engine.addTorrent(torrentBytes);

        List<LibraryEvent> events = eventStore.forTorrent(session.metadata().infoHash().hex());
        assertEquals(1, events.stream().filter(e -> e.type() == EventType.ADDED).count());
    }

    /** A direct upload via the public single-arg addTorrent(byte[]) still records message:
     * null, confirming the new package-private source-aware overload the watch folder uses
     * (design_docs/0056) didn't change this existing, unrelated call path's behavior. */
    @Test
    void addTorrentWithoutASourceRecordsAnAddedEventWithNoMessage(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);
        byte[] torrentBytes = torrentBytes("no-source-event.bin", fill(20, 15), announceUrl);

        TorrentSession session = engine.addTorrent(torrentBytes).session();

        List<LibraryEvent> events = eventStore.forTorrent(session.metadata().infoHash().hex());
        LibraryEvent added = events.stream().filter(e -> e.type() == EventType.ADDED).findFirst().orElseThrow();
        assertNull(added.message());
    }

    @Test
    void removeTorrentRecordsARemovedEvent(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);
        byte[] torrentBytes = torrentBytes("removed-event.bin", fill(20, 14), announceUrl);
        TorrentSession session = engine.addTorrent(torrentBytes).session();
        InfoHash infoHash = session.metadata().infoHash();

        engine.removeTorrent(infoHash);

        List<LibraryEvent> events = eventStore.forTorrent(infoHash.hex());
        assertEquals(1, events.stream().filter(e -> e.type() == EventType.REMOVED).count());
    }

    /** trackerStatusListenerFor() is the adapter between TrackedTrackerClient's engine-only
     * TrackerStatusListener callback and a real library event - the time-window debounce policy
     * is TrackedTrackerClient's own concern (TrackedTrackerClientTest); this proves the adapter
     * records engine-wide events (null infoHash/torrentName, URL in the message) and collapses
     * several torrents sharing one tracker into a single unreachable/recovered pair. See
     * design_docs/0055's own TRACKER_UNREACHABLE addendum and its 2026-09-21 revision. */
    @Test
    void trackerStatusListenerCollapsesTorrentsSharingATrackerIntoOneEventPair(@TempDir Path tempDir) {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);
        String url = "http://tracker.example/announce";
        TorrentMetadata first = new SingleFileTorrent("tracker-status.bin", 1, 1, new PieceHashes(fill(20, 0)),
                InfoHash.of(fill(20, 16)), url, List.of());
        TorrentMetadata second = new SingleFileTorrent("tracker-status-2.bin", 1, 1, new PieceHashes(fill(20, 0)),
                InfoHash.of(fill(20, 19)), url, List.of());

        var firstListener = engine.trackerStatusListenerFor(first);
        var secondListener = engine.trackerStatusListenerFor(second);
        firstListener.onTrackerUnreachable(url, "simulated failure");
        secondListener.onTrackerUnreachable(url, "simulated failure");
        firstListener.onTrackerRecovered(url);
        secondListener.onTrackerRecovered(url);

        List<LibraryEvent> unreachable = eventStore.all().stream()
                .filter(e -> e.type() == EventType.TRACKER_UNREACHABLE).toList();
        assertEquals(1, unreachable.size());
        assertNull(unreachable.get(0).infoHash());
        assertNull(unreachable.get(0).torrentName());
        assertTrue(unreachable.get(0).message().contains(url));
        assertTrue(unreachable.get(0).message().contains("simulated failure"));

        List<LibraryEvent> recovered = eventStore.all().stream()
                .filter(e -> e.type() == EventType.TRACKER_RECOVERED).toList();
        assertEquals(1, recovered.size());
        assertNull(recovered.get(0).infoHash());
        assertTrue(recovered.get(0).message().contains(url));
    }

    /** design_docs/0055's own MAGNET_RESOLVED addendum: a resolved magnet reuses the existing
     * ADDED event (not a new EventType) with the same source-driven message watch folder already
     * established ([[0056-watch-folder]]) - "Added via magnet" rather than a distinct type,
     * since the ADDED event already shows up either way and a second event per resolved magnet
     * would just be redundant. addFetchedTorrent() is exercised directly here (package-private
     * for testing) rather than through a real peer metadata fetch. */
    @Test
    void addFetchedTorrentRecordsAnAddedEventWithAnAddedViaMagnetMessage(@TempDir Path tempDir) {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);
        byte[] infoDict = infoDictBytes("magnet-resolved.bin", fill(20, 17));
        MagnetLink magnet = new MagnetLink(InfoHash.of(fill(20, 18)), "magnet-resolved.bin", List.of());

        // Empty tracker list, same as the real DHT-resolved-magnet call site
        // (addFetchedTorrent(magnet, infoDictBytes.get(), List.of(), source)) - avoids a real
        // network call to a fake tracker host, which createTrackerClient(List.of(...)) would
        // otherwise attempt via TorrentSession.start(). source: null, same as an ordinary
        // REST/UI-triggered magnet add (not watch-folder-sourced).
        engine.addFetchedTorrent(magnet, infoDict, List.of(), null);

        InfoHash resultingInfoHash = InfoHash.of(sha1(infoDict));
        List<LibraryEvent> events = eventStore.forTorrent(resultingInfoHash.hex());
        LibraryEvent added = events.stream().filter(e -> e.type() == EventType.ADDED).findFirst().orElseThrow();
        assertEquals("magnet-resolved.bin", added.torrentName());
        assertEquals("Added via magnet", added.message());
    }

    /** design_docs/0056's own 2026-09-06 addendum: a watch-folder-dropped magnet file's
     * resulting ADDED event reads "Added via watch folder" - the same label a watch-folder-
     * dropped .torrent file already gets - not "Added via magnet", by passing a non-null
     * source through addFetchedTorrent() (the literal string here matches
     * TorrentEngine's own private WATCH_FOLDER_SOURCE constant; not referenced directly since
     * it's private, same as every other test in this class asserting against a literal
     * expected message). */
    @Test
    void addFetchedTorrentRecordsAnAddedViaWatchFolderMessageWhenSourceIsWatchFolder(@TempDir Path tempDir) {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore);
        byte[] infoDict = infoDictBytes("magnet-watch-folder.bin", fill(20, 19));
        MagnetLink magnet = new MagnetLink(InfoHash.of(fill(20, 20)), "magnet-watch-folder.bin", List.of());

        engine.addFetchedTorrent(magnet, infoDict, List.of(), "watch folder");

        InfoHash resultingInfoHash = InfoHash.of(sha1(infoDict));
        List<LibraryEvent> events = eventStore.forTorrent(resultingInfoHash.hex());
        LibraryEvent added = events.stream().filter(e -> e.type() == EventType.ADDED).findFirst().orElseThrow();
        assertEquals("Added via watch folder", added.message());
    }

    /** The "override can enable a limit the global default leaves disabled" direction isn't
     * covered here as a triggering scenario: with no real peer connection, actual ratio is
     * always exactly 0.0, so the only way to make a check deterministically trigger without a
     * real sleep or real uploaded bytes is the degenerate-global-default trick the two tests
     * above use (global enabled at exactly 0.0/0 minutes) - and that trick doesn't work for a
     * custom *override* value, since 0 on an override is reserved for "explicitly no limit"
     * (SeedingLimitOverride's own sentinel convention), not "a custom limit of zero." That
     * resolution logic - a positive override wins over a disabled global default - is already
     * covered where it actually can be tested cheaply: SeedingLimitsTest's
     * aPositiveOverrideRatioWinsRegardlessOfTheGlobalDefault (testing the pure resolver
     * directly, not requiring real ratio to have actually reached the threshold). This test
     * only needs to cover the direction that degenerate values can actually exercise: an
     * override *disabling* a limit the global default would otherwise trigger. */
    @Test
    void aPerTorrentOverrideCanDisableARatioLimitTheGlobalDefaultWouldOtherwiseTrigger(@TempDir Path tempDir)
            throws Exception {
        String announceUrl = startFakeTrackerServer();
        InMemorySettingsStore settingsStore = new InMemorySettingsStore(settingsWithSeedingLimits(true, 0.0, false, 0));
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener(), false, false, settingsStore);

        TorrentSession session = addAlreadySeededTorrent(engine, tempDir, "override-disable.bin", fill(20, 8), announceUrl);
        engine.setSeedingLimitOverride(session.metadata().infoHash(), new SeedingLimitOverride(0, -1));

        engine.checkSeedingLimits();

        assertEquals(TorrentState.SEEDING, session.state());
    }

    @Test
    void aSeedingLimitOverrideSurvivesARestart(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("override-restart.bin", fill(20, 9), announceUrl);

        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession original = firstEngine.addTorrent(torrentBytes).session();
        InfoHash infoHash = original.metadata().infoHash();
        SeedingLimitOverride override = new SeedingLimitOverride(3.5, 120);
        firstEngine.setSeedingLimitOverride(infoHash, override);
        original.stop();

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        TorrentSession restored = secondEngine.getTorrent(infoHash).orElseThrow();
        assertEquals(override, restored.seedingLimitOverride());
    }

    /** design_docs/0072 - same shape as aSeedingLimitOverrideSurvivesARestart above, for the
     * new marker. Covers the persistence side; the live-bandwidth-vs-restart-required-
     * connections asymmetry setTorrentLimits() itself introduces needs a real peer connection
     * to observe directly (like RateLimiterTest's own real-transfer coverage) and isn't
     * exercised here. */
    @Test
    void aTorrentLimitOverrideSurvivesARestart(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = torrentBytes("limits-override-restart.bin", fill(20, 10), announceUrl);

        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession original = firstEngine.addTorrent(torrentBytes).session();
        InfoHash infoHash = original.metadata().infoHash();
        com.grimtorrenter.engine.torrent.TorrentLimitOverride override =
                new com.grimtorrenter.engine.torrent.TorrentLimitOverride(500_000, 250_000, 10);
        firstEngine.setTorrentLimits(infoHash, override);
        assertEquals(override, original.torrentLimits());
        original.stop();

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        TorrentSession restored = secondEngine.getTorrent(infoHash).orElseThrow();
        assertEquals(override, restored.torrentLimits());
    }

    /** Two 8-byte files, 8-byte pieces (so each file is exactly one piece and skipping either
     * never touches the other's piece) - just enough shape for file-priority persistence tests,
     * which don't need any real data on disk. */
    private static byte[] twoFileTorrentBytes(String name, String announceUrl) {
        byte[] fileA = fill(8, 1);
        byte[] fileB = fill(8, 50);
        byte[] hashes = new byte[40];
        System.arraycopy(sha1(fileA), 0, hashes, 0, 20);
        System.arraycopy(sha1(fileB), 0, hashes, 20, 20);
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of(name),
                BString.of("piece length"), new BInteger(8),
                BString.of("pieces"), BString.of(hashes),
                BString.of("files"), new BList(List.of(
                        new BDictionary(Map.of(BString.of("length"), new BInteger(8),
                                BString.of("path"), new BList(List.of(BString.of("a.bin"))))),
                        new BDictionary(Map.of(BString.of("length"), new BInteger(8),
                                BString.of("path"), new BList(List.of(BString.of("b.bin")))))))));
        BDictionary top = new BDictionary(Map.of(
                BString.of("announce"), BString.of(announceUrl),
                BString.of("info"), info));
        return BencodeEncoder.encode(top);
    }

    private static Path filePrioritiesMarker(Path tempDir, InfoHash infoHash) {
        return tempDir.resolve("torrents").resolve(infoHash.hex()).resolve(".grimtorrenter-file-priorities");
    }

    /** design_docs/0075 - same shape as aTorrentLimitOverrideSurvivesARestart above. Also pins
     * the marker's sparse format: only files not at MEDIUM get a line. */
    @Test
    void filePrioritiesSurviveARestartAndOnlyNonMediumFilesArePersisted(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        byte[] torrentBytes = twoFileTorrentBytes("priorities-restart", announceUrl);

        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession original = firstEngine.addTorrent(torrentBytes).session();
        InfoHash infoHash = original.metadata().infoHash();
        FilePriorities priorities = new FilePriorities(List.of(FilePriority.HIGH, FilePriority.MEDIUM));

        assertTrue(firstEngine.setFilePriorities(infoHash, priorities));
        assertEquals(priorities, original.filePriorities());
        assertEquals("0=HIGH\n", Files.readString(filePrioritiesMarker(tempDir, infoHash)));
        original.stop();

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        assertEquals(priorities, secondEngine.getTorrent(infoHash).orElseThrow().filePriorities());
    }

    @Test
    void aTorrentWithNoPrioritiesMarkerStartsWithEveryFileMedium(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        TorrentSession session = engine.addTorrent(twoFileTorrentBytes("no-marker", announceUrl)).session();

        assertEquals(FilePriorities.allMedium(2), session.filePriorities());
        assertFalse(Files.exists(filePrioritiesMarker(tempDir, session.metadata().infoHash())));
        session.stop();
    }

    /** A hand-edited or truncated marker must degrade to MEDIUM for whatever it can't make
     * sense of, not fail the restore: a line with no '=', an unknown priority name, and an
     * out-of-range index are all ignored; the one valid line still applies. */
    @Test
    void aMalformedPrioritiesMarkerDegradesToMediumInsteadOfFailingTheRestore(@TempDir Path tempDir)
            throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession original = firstEngine.addTorrent(twoFileTorrentBytes("malformed", announceUrl)).session();
        InfoHash infoHash = original.metadata().infoHash();
        original.stop();
        Files.writeString(filePrioritiesMarker(tempDir, infoHash),
                "garbage\n0=BOGUS\n7=SKIP\nnotANumber=LOW\n1=SKIP\n");

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        assertEquals(new FilePriorities(List.of(FilePriority.MEDIUM, FilePriority.SKIP)),
                secondEngine.getTorrent(infoHash).orElseThrow().filePriorities());
    }

    /** A marker that would leave nothing wanted (every file skipped) is the same invalid state
     * setFilePriorities() itself rejects - restoring it as-is would make the torrent "complete"
     * instantly, so the read falls back to all-MEDIUM. */
    @Test
    void aPrioritiesMarkerSkippingEveryFileFallsBackToAllMediumOnRestore(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession original = firstEngine.addTorrent(twoFileTorrentBytes("all-skipped", announceUrl)).session();
        InfoHash infoHash = original.metadata().infoHash();
        original.stop();
        Files.writeString(filePrioritiesMarker(tempDir, infoHash), "0=SKIP\n1=SKIP\n");

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        assertEquals(FilePriorities.allMedium(2), secondEngine.getTorrent(infoHash).orElseThrow().filePriorities());
    }

    @Test
    void setFilePrioritiesRejectsAWrongLengthOrAllSkippedArrayWithoutWritingAMarker(@TempDir Path tempDir)
            throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TorrentSession session = engine.addTorrent(twoFileTorrentBytes("rejects", announceUrl)).session();
        InfoHash infoHash = session.metadata().infoHash();

        assertThrows(IllegalArgumentException.class,
                () -> engine.setFilePriorities(infoHash, FilePriorities.allMedium(3)));
        assertThrows(IllegalArgumentException.class, () -> engine.setFilePriorities(infoHash,
                new FilePriorities(List.of(FilePriority.SKIP, FilePriority.SKIP))));

        assertFalse(Files.exists(filePrioritiesMarker(tempDir, infoHash)));
        assertEquals(FilePriorities.allMedium(2), session.filePriorities());
        session.stop();
    }

    @Test
    void setFilePrioritiesForAnUnknownTorrentReturnsFalse(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        assertFalse(engine.setFilePriorities(InfoHash.of(fill(20, 99)), FilePriorities.allMedium(1)));
    }

    private static Path labelIdsMarker(Path tempDir, InfoHash infoHash) {
        return tempDir.resolve("torrents").resolve(infoHash.hex()).resolve(".grimtorrenter-label-ids");
    }

    /** design_docs/0077 - a torrent's labels and the label list itself both survive a restart,
     * and the marker stores ids (not names). */
    @Test
    void torrentLabelsAndTheLabelListSurviveARestart(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        Label movies = firstEngine.labels().create("Movies");
        Label music = firstEngine.labels().create("Music");
        TorrentSession original = firstEngine.addTorrent(torrentBytes("labels-restart.bin", fill(20, 11), announceUrl)).session();
        InfoHash infoHash = original.metadata().infoHash();

        assertTrue(firstEngine.setTorrentLabels(infoHash, List.of(music.id(), movies.id())));
        assertEquals(List.of(music.id(), movies.id()), original.labelIds());
        assertEquals(music.id() + "\n" + movies.id() + "\n", Files.readString(labelIdsMarker(tempDir, infoHash)));
        original.stop();

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        assertEquals(List.of(music.id(), movies.id()), secondEngine.getTorrent(infoHash).orElseThrow().labelIds());
        assertEquals(List.of(movies, music), secondEngine.labels().list());
    }

    /** The whole point of storing ids: a rename changes the registry only, and every torrent
     * still resolves to the same id. */
    @Test
    void renamingALabelLeavesEveryTorrentsIdsUntouched(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        Label movies = engine.labels().create("Movies");
        TorrentSession session = engine.addTorrent(torrentBytes("labels-rename.bin", fill(20, 12), announceUrl)).session();
        engine.setTorrentLabels(session.metadata().infoHash(), List.of(movies.id()));

        engine.labels().rename(movies.id(), "Films");

        assertEquals(List.of(movies.id()), session.labelIds());
        assertEquals("Films", engine.labels().get(movies.id()).orElseThrow().name());
        session.stop();
    }

    @Test
    void deletingALabelStripsItFromEveryTorrentAndItsMarker(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        Label movies = engine.labels().create("Movies");
        Label music = engine.labels().create("Music");
        TorrentSession session = engine.addTorrent(torrentBytes("labels-delete.bin", fill(20, 13), announceUrl)).session();
        InfoHash infoHash = session.metadata().infoHash();
        engine.setTorrentLabels(infoHash, List.of(movies.id(), music.id()));

        assertTrue(engine.deleteLabel(movies.id()));
        assertFalse(engine.deleteLabel(movies.id()));

        assertEquals(List.of(music.id()), session.labelIds());
        assertEquals(music.id() + "\n", Files.readString(labelIdsMarker(tempDir, infoHash)));
        assertEquals(List.of(music), engine.labels().list());
        session.stop();
    }

    @Test
    void setTorrentLabelsRejectsUnknownIdsAndTooManyAndDeduplicates(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        Label movies = engine.labels().create("Movies");
        TorrentSession session = engine.addTorrent(torrentBytes("labels-validate.bin", fill(20, 14), announceUrl)).session();
        InfoHash infoHash = session.metadata().infoHash();

        assertThrows(IllegalArgumentException.class, () -> engine.setTorrentLabels(infoHash, List.of("not-a-label")));
        assertTrue(session.labelIds().isEmpty());
        assertFalse(Files.exists(labelIdsMarker(tempDir, infoHash)));

        assertTrue(engine.setTorrentLabels(infoHash, List.of(movies.id(), movies.id())));
        assertEquals(List.of(movies.id()), session.labelIds());

        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= TorrentEngine.MAX_LABELS_PER_TORRENT; i++) {
            tooMany.add(engine.labels().create("label-" + i).id());
        }
        assertThrows(IllegalArgumentException.class, () -> engine.setTorrentLabels(infoHash, tooMany));
        assertEquals(List.of(movies.id()), session.labelIds());
        session.stop();
    }

    @Test
    void setTorrentLabelsForAnUnknownTorrentReturnsFalse(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        assertFalse(engine.setTorrentLabels(InfoHash.of(fill(20, 98)), List.of()));
    }

    /** A marker holding an id that's no longer in the registry (deleted while the torrent wasn't
     * loaded), a blank line, or a duplicate never surfaces a dead or repeated label after a
     * restore. */
    @Test
    void restoreDropsLabelIdsThatNoLongerExistInTheRegistry(@TempDir Path tempDir) throws Exception {
        String announceUrl = startFakeTrackerServer();
        TorrentEngine firstEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        Label movies = firstEngine.labels().create("Movies");
        TorrentSession original = firstEngine.addTorrent(torrentBytes("labels-dead.bin", fill(20, 15), announceUrl)).session();
        InfoHash infoHash = original.metadata().infoHash();
        original.stop();
        Files.writeString(labelIdsMarker(tempDir, infoHash), "dead-id\n\n" + movies.id() + "\n" + movies.id() + "\n");

        TorrentEngine secondEngine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        secondEngine.restore();

        assertEquals(List.of(movies.id()), secondEngine.getTorrent(infoHash).orElseThrow().labelIds());
    }

    private static Settings proxySettings(boolean blockUnsupported) {
        return Settings.defaults().withProxy(true, "127.0.0.1", 1080, "", blockUnsupported);
    }

    private static TorrentEngine.ServiceStatus serviceNamed(TorrentEngine engine, String name) {
        return engine.serviceStatuses().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    /** design_docs/0079 - with a proxy active and the block switch on (the default), everything a
     * SOCKS5 proxy can't carry is never started, whatever the engine was asked for. */
    @Test
    void aProxyWithTheBlockSwitchOnStartsNeitherDhtNorTheInboundServer(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true, true,
                new InMemorySettingsStore(proxySettings(true)));
        try {
            assertFalse(engine.dhtStatus().enabled());
            assertTrue(engine.peerServerPort().isEmpty());
            assertEquals("DISABLED", serviceNamed(engine, "dht").state().name());
            assertEquals("DISABLED", serviceNamed(engine, "peerServer").state().name());
            assertNotNull(serviceNamed(engine, "dht").reason());
            assertTrue(engine.proxyStatus().active());
            assertTrue(engine.proxyStatus().blockingNow());
            assertFalse(engine.proxyStatus().restartRequired());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void withNoProxyTheSameEngineStartsDhtAndTheInboundServer(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true, true,
                new InMemorySettingsStore(Settings.defaults()));
        try {
            assertTrue(engine.dhtStatus().enabled());
            assertTrue(engine.peerServerPort().isPresent());
            assertNull(serviceNamed(engine, "dht").reason());
            assertFalse(engine.proxyStatus().active());
        } finally {
            engine.shutdown();
        }
    }

    /** Someone who deliberately switched the block off keeps DHT and the inbound server even
     * with a proxy - their call, and the UI says what that exposes. */
    @Test
    void aProxyWithTheBlockSwitchOffStillStartsDht(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true, true,
                new InMemorySettingsStore(proxySettings(false)));
        try {
            assertTrue(engine.dhtStatus().enabled());
            assertTrue(engine.peerServerPort().isPresent());
            assertTrue(engine.proxyStatus().active());
            assertFalse(engine.proxyStatus().blockingNow());
        } finally {
            engine.shutdown();
        }
    }

    /** Enabling a proxy on a running engine can't stop the DHT node that's already running - the
     * status must say a restart is still needed, so nobody assumes they're protected. */
    @Test
    void enablingTheProxyOnARunningEngineReportsThatARestartIsRequired(@TempDir Path tempDir) {
        InMemorySettingsStore store = new InMemorySettingsStore(Settings.defaults());
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), true, true, store);
        try {
            assertFalse(engine.proxyStatus().restartRequired());

            store.update(proxySettings(true));

            assertTrue(engine.proxyStatus().restartRequired());
            assertFalse(engine.proxyStatus().blockingNow());
            assertTrue(engine.dhtStatus().enabled(), "the DHT node that is already running stays up until restart");
        } finally {
            engine.shutdown();
        }
    }

    /** End to end through the engine: a torrent's HTTP tracker announce is tunnelled through the
     * configured proxy, and the proxy is handed the tracker's name to resolve. */
    @Test
    void aTorrentsTrackerAnnounceGoesThroughTheConfiguredProxy(@TempDir Path tempDir) throws Exception {
        String realUrl = startFakeTrackerServer();
        int trackerPort = trackerServer.getAddress().getPort();
        try (FakeSocks5Proxy proxy = new FakeSocks5Proxy()) {
            proxy.map("tracker.invalid", new InetSocketAddress("127.0.0.1", trackerPort));
            Settings settings = Settings.defaults().withProxy(true, "127.0.0.1", proxy.port(), "", true);
            TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), false, false,
                    new InMemorySettingsStore(settings));
            try {
                String url = realUrl.replace("127.0.0.1", "tracker.invalid");

                TorrentSession session = engine.addTorrent(torrentBytes("proxied.bin", fill(20, 21), url)).session();

                assertTrue(proxy.connectTargets.contains("tracker.invalid:" + trackerPort),
                        "the announce must have been tunnelled: " + proxy.connectTargets);
                session.stop();
            } finally {
                engine.shutdown();
            }
        }
    }

    @Test
    void testProxyReportsThatNoneIsConfiguredWhenTheProxyIsOff(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), false, false,
                new InMemorySettingsStore(Settings.defaults()));
        try {
            assertFalse(engine.testProxy().reachable());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void testProxyChecksTheSavedProxyEndToEnd(@TempDir Path tempDir) throws Exception {
        try (FakeSocks5Proxy proxy = new FakeSocks5Proxy()) {
            proxy.requiredUsername = "alice";
            proxy.requiredPassword = "s3cret";
            Settings settings = Settings.defaults().withProxy(true, "127.0.0.1", proxy.port(), "alice", true);
            TorrentEngine engine = new TorrentEngine(tempDir, 0, new NoOpListener(), false, false,
                    new InMemorySettingsStore(settings));
            try {
                assertFalse(engine.testProxy().reachable(), "no password saved yet");

                engine.proxyConfig().setPassword("s3cret");

                assertTrue(engine.testProxy().reachable());
                assertTrue(engine.testProxy().udpSupported());
            } finally {
                engine.shutdown();
            }
        }
    }

    private static void awaitState(TorrentSession session, TorrentState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (session.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, session.state());
    }
}
