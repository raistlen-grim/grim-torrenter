package com.grimtorrenter.engine.engine;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.InMemoryEventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.MetainfoParser;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.mse.EncryptionMode;
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
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

        Path fileA = tempDir.resolve("same-name.bin").resolve("same-name.bin");
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
        assertTrue(Files.exists(tempDir.resolve("reused-name.bin").resolve("reused-name.bin")));
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

        Path directory = tempDir.resolve("file.bin");
        assertTrue(Files.exists(directory.resolve("file.bin")));
        assertFalse(Files.exists(directory.resolve(".grimtorrenter.torrent")));
        assertFalse(Files.exists(directory.resolve(".grimtorrenter-state")));

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
        Files.write(tempDir.resolve("reuse-data.bin").resolve("reuse-data.bin"), content);

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
        Files.createDirectories(tempDir.resolve("unrelated"));

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
        Files.write(tempDir.resolve(name).resolve(name), content);
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
        assertEquals("STOPPED", Files.readString(tempDir.resolve("ratio-limit.bin").resolve(".grimtorrenter-state")).strip());
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

    private static void awaitState(TorrentSession session, TorrentState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (session.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, session.state());
    }
}
