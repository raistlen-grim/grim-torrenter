package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.dht.DhtNode;
import com.grimtorrenter.engine.dht.NodeId;
import com.grimtorrenter.engine.dht.NodeInfo;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.MultiFileTorrent;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentFile;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peer.PeerSource;
import com.grimtorrenter.engine.peerwire.Bitfield;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.Have;
import com.grimtorrenter.engine.peerwire.Interested;
import com.grimtorrenter.engine.peerwire.PeerMessage;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.peerwire.Piece;
import com.grimtorrenter.engine.peerwire.Port;
import com.grimtorrenter.engine.peerwire.Request;
import com.grimtorrenter.engine.peerwire.Unchoke;
import com.grimtorrenter.engine.pex.PexCodec;
import com.grimtorrenter.engine.pex.PexMessage;
import com.grimtorrenter.engine.piece.PieceState;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.storage.FileHandlePool;
import com.grimtorrenter.engine.tracker.NoOpTrackerClient;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import com.grimtorrenter.engine.tracker.TrackerClient;
import com.grimtorrenter.engine.tracker.TrackerEvent;
import com.grimtorrenter.engine.tracker.TrackerRequest;
import com.grimtorrenter.engine.tracker.TrackerResponse;
import com.grimtorrenter.engine.tracker.TrackerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentSessionTest {

    private static final Duration DHT_TEST_TIMEOUT = Duration.ofSeconds(2);

    private ServerSocket serverSocket;
    private final List<DhtNode> dhtNodes = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        dhtNodes.forEach(DhtNode::close);
    }

    /** Ephemeral (port 0) loopback DHT nodes, same setup as PeerLookupTest - the DHT
     * backstop is exercised end-to-end over real local UDP sockets, not faked. */
    private DhtNode createDhtNode(int idSeed) {
        DhtNode node = new DhtNode(NodeId.of(fill(20, idSeed)), 0);
        dhtNodes.add(node);
        return node;
    }

    private static NodeInfo contactOf(DhtNode node) {
        return new NodeInfo(node.ourId(), InetAddress.getLoopbackAddress(), node.port());
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

    private static PeerId fakeRemotePeerId() {
        return PeerId.of(fill(20, 100));
    }

    /** Reads and discards messages until one of the requested type arrives - the client
     * legitimately interleaves other protocol messages (Interested, KeepAlive, ...). */
    private static <T extends PeerMessage> T readUntil(InputStream in, Class<T> type) throws IOException {
        while (true) {
            PeerMessage message = PeerWireCodec.readMessage(in);
            if (type.isInstance(message)) {
                return type.cast(message);
            }
        }
    }

    private static TorrentMetadata singlePieceMetadata(byte[] content) {
        return new SingleFileTorrent("file.bin", content.length, content.length,
                new PieceHashes(sha1(content)), InfoHash.of(fill(20, 9)), null, List.of());
    }

    /** BEP 27 - same shape as singlePieceMetadata but with isPrivate true, for tests proving
     * DHT/PEX are never touched for a private torrent. Distinct info hash from
     * singlePieceMetadata's so a private-torrent test's DhtNode fixtures never collide with a
     * non-private test's, even though each test method already uses its own isolated DhtNode
     * set. */
    private static TorrentMetadata privateSinglePieceMetadata(byte[] content) {
        return new SingleFileTorrent("file.bin", content.length, content.length,
                new PieceHashes(sha1(content)), InfoHash.of(fill(20, 77)), null, List.of(), true);
    }

    private static TorrentMetadata twoPieceMetadata(byte[] piece0, byte[] piece1) {
        byte[] hashes = new byte[40];
        System.arraycopy(sha1(piece0), 0, hashes, 0, 20);
        System.arraycopy(sha1(piece1), 0, hashes, 20, 20);
        return new SingleFileTorrent("file.bin", piece0.length + piece1.length, piece0.length,
                new PieceHashes(hashes), InfoHash.of(fill(20, 9)), null, List.of());
    }

    /** Two 10-byte files laid out contiguously (20 bytes total) under 8-byte pieces, so the
     * middle piece [8,16) straddles the boundary: 2 bytes belong to file A ([8,10)) and 6
     * bytes belong to file B ([10,16)) - the exact case files() exists to handle correctly.
     * See design_docs/0031. */
    private static TorrentMetadata straddlingBoundaryMultiFileMetadata(byte[] content) {
        byte[] piece0 = Arrays.copyOfRange(content, 0, 8);
        byte[] piece1 = Arrays.copyOfRange(content, 8, 16);
        byte[] piece2 = Arrays.copyOfRange(content, 16, 20);
        byte[] hashes = new byte[60];
        System.arraycopy(sha1(piece0), 0, hashes, 0, 20);
        System.arraycopy(sha1(piece1), 0, hashes, 20, 20);
        System.arraycopy(sha1(piece2), 0, hashes, 40, 20);
        List<TorrentFile> files = List.of(
                new TorrentFile(List.of("a.bin"), 10),
                new TorrentFile(List.of("b.bin"), 10));
        return new MultiFileTorrent("multi", files, 8, new PieceHashes(hashes),
                InfoHash.of(fill(20, 9)), null, List.of());
    }

    private static final class FakeTrackerClient implements TrackerClient {
        final List<TrackerRequest> requests = new CopyOnWriteArrayList<>();
        volatile List<PeerAddress> peersToReturn = List.of();
        volatile RuntimeException failure;
        /** Empty unless a test needs to prove TorrentSession.trackers() actually delegates
         * to the wrapped TrackerClient - see trackersDelegatesToTheWrappedTrackerClient. */
        volatile List<TrackerStatus> statusesToReturn = List.of();
        /** checkForCompletion() sends this announce AFTER releasing the lock that triggers
         * the SEEDING state-changed event (see design_docs/0017) - waiting on the state
         * transition alone doesn't guarantee this has happened yet, so tests that need to
         * know the COMPLETED announce was actually sent should await this instead. */
        final CountDownLatch completedAnnounceLatch = new CountDownLatch(1);

        @Override
        public List<TrackerStatus> statuses() {
            return statusesToReturn;
        }

        @Override
        public TrackerResponse announce(TrackerRequest request) {
            requests.add(request);
            if (request.event() == TrackerEvent.COMPLETED) {
                completedAnnounceLatch.countDown();
            }
            if (failure != null) {
                throw failure;
            }
            return new TrackerResponse(3600, null, 0, 0, peersToReturn, null, null);
        }
    }

    private static final class RecordingListener implements TorrentSessionListener {
        final List<TorrentState> stateChanges = new CopyOnWriteArrayList<>();
        final CountDownLatch seedingLatch = new CountDownLatch(1);
        final CountDownLatch pieceCompletedLatch = new CountDownLatch(1);

        @Override
        public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
            stateChanges.add(newState);
            if (newState == TorrentState.SEEDING) {
                seedingLatch.countDown();
            }
        }

        @Override
        public void onPieceCompleted(TorrentSession session, int pieceIndex) {
            pieceCompletedLatch.countDown();
        }
    }

    @Test
    void startAnnouncesStartedEventAndTransitionsToDownloading(@TempDir Path tempDir) throws IOException {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        FakeTrackerClient tracker = new FakeTrackerClient();
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null);
        session.start();
        try {
            assertEquals(1, tracker.requests.size());
            assertEquals(TrackerEvent.STARTED, tracker.requests.get(0).event());
            assertEquals(TorrentState.DOWNLOADING, session.state());
        } finally {
            session.stop();
        }
    }

    @Test
    void startTransitionsToErrorWhenTrackerFails(@TempDir Path tempDir) throws IOException {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.failure = new RuntimeException("tracker down");
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null);
        session.start();

        assertEquals(TorrentState.ERROR, session.state());
        assertNotNull(session.lastError());
    }

    /** Same as startTransitionsToErrorWhenTrackerFails but with a DhtNode present - since
     * this DHT node has no known contacts at all (an empty routing table, matching
     * PeerLookupTest's lookupWithNoKnownContactsReturnsEmptyList), the backstop lookup
     * itself completes but finds nobody, which is still not "DHT unavailable" - confirms
     * ERROR is reserved for when no peer-discovery path exists at all, not merely "found no
     * peers this attempt." See design_docs/0036. */
    @Test
    void startTransitionsToErrorWhenTrackerFailsAndDhtFindsNoPeers(@TempDir Path tempDir) throws IOException {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.failure = new RuntimeException("tracker down");
        RecordingListener listener = new RecordingListener();
        DhtNode sessionDht = createDhtNode(1);

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, sessionDht);
        try {
            session.start();

            assertEquals(TorrentState.DOWNLOADING, session.state());
            assertEquals(0, session.peers().size());
        } finally {
            session.stop();
        }
    }

    /** The DHT backstop itself: every tracker fails on start(), but a peer for this
     * infoHash is discoverable via DHT (announced to a node this session's DhtNode knows
     * about) - drives a real download through it, over real local UDP sockets for the DHT
     * exchange (same style as PeerLookupTest) plus the file's existing fake-peer/ServerSocket
     * fixture for the actual peer-wire connection. See design_docs/0036. */
    @Test
    void fallsBackToDhtWhenAllTrackersFailOnStart(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        AtomicReference<Request> receivedRequest = new AtomicReference<>();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                Handshake theirHandshake = PeerWireCodec.readHandshake(socket.getInputStream());
                if (!infoHash.equals(theirHandshake.infoHash())) {
                    throw new AssertionError("info hash mismatch");
                }
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0x80}));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());
                receivedRequest.set(readUntil(socket.getInputStream(), Request.class));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(0, 0, content));
                readUntil(socket.getInputStream(), Have.class);
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        DhtNode sessionDht = createDhtNode(1);
        DhtNode dhtResponder = createDhtNode(2);
        DhtNode peerAnnouncer = createDhtNode(3);
        sessionDht.routingTable().insert(contactOf(dhtResponder));

        peerAnnouncer.routingTable().insert(contactOf(dhtResponder));
        // findPeers's own side effect (see PeerLookupTest's findPeersAlsoAnnouncesUsToTheRespondingNodes)
        // announces peerAnnouncer to dhtResponder as a peer on fakePeerAddress.port() -
        // exactly the get_peers+announce_peer sequence a real peer would perform, but
        // through DhtNode's public API rather than its package-private getPeers/announcePeer.
        peerAnnouncer.findPeers(infoHash, fakePeerAddress.port(), false, DHT_TEST_TIMEOUT);

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.failure = new RuntimeException("tracker down");
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, PeerId.of(fill(20, 50)), 6881, listener, sessionDht);
        try {
            session.start();

            assertEquals(TorrentState.DOWNLOADING, session.state());
            assertTrue(session.isDhtBackstopActive());
            assertTrue(listener.seedingLatch.await(15, TimeUnit.SECONDS));
            assertEquals(TorrentState.SEEDING, session.state());
            assertEquals(new Request(0, 0, content.length), receivedRequest.get());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("file.bin")));
    }

    /** BEP 27 counterpart to fallsBackToDhtWhenAllTrackersFailOnStart directly above - same
     * setup (a peer genuinely is discoverable via DHT for this info hash), but the torrent is
     * private, so dhtEligible() must exclude it even though dhtNode is configured and healthy.
     * Proves the private torrent goes straight to ERROR instead of silently getting a DHT
     * peer-discovery leak the moment its tracker fails - the exact live gap design_docs/0036's
     * 2026-09-06 revision closed (BEP 27 was never parsed or respected anywhere before it). */
    @Test
    void privateTorrentNeverFallsBackToDhtWhenTrackerFails(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = privateSinglePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();

        DhtNode sessionDht = createDhtNode(1);
        DhtNode dhtResponder = createDhtNode(2);
        DhtNode peerAnnouncer = createDhtNode(3);
        sessionDht.routingTable().insert(contactOf(dhtResponder));
        peerAnnouncer.routingTable().insert(contactOf(dhtResponder));
        peerAnnouncer.findPeers(infoHash, 6882, false, DHT_TEST_TIMEOUT);

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.failure = new RuntimeException("tracker down");
        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, new RecordingListener(), sessionDht);
        try {
            session.start();

            assertEquals(TorrentState.ERROR, session.state());
            assertFalse(session.isDhtBackstopActive());
        } finally {
            session.stop();
        }
    }

    /** reannounce() and discoverPeersViaDht() are both package-private specifically so this
     * can trigger cycles directly instead of waiting out real scheduled intervals. Starts
     * normally (tracker succeeds, zero peers), fails the tracker and drives one reannounce -
     * isDhtBackstopActive() should reflect the failure immediately, purely as a tracker-health
     * signal now (design_docs/0036's own 2026-09-06 revision decoupled it from whether a DHT
     * lookup actually runs). A separate, directly-triggered discoverPeersViaDht() cycle is
     * what actually connects the DHT-discovered peer - proving DHT peer discovery no longer
     * depends on reannounce() at all. Then simulates the tracker recovering on the next
     * reannounce and confirms the flag reverts to false - proving it tracks current reality,
     * not "ever failed this session." See design_docs/0036/0039. */
    @Test
    void fallsBackToDhtWhenReannounceFails(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();
        PeerId remoteId = PeerId.of(fill(20, 50));

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, remoteId));
                Thread.sleep(500); // keep the connection open long enough for the assertion below
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        DhtNode sessionDht = createDhtNode(1);
        DhtNode dhtResponder = createDhtNode(2);
        DhtNode peerAnnouncer = createDhtNode(3);
        sessionDht.routingTable().insert(contactOf(dhtResponder));

        peerAnnouncer.routingTable().insert(contactOf(dhtResponder));
        // findPeers's own side effect (see PeerLookupTest's findPeersAlsoAnnouncesUsToTheRespondingNodes)
        // announces peerAnnouncer to dhtResponder as a peer on fakePeerAddress.port() -
        // exactly the get_peers+announce_peer sequence a real peer would perform, but
        // through DhtNode's public API rather than its package-private getPeers/announcePeer.
        peerAnnouncer.findPeers(infoHash, fakePeerAddress.port(), false, DHT_TEST_TIMEOUT);

        FakeTrackerClient tracker = new FakeTrackerClient();
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), sessionDht);
        try {
            session.start();
            assertEquals(TorrentState.DOWNLOADING, session.state());

            tracker.failure = new RuntimeException("tracker down");
            session.reannounce();
            assertTrue(session.isDhtBackstopActive());

            session.discoverPeersViaDht();
            List<TorrentSession.PeerSnapshot> peers = awaitOnePeer(session);
            assertEquals(1, peers.size());
            assertEquals(fakePeerAddress, peers.get(0).address());

            tracker.failure = null;
            session.reannounce();
            assertFalse(session.isDhtBackstopActive());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    /** The actual new capability design_docs/0036's 2026-09-06 revision adds: DHT peer
     * discovery concurrent with a perfectly healthy tracker, not just a last-resort backstop
     * once the tracker has failed. The tracker here never fails and never even offers this
     * peer itself (FakeTrackerClient's default empty peersToReturn) - the only way this
     * session ever learns about it is discoverPeersViaDht(), triggered directly the same way
     * fallsBackToDhtWhenReannounceFails triggers it, but without ever touching tracker.failure
     * at all. isDhtBackstopActive() staying false throughout is the proof this isn't the
     * degraded/backstop path - a genuinely healthy tracker session got a DHT-sourced peer. */
    @Test
    void discoverPeersViaDhtSupplementsAHealthyTracker(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();
        PeerId remoteId = PeerId.of(fill(20, 50));

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, remoteId));
                Thread.sleep(500); // keep the connection open long enough for the assertion below
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        DhtNode sessionDht = createDhtNode(1);
        DhtNode dhtResponder = createDhtNode(2);
        DhtNode peerAnnouncer = createDhtNode(3);
        sessionDht.routingTable().insert(contactOf(dhtResponder));
        peerAnnouncer.routingTable().insert(contactOf(dhtResponder));
        peerAnnouncer.findPeers(infoHash, fakePeerAddress.port(), false, DHT_TEST_TIMEOUT);

        FakeTrackerClient tracker = new FakeTrackerClient();
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), sessionDht);
        try {
            session.start();
            assertEquals(TorrentState.DOWNLOADING, session.state());
            assertFalse(session.isDhtBackstopActive());
            assertTrue(session.peers().isEmpty());

            session.discoverPeersViaDht();
            List<TorrentSession.PeerSnapshot> peers = awaitOnePeer(session);
            assertEquals(1, peers.size());
            assertEquals(fakePeerAddress, peers.get(0).address());
            assertFalse(session.isDhtBackstopActive());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    @Test
    void downloadsSinglePieceFromFakePeerAndReachesSeeding(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        AtomicReference<Request> receivedRequest = new AtomicReference<>();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                Handshake theirHandshake = PeerWireCodec.readHandshake(socket.getInputStream());
                if (!infoHash.equals(theirHandshake.infoHash())) {
                    throw new AssertionError("info hash mismatch");
                }
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0x80}));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());

                // The client legitimately sends other messages first (e.g. Interested, in
                // response to our Bitfield) before ever sending a Request - read past those
                // rather than assuming the very next message is the one we're waiting for.
                receivedRequest.set(readUntil(socket.getInputStream(), Request.class));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(0, 0, content));

                readUntil(socket.getInputStream(), Have.class);
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, PeerId.of(fill(20, 50)), 6881, listener, null);
        try {
            session.start();

            assertTrue(listener.seedingLatch.await(15, TimeUnit.SECONDS));
            assertTrue(listener.pieceCompletedLatch.await(1, TimeUnit.SECONDS));
            assertTrue(tracker.completedAnnounceLatch.await(5, TimeUnit.SECONDS));
            assertEquals(TorrentState.SEEDING, session.state());
            assertEquals(1.0, session.progress());
            assertEquals(new Request(0, 0, content.length), receivedRequest.get());
            assertTrue(tracker.requests.stream().anyMatch(r -> r.event() == TrackerEvent.COMPLETED));
            // A genuinely just-downloaded torrent (never restored) must never be flagged as
            // "already complete before we started" - that flag is what TorrentEventListener
            // (grimtorrenter-app) uses to tell a real completion apart from a restore/resume
            // rediscovering old data, and this is the one case that must still count as real.
            assertFalse(session.wasCompleteOnRestore());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("file.bin")));
    }

    /** End-to-end proof the download RateLimiter is actually wired into a real transfer,
     * not just unit-tested in isolation (see RateLimiterTest for that) - a 1000-byte piece
     * capped at 500 bytes/sec must take measurably longer than an unthrottled loopback
     * transfer would (low single-digit ms), not just eventually complete. A generous lower
     * bound (not a tight upper one) avoids flakiness while still clearly proving real
     * throttling happened. See design_docs/0042. */
    @Test
    void downloadRateLimitActuallyThrottlesARealTransfer(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(1000, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                Handshake theirHandshake = PeerWireCodec.readHandshake(socket.getInputStream());
                if (!infoHash.equals(theirHandshake.infoHash())) {
                    throw new AssertionError("info hash mismatch");
                }
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0x80}));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());
                readUntil(socket.getInputStream(), Request.class);
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(0, 0, content));
                readUntil(socket.getInputStream(), Have.class);
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        InMemorySettingsStore settingsStore = new InMemorySettingsStore(new Settings(true, true, 0, 500));
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir, PeerId.of(fill(20, 50)),
                6881, listener, null, RateLimiters.from(settingsStore));
        long start = System.nanoTime();
        try {
            session.start();
            assertTrue(listener.seedingLatch.await(15, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs >= 1500,
                    "expected the 1000-byte piece to be throttled to roughly 2s at 500 bytes/sec, took " + elapsedMs + "ms");
        } finally {
            session.stop();
        }
        fakePeer.join(2000);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("file.bin")));
    }

    /**
     * The same fake peer both gives us the piece AND then asks for it back - narratively a
     * bit odd for a real swarm, but it's the simplest way to exercise the actual code path
     * being tested (do we unchoke an interested peer and correctly serve a requested block)
     * without building a separate two-peer test harness.
     */
    @Test
    void unchokesInterestedPeerAndServesRequestedBlockOnceWeHaveIt(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        AtomicReference<Piece> receivedPiece = new AtomicReference<>();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0x80}));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());

                readUntil(socket.getInputStream(), Request.class);
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(0, 0, content));
                readUntil(socket.getInputStream(), Have.class);

                // Now ask for it back - exercises the serving path.
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Interested());
                readUntil(socket.getInputStream(), Unchoke.class);
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Request(0, 0, content.length));
                receivedPiece.set(readUntil(socket.getInputStream(), Piece.class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, PeerId.of(fill(20, 50)), 6881, listener, null);
        try {
            session.start();
            assertTrue(listener.seedingLatch.await(15, TimeUnit.SECONDS));

            fakePeer.join(5000);
            assertEquals(new Piece(0, 0, content), receivedPiece.get());
        } finally {
            session.stop();
        }
    }

    /** A trackerless session (NoOpTrackerClient - see design_docs/0028) starts with zero
     * known peers; addKnownPeers is how a DHT-discovered peer list reaches it. */
    @Test
    void addKnownPeersSeedsAdditionalPeersAndAttemptsConnection(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        CountDownLatch handshakeReceived = new CountDownLatch(1);
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                Handshake theirHandshake = PeerWireCodec.readHandshake(socket.getInputStream());
                if (infoHash.equals(theirHandshake.infoHash())) {
                    handshakeReceived.countDown();
                }
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        TorrentSession session = TorrentSession.create(metadata, new NoOpTrackerClient(), tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            session.addKnownPeers(List.of(fakePeerAddress), PeerSource.UNKNOWN);

            assertTrue(handshakeReceived.await(5, TimeUnit.SECONDS));
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    /** The connection-refill fix (design_docs/0017's own 2026-09-06 revision,
     * design_docs/0036's matching addendum): a failed address must never be retried again
     * this session, even though the tracker keeps re-offering it on every reannounce - proof
     * the new failedAddresses set actually excludes it from fillConnections()'s candidate
     * selection, not just from the one fillConnections() call immediately following its own
     * failure. badServer accepts the TCP connection (so a real attempt genuinely happens,
     * counted precisely) but closes immediately without completing the peer-wire handshake,
     * failing attemptConnect() the same way an unresponsive real peer would. */
    @Test
    void failedAddressIsNeverRetriedEvenWhenTheTrackerKeepsOfferingIt(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));

        ServerSocket badServer = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        PeerAddress badAddress = new PeerAddress(InetAddress.getLoopbackAddress(), badServer.getLocalPort());
        AtomicInteger attemptCount = new AtomicInteger();
        Thread badPeer = new Thread(() -> {
            try {
                while (true) {
                    Socket socket = badServer.accept();
                    attemptCount.incrementAndGet();
                    socket.close();
                }
            } catch (IOException ignored) {
                // badServer.close() in the test's finally unblocks accept() - expected exit.
            }
        });
        badPeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(badAddress);
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();

            long deadline = System.currentTimeMillis() + 5000;
            while (attemptCount.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, attemptCount.get());

            // The tracker still offers the same (now-failed) address on this reannounce -
            // fillConnections() must exclude it via failedAddresses regardless.
            session.reannounce();
            Thread.sleep(200);
            assertEquals(1, attemptCount.get());
        } finally {
            session.stop();
            badServer.close();
        }
        badPeer.join(2000);
    }

    /** Regression test for two real production bugs (both 2026-09-06, both traced to
     * fillConnections()'s own reactive refill-on-failure): an `OutOfMemoryError` (fixed with
     * connectionSlots, a Semaphore bounding *total* concurrent attempts) and, once that alone
     * was deployed, a second bug where the *same* address got attempted over a dozen times
     * within milliseconds instead of spreading across the known pool (fixed with
     * inFlightAddresses, claimed atomically at candidate-selection time - see both fields'
     * own Javadoc). Each fake server loops accepting connections and counts them by port,
     * closing each one immediately without completing the handshake (failing attemptConnect())
     * - proves both properties in one pass: peak concurrent attempts never exceeds
     * MAX_CONNECTIONS (30), and every one of the 60 candidates is attempted *exactly once*,
     * never zero (every candidate eventually reached) and never more than once (no duplicate/
     * wasted attempts at an address already claimed or already permanently failed). See
     * design_docs/0017's own 2026-09-06 revision. */
    @Test
    void neverDuplicatesOrExceedsMaxConnectionsEvenUnderABurstOfFailures(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));

        int candidateCount = 60;
        List<PeerAddress> candidates = new ArrayList<>();
        List<ServerSocket> badServers = new ArrayList<>();
        List<Thread> badPeers = new ArrayList<>();
        AtomicInteger currentlyOpen = new AtomicInteger();
        AtomicInteger peakOpen = new AtomicInteger();
        Map<Integer, AtomicInteger> attemptsByPort = new ConcurrentHashMap<>();

        for (int i = 0; i < candidateCount; i++) {
            ServerSocket badServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            badServers.add(badServer);
            int port = badServer.getLocalPort();
            candidates.add(new PeerAddress(InetAddress.getLoopbackAddress(), port));
            attemptsByPort.put(port, new AtomicInteger());
            Thread badPeer = new Thread(() -> {
                try {
                    while (true) {
                        Socket socket = badServer.accept();
                        attemptsByPort.get(port).incrementAndGet();
                        int open = currentlyOpen.incrementAndGet();
                        peakOpen.updateAndGet(peak -> Math.max(peak, open));
                        // Closes immediately, without completing the peer-wire handshake -
                        // attemptConnect() fails reading it. Deliberately not held open (unlike
                        // this file's other single-address tests) - a duplicate attempt racing
                        // in in this test needs to resolve fast enough that both attempts land
                        // within the same fillConnections() cascade, not be delayed past it.
                        socket.close();
                        currentlyOpen.decrementAndGet();
                    }
                } catch (IOException ignored) {
                    // badServer.close() in the test's finally unblocks accept() - expected exit.
                }
            });
            badPeers.add(badPeer);
            badPeer.start();
        }

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = candidates;
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            // Enough time for every candidate to be attempted and fail, across as many
            // fillConnections() refill cascades as it takes.
            Thread.sleep(4000);

            assertTrue(peakOpen.get() <= 30,
                    "peak concurrent attempts was " + peakOpen.get() + ", expected <= MAX_CONNECTIONS (30)");
            for (Map.Entry<Integer, AtomicInteger> entry : attemptsByPort.entrySet()) {
                assertEquals(1, entry.getValue().get(),
                        "port " + entry.getKey() + " was attempted " + entry.getValue().get() + " time(s), expected exactly 1");
            }
        } finally {
            session.stop();
            for (ServerSocket badServer : badServers) {
                badServer.close();
            }
        }
        for (Thread badPeer : badPeers) {
            badPeer.join(2000);
        }
    }

    /** Genuinely trackerless (NoOpTrackerClient) - enterDownloading()'s discoverPeersViaDht()
     * task, scheduled with a zero initial delay, fires essentially immediately after start()
     * returns, replacing the old startViaDht()/TorrentEngine.seedFromDhtIfTrackerless()
     * mechanisms (both removed - see design_docs/0036's own 2026-09-06 revision). Same
     * DHT-over-real-loopback-UDP setup as fallsBackToDhtWhenAllTrackersFailOnStart, but for
     * the genuinely-trackerless path rather than the tracker-degraded backstop path -
     * isDhtBackstopActive() should stay false throughout, since a trackerless torrent's
     * NoOpTrackerClient never fails. */
    @Test
    void discoverPeersViaDhtFindsPeersImmediatelyForATrackerlessTorrent(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        CountDownLatch handshakeReceived = new CountDownLatch(1);
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                Handshake theirHandshake = PeerWireCodec.readHandshake(socket.getInputStream());
                if (infoHash.equals(theirHandshake.infoHash())) {
                    handshakeReceived.countDown();
                }
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        DhtNode sessionDht = createDhtNode(1);
        DhtNode dhtResponder = createDhtNode(2);
        DhtNode peerAnnouncer = createDhtNode(3);
        sessionDht.routingTable().insert(contactOf(dhtResponder));
        peerAnnouncer.routingTable().insert(contactOf(dhtResponder));
        peerAnnouncer.findPeers(infoHash, fakePeerAddress.port(), false, DHT_TEST_TIMEOUT);

        TorrentSession session = TorrentSession.create(metadata, new NoOpTrackerClient(), tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), sessionDht);
        try {
            session.start();

            assertTrue(handshakeReceived.await(5, TimeUnit.SECONDS));
            assertEquals(TorrentState.DOWNLOADING, session.state());
            assertFalse(session.isDhtBackstopActive());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    /** Proves discoverPeersViaDht()'s periodic re-query genuinely works on its own schedule,
     * not just the immediate first tick - that first tick runs before the peer is announced
     * to DHT at all, so it finds nothing; the peer only becomes discoverable afterwards, and
     * is picked up on a later scheduled cycle without the task ever being triggered directly
     * (unlike fallsBackToDhtWhenReannounceFails, which does trigger one cycle manually) - a
     * short dhtReannounceIntervalSeconds keeps the test itself fast. This also demonstrates
     * the mechanism is now independent of reannounce()/the tracker entirely - a genuinely
     * trackerless torrent's NoOpTrackerClient plays no role in finding this peer at all. See
     * design_docs/0036's own 2026-09-06 revision. */
    @Test
    void discoverPeersViaDhtPicksUpANewlyAnnouncedPeerOnALaterCycle(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                Thread.sleep(500); // keep the connection open long enough for the assertion below
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        DhtNode sessionDht = createDhtNode(1);
        DhtNode dhtResponder = createDhtNode(2);
        DhtNode peerAnnouncer = createDhtNode(3);
        sessionDht.routingTable().insert(contactOf(dhtResponder));
        peerAnnouncer.routingTable().insert(contactOf(dhtResponder));

        TorrentSession session = TorrentSession.create(metadata, new NoOpTrackerClient(), tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), sessionDht,
                RateLimiters.unlimited(), FileHandlePool.unbounded(), new Semaphore(Integer.MAX_VALUE),
                () -> EncryptionMode.DISABLED, SeedingLimitOverride.INHERIT, Instant.now(), () -> 1L);
        try {
            session.start();
            assertEquals(TorrentState.DOWNLOADING, session.state());
            assertTrue(session.peers().isEmpty());

            // Only now does the peer become discoverable via DHT - start()'s own lookup
            // already ran and found nothing, so it must come from a later scheduled cycle.
            peerAnnouncer.findPeers(infoHash, fakePeerAddress.port(), false, DHT_TEST_TIMEOUT);

            List<TorrentSession.PeerSnapshot> peers = awaitOnePeer(session);
            assertEquals(1, peers.size());
            assertEquals(fakePeerAddress, peers.get(0).address());
            assertFalse(session.isDhtBackstopActive());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    /** acceptIncomingConnection() is the inbound counterpart to addKnownPeers()'s outbound
     * connection above - here the "fake peer" is the one who dials in and speaks first
     * (matching a real inbound connection), and the test itself plays PeerServer's role of
     * accepting the socket and reading the initial handshake before handing both off to
     * the session. See design_docs/0038. */
    @Test
    void acceptIncomingConnectionAdoptsARemotelyInitiatedConnection(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();
        PeerId remoteId = PeerId.of(fill(20, 50));

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int ourPort = serverSocket.getLocalPort();

        AtomicReference<Port> receivedPort = new AtomicReference<>();
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), ourPort)) {
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, remoteId));
                PeerWireCodec.readHandshake(socket.getInputStream());
                receivedPort.set(readUntil(socket.getInputStream(), Port.class));
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        Socket accepted = serverSocket.accept();
        Handshake remoteHandshake = PeerWireCodec.readHandshake(accepted.getInputStream());

        TorrentSession session = TorrentSession.create(metadata, new FakeTrackerClient(), tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            session.acceptIncomingConnection(accepted, accepted.getInputStream(), accepted.getOutputStream(), remoteHandshake);

            List<TorrentSession.PeerSnapshot> peers = awaitOnePeer(session);
            assertEquals(1, peers.size());
            assertEquals(remoteId, peers.get(0).peerId());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
        assertEquals(new Port(6881), receivedPort.get());
    }

    /** Extracts the id a peer's own extended handshake (extendedMessageId 0) advertised
     * for extensionName - i.e. the id we must use when sending that peer a message of
     * that extension (see PeerConnection.remoteExtensionId's own Javadoc on this half of
     * BEP 10's two-id negotiation). Used by the PEX tests below to correctly identify
     * TorrentSession's own advertised ut_pex id, exactly as a real peer would have to. */
    private static int extractAdvertisedExtensionId(Extended handshake, String extensionName) {
        BValue decoded = BencodeDecoder.decode(handshake.payload());
        if (!(decoded instanceof BDictionary dict) || !(dict.get("m") instanceof BDictionary m)
                || !(m.get(extensionName) instanceof BInteger id)) {
            throw new AssertionError("Extended handshake did not advertise " + extensionName);
        }
        return (int) id.value();
    }

    /** sendPexUpdates() gossips who we're connected to, not our whole known-candidates
     * pool - two fake peers connect (each advertising its own, deliberately different,
     * ut_pex id - proving the per-connection remoteExtensionId lookup, not a shared/wrong
     * one, drives what's sent), then one directly-triggered PEX cycle (see
     * sendPexUpdates()'s own package-private-for-testing note) should tell each about the
     * other, and only the other - never about itself. See design_docs/0040. */
    @Test
    void sendPexUpdatesTellsEachConnectedPeerAboutTheOther(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();

        ServerSocket serverA = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ServerSocket serverB = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress addressA = new PeerAddress(InetAddress.getLoopbackAddress(), serverA.getLocalPort());
        PeerAddress addressB = new PeerAddress(InetAddress.getLoopbackAddress(), serverB.getLocalPort());

        AtomicReference<PexMessage> receivedByA = new AtomicReference<>();
        AtomicReference<PexMessage> receivedByB = new AtomicReference<>();
        CountDownLatch bothReceived = new CountDownLatch(2);
        Thread fakePeerA = new Thread(() -> runFakePexPeer(serverA, infoHash, 5, receivedByA, bothReceived));
        Thread fakePeerB = new Thread(() -> runFakePexPeer(serverB, infoHash, 7, receivedByB, bothReceived));
        fakePeerA.start();
        fakePeerB.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(addressA, addressB);
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            // Waits for each connection's own extended handshake to have actually been
            // received and processed (remoteExtensionId populated) - connectedPeerCount()
            // alone only proves our own connect()/handshake finished, not that the reverse,
            // independent direction (each fake peer's own proactively-sent extended
            // handshake) has landed yet. See hasReceivedExtendedHandshakeFrom's own note.
            long deadline = System.currentTimeMillis() + 5000;
            while ((!session.hasReceivedExtendedHandshakeFrom(addressA)
                    || !session.hasReceivedExtendedHandshakeFrom(addressB))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(session.hasReceivedExtendedHandshakeFrom(addressA));
            assertTrue(session.hasReceivedExtendedHandshakeFrom(addressB));

            session.sendPexUpdates();

            assertTrue(bothReceived.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(addressB), receivedByA.get().added());
            assertEquals(List.of(addressA), receivedByB.get().added());
        } finally {
            session.stop();
        }
        fakePeerA.join(2000);
        fakePeerB.join(2000);
        serverA.close();
        serverB.close();
    }

    private static void runFakePexPeer(ServerSocket serverSocket, InfoHash infoHash, int ourPexId,
                                        AtomicReference<PexMessage> received, CountDownLatch latch) {
        try (Socket socket = serverSocket.accept()) {
            PeerWireCodec.readHandshake(socket.getInputStream());
            PeerWireCodec.writeHandshake(
                    socket.getOutputStream(), Handshake.withExtensionProtocol(infoHash, fakeRemotePeerId()));

            BDictionary ourHandshakeDict = new BDictionary(
                    Map.of(BString.of("m"), new BDictionary(Map.of(BString.of("ut_pex"), new BInteger(ourPexId)))));
            PeerWireCodec.writeMessage(socket.getOutputStream(), new Extended(0, BencodeEncoder.encode(ourHandshakeDict)));

            // The first Extended message is TorrentSession's own extended handshake
            // response - just consumed/skipped here, not otherwise needed by this
            // direction of the test (a message TorrentSession sends TO this fake peer
            // uses *this peer's own* advertised id, per BEP 10 - see the check below -
            // not the id TorrentSession advertised, which only matters for messages sent
            // the other way; see receivingPexAddedFeedsIntoKnownPeersAndAttemptsConnection
            // for that direction).
            readUntil(socket.getInputStream(), Extended.class);

            Extended pexMessage = readUntil(socket.getInputStream(), Extended.class);
            if (pexMessage.extendedMessageId() != ourPexId) {
                throw new AssertionError(
                        "Expected the PEX message on id " + ourPexId + " but got " + pexMessage.extendedMessageId());
            }
            received.set(PexCodec.decode(pexMessage.payload()));
            latch.countDown();
            Thread.sleep(300);
        } catch (Throwable e) {
            // Throwable, not Exception - an AssertionError from the check above (or a
            // decode failure) must still surface as a loud test failure rather than
            // silently vanishing as an uncaught error on this thread, which would just
            // leave bothReceived stuck and the test failing on an opaque timeout instead
            // of the actual cause.
            throw new RuntimeException(e);
        }
    }

    /** BEP 27 counterpart to sendPexUpdatesTellsEachConnectedPeerAboutTheOther above - same
     * two-fake-peer setup, but the torrent is private. Both fake peers still advertise their
     * own ut_pex support (proving extensionsToAdvertise() alone - us simply not advertising
     * support ourselves - isn't what's actually being relied on here); sendPexUpdates() must
     * still refuse to send anything regardless, which is the critical half of the fix that
     * doesn't depend on what we advertise (see extensionsToAdvertise()'s own Javadoc). Proven
     * by bothReceived never counting down within a short bounded wait - runFakePexPeer's
     * second readUntil(Extended.class) blocks forever otherwise, so absence can only be shown
     * within a timeout, not by waiting forever for something that (correctly) never arrives. */
    @Test
    void privateTorrentNeverSendsPexUpdates(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = privateSinglePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();

        ServerSocket serverA = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ServerSocket serverB = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress addressA = new PeerAddress(InetAddress.getLoopbackAddress(), serverA.getLocalPort());
        PeerAddress addressB = new PeerAddress(InetAddress.getLoopbackAddress(), serverB.getLocalPort());

        AtomicReference<PexMessage> receivedByA = new AtomicReference<>();
        AtomicReference<PexMessage> receivedByB = new AtomicReference<>();
        CountDownLatch bothReceived = new CountDownLatch(2);
        Thread fakePeerA = new Thread(() -> runFakePexPeer(serverA, infoHash, 5, receivedByA, bothReceived));
        Thread fakePeerB = new Thread(() -> runFakePexPeer(serverB, infoHash, 7, receivedByB, bothReceived));
        fakePeerA.start();
        fakePeerB.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(addressA, addressB);
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            long deadline = System.currentTimeMillis() + 5000;
            while ((!session.hasReceivedExtendedHandshakeFrom(addressA)
                    || !session.hasReceivedExtendedHandshakeFrom(addressB))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(session.hasReceivedExtendedHandshakeFrom(addressA));
            assertTrue(session.hasReceivedExtendedHandshakeFrom(addressB));

            session.sendPexUpdates();

            assertFalse(bothReceived.await(500, TimeUnit.MILLISECONDS));
        } finally {
            session.stop();
        }
        fakePeerA.join(2000);
        fakePeerB.join(2000);
        serverA.close();
        serverB.close();
    }

    /** The receiving half: a connected peer sends us a ut_pex message introducing a third
     * address we've never heard of - it should feed straight into the existing
     * addKnownPeers() (same mechanism tracker/DHT-discovered peers already use), driving a
     * real connection attempt to that address. See design_docs/0040. */
    @Test
    void receivingPexAddedFeedsIntoKnownPeersAndAttemptsConnection(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress senderAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        ServerSocket introducedServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress introducedAddress = new PeerAddress(InetAddress.getLoopbackAddress(), introducedServer.getLocalPort());

        CountDownLatch introducedHandshakeReceived = new CountDownLatch(1);
        Thread introducedPeer = new Thread(() -> {
            try (Socket socket = introducedServer.accept()) {
                Handshake theirs = PeerWireCodec.readHandshake(socket.getInputStream());
                if (infoHash.equals(theirs.infoHash())) {
                    introducedHandshakeReceived.countDown();
                }
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        introducedPeer.start();

        Thread pexSenderPeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(
                        socket.getOutputStream(), Handshake.withExtensionProtocol(infoHash, fakeRemotePeerId()));

                Extended theirHandshake = readUntil(socket.getInputStream(), Extended.class);
                int ourPexId = extractAdvertisedExtensionId(theirHandshake, "ut_pex");

                PeerWireCodec.writeMessage(socket.getOutputStream(), new Extended(
                        ourPexId, PexCodec.encode(new PexMessage(List.of(introducedAddress), List.of()))));
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        pexSenderPeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(senderAddress);
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            assertTrue(introducedHandshakeReceived.await(5, TimeUnit.SECONDS));
        } finally {
            session.stop();
        }
        pexSenderPeer.join(2000);
        introducedPeer.join(2000);
        introducedServer.close();
    }

    @Test
    void pieceStatesStartAllNeeded(@TempDir Path tempDir) throws IOException {
        TorrentMetadata metadata = twoPieceMetadata(fill(20, 1), fill(20, 21));
        try (TorrentSession session = TorrentSession.create(metadata, new FakeTrackerClient(), tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null)) {
            assertEquals(List.of(PieceState.NEEDED, PieceState.NEEDED), session.pieceStates());
        }
    }

    @Test
    void isTracklessReflectsTheTrackerClientKind(@TempDir Path tempDir) throws IOException {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));

        try (TorrentSession withTracker = TorrentSession.create(metadata, new FakeTrackerClient(),
                tempDir.resolve("a"), fakeRemotePeerId(), 6881, new RecordingListener(), null);
             TorrentSession withoutTracker = TorrentSession.create(metadata, new NoOpTrackerClient(),
                tempDir.resolve("b"), fakeRemotePeerId(), 6881, new RecordingListener(), null)) {
            assertFalse(withTracker.isTrackerless());
            assertTrue(withoutTracker.isTrackerless());
        }
    }

    /** trackers() is a thin delegate to the wrapped TrackerClient's own statuses() - the
     * actual status-tracking logic lives in TrackedTrackerClient/MultiTrackerClient (see
     * their own tests); this only proves TorrentSession wires the delegation through
     * correctly. See design_docs/0031. */
    @Test
    void trackersDelegatesToTheWrappedTrackerClient(@TempDir Path tempDir) throws IOException {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        FakeTrackerClient tracker = new FakeTrackerClient();
        List<TrackerStatus> expected = List.of(
                new TrackerStatus("http://tracker.example/announce", 0, TrackerStatus.State.WORKING,
                        null, null, null, 5, 2, 30));
        tracker.statusesToReturn = expected;

        try (TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null)) {
            assertEquals(expected, session.trackers());
        }
    }

    @Test
    void peersReflectsConnectedPeerState(@TempDir Path tempDir) throws Exception {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        InfoHash infoHash = metadata.infoHash();
        PeerId remoteId = PeerId.of(fill(20, 50));

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, remoteId));
                Thread.sleep(500); // keep the connection open long enough for the assertion below
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            List<TorrentSession.PeerSnapshot> peers = awaitOnePeer(session);

            assertEquals(1, peers.size());
            assertEquals(fakePeerAddress, peers.get(0).address());
            assertEquals(remoteId, peers.get(0).peerId());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    /** Distinguishes percentAvailable from relevance: the peer has piece 0 (which we already
     * have too - restored from disk) and lacks piece 1 (which we still need). percentAvailable
     * counts piece 0 (they have half the torrent); relevance doesn't (the one piece they have
     * isn't one we need) - if these were computed identically, this would catch it. See
     * design_docs/0067. */
    @Test
    void peersReflectsPercentAvailableAndRelevanceSeparately(@TempDir Path tempDir) throws Exception {
        byte[] piece0 = fill(8, 1);
        byte[] piece1 = fill(8, 2);
        TorrentMetadata metadata = twoPieceMetadata(piece0, piece1);
        InfoHash infoHash = metadata.infoHash();
        PeerId remoteId = PeerId.of(fill(20, 50));

        // piece0 correct (already have it), piece1 deliberately wrong (still need it).
        byte[] onDisk = new byte[piece0.length + piece1.length];
        System.arraycopy(piece0, 0, onDisk, 0, piece0.length);
        Files.write(tempDir.resolve("file.bin"), onDisk);

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, remoteId));
                // Bit 0 set (has piece 0), bit 1 clear (lacks piece 1).
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[] {(byte) 0x80}));
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        TorrentSession session = TorrentSession.restoreAsync(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, new RecordingListener(), null, true);
        try {
            awaitState(session, TorrentState.DOWNLOADING);
            awaitOnePeer(session);
            // awaitOnePeer() only waits for the connection itself to be registered - the fake
            // peer's Bitfield arrives asynchronously afterward on the connection's own read
            // loop, so this also waits for it to actually have been applied.
            List<TorrentSession.PeerSnapshot> peers = awaitPercentAvailableAbove(session, 0);

            assertEquals(1, peers.size());
            assertEquals(0.5, peers.get(0).percentAvailable(), 0.001);
            assertEquals(0.0, peers.get(0).relevance(), 0.001);
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    private static List<TorrentSession.PeerSnapshot> awaitPercentAvailableAbove(TorrentSession session, double floor)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<TorrentSession.PeerSnapshot> peers = session.peers();
        while ((peers.isEmpty() || peers.get(0).percentAvailable() <= floor)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            peers = session.peers();
        }
        return peers;
    }

    /** bytesReceived() mirrors bytesUploaded()'s accumulator-plus-live-connections
     * pattern - this proves the "survives disconnect" half of that specifically, since
     * that's the whole reason the accumulator exists rather than just summing live
     * connections. See design_docs/0031. */
    @Test
    void bytesReceivedSurvivesPeerDisconnect(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0x80}));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());
                readUntil(socket.getInputStream(), Request.class);
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(0, 0, content));
                // Keep the connection open briefly so the received block is definitely
                // recorded before this closes it (the try-with-resources close below).
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        TorrentSession session = TorrentSession.create(metadata, tracker, tempDir,
                fakeRemotePeerId(), 6881, new RecordingListener(), null);
        try {
            session.start();
            awaitBytesReceivedAtLeast(session, content.length);
            assertEquals(content.length, session.bytesReceived());

            fakePeer.join(2000);
            awaitNoConnections(session);
            assertEquals(content.length, session.bytesReceived());
        } finally {
            session.stop();
        }
    }

    /** Files don't align with piece boundaries - see straddlingBoundaryMultiFileMetadata().
     * The fake peer answers each Request using that request's own index (rather than a
     * fixed send order) since SequentialPieceSelectionStrategy's exact pipelining isn't
     * this test's concern. Checks progress mid-download, right after the boundary-straddling
     * piece completes, to prove the overlap is actually split rather than credited whole to
     * one file - not just checking the fully-downloaded end state, which a wrong split would
     * also pass. See design_docs/0031. */
    @Test
    void filesReflectsPerFileDownloadProgressAcrossAPieceBoundary(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = straddlingBoundaryMultiFileMetadata(content);
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        CountDownLatch boundaryChecked = new CountDownLatch(1);
        Thread fakePeer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                PeerWireCodec.readHandshake(socket.getInputStream());
                PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0xE0}));
                PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());

                for (int i = 0; i < 3; i++) {
                    Request request = readUntil(socket.getInputStream(), Request.class);
                    int pieceStart = request.index() * 8;
                    byte[] pieceData = Arrays.copyOfRange(content, pieceStart, pieceStart + request.length());
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(request.index(), 0, pieceData));
                    if (i == 1) {
                        // Pause after the boundary-straddling piece (index 1) so the test can
                        // assert mid-download progress before the final piece arrives.
                        boundaryChecked.await(5, TimeUnit.SECONDS);
                    }
                }
                Thread.sleep(300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null);
        try {
            session.start();
            awaitCompletedPieceCount(session, 2);

            List<TorrentSession.FileProgress> partial = session.files();
            assertEquals(2, partial.size());
            assertEquals(List.of("a.bin"), partial.get(0).pathSegments());
            assertEquals(10, partial.get(0).length());
            assertEquals(10, partial.get(0).bytesDownloaded()); // piece0 (8B) + piece1's 2B overlap
            assertEquals(List.of("b.bin"), partial.get(1).pathSegments());
            assertEquals(10, partial.get(1).length());
            assertEquals(6, partial.get(1).bytesDownloaded()); // piece1's 6B overlap only

            boundaryChecked.countDown();
            assertTrue(listener.seedingLatch.await(15, TimeUnit.SECONDS));

            List<TorrentSession.FileProgress> complete = session.files();
            assertEquals(10, complete.get(0).bytesDownloaded());
            assertEquals(10, complete.get(1).bytesDownloaded());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);
    }

    private static void awaitBytesReceivedAtLeast(TorrentSession session, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (session.bytesReceived() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static void awaitNoConnections(TorrentSession session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (session.connectedPeerCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static List<TorrentSession.PeerSnapshot> awaitOnePeer(TorrentSession session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (session.peers().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return session.peers();
    }

    @Test
    void stopIsIdempotentAndSendsStoppedEvent(@TempDir Path tempDir) throws IOException {
        TorrentMetadata metadata = singlePieceMetadata(fill(20, 1));
        FakeTrackerClient tracker = new FakeTrackerClient();
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null);
        session.start();
        session.stop();
        session.stop();

        assertEquals(TorrentState.STOPPED, session.state());
        assertEquals(1, tracker.requests.stream().filter(r -> r.event() == TrackerEvent.STOPPED).count());
    }

    /**
     * Regression test: stop() (used for pause) must not close storage - it's a final field
     * with no reopen path, so a paused-then-resumed session would permanently fail every
     * subsequent read/write with ClosedChannelException otherwise. Drives a real pause and
     * resume mid-download (not just around a no-op start/stop with nothing in flight) and
     * confirms a piece received *after* resuming still gets written successfully. See
     * design_docs/0030.
     */
    @Test
    void pausingAndResumingKeepsStorageUsable(@TempDir Path tempDir) throws Exception {
        byte[] piece0 = fill(20, 1);
        byte[] piece1 = fill(20, 50);
        TorrentMetadata metadata = twoPieceMetadata(piece0, piece1);
        InfoHash infoHash = metadata.infoHash();

        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        PeerAddress fakePeerAddress = new PeerAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());

        Thread fakePeer = new Thread(() -> {
            try {
                // First connection: serve piece 0 only (bitfield advertises just bit 0).
                try (Socket socket = serverSocket.accept()) {
                    PeerWireCodec.readHandshake(socket.getInputStream());
                    PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0x80}));
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());
                    readUntil(socket.getInputStream(), Request.class);
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(0, 0, piece0));
                    Thread.sleep(300);
                }
                // Second connection, after the client pauses and resumes: serve piece 1.
                try (Socket socket = serverSocket.accept()) {
                    PeerWireCodec.readHandshake(socket.getInputStream());
                    PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.of(infoHash, fakeRemotePeerId()));
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Bitfield(new byte[]{(byte) 0x40}));
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Unchoke());
                    readUntil(socket.getInputStream(), Request.class);
                    PeerWireCodec.writeMessage(socket.getOutputStream(), new Piece(1, 0, piece1));
                    readUntil(socket.getInputStream(), Have.class);
                    Thread.sleep(300);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fakePeer.start();

        FakeTrackerClient tracker = new FakeTrackerClient();
        tracker.peersToReturn = List.of(fakePeerAddress);
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.create(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null);
        try {
            session.start();
            awaitCompletedPieceCount(session, 1);

            session.stop();
            assertEquals(TorrentState.STOPPED, session.state());

            session.start();
            assertTrue(listener.seedingLatch.await(15, TimeUnit.SECONDS));
            assertEquals(TorrentState.SEEDING, session.state());
        } finally {
            session.stop();
        }
        fakePeer.join(2000);

        byte[] expected = new byte[piece0.length + piece1.length];
        System.arraycopy(piece0, 0, expected, 0, piece0.length);
        System.arraycopy(piece1, 0, expected, piece0.length, piece1.length);
        assertArrayEquals(expected, Files.readAllBytes(tempDir.resolve("file.bin")));
    }

    private static void awaitCompletedPieceCount(TorrentSession session, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (session.completedPieceCount() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, session.completedPieceCount());
    }

    @Test
    void restoreAsyncReturnsImmediatelyInVerifyingThenAutoStartsIntoSeeding(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        Files.write(tempDir.resolve("file.bin"), content);

        FakeTrackerClient tracker = new FakeTrackerClient();
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.restoreAsync(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null, true);
        // Must be visible right away, before verification (a real re-hash) has any chance to finish -
        // that's the whole point of restoreAsync() over a blocking restore().
        assertEquals(TorrentState.VERIFYING, session.state());

        try {
            assertTrue(listener.seedingLatch.await(5, TimeUnit.SECONDS));
            assertTrue(tracker.completedAnnounceLatch.await(5, TimeUnit.SECONDS));
            assertEquals(TorrentState.SEEDING, session.state());
            assertEquals(1, session.completedPieceCount());
            assertEquals(content.length, session.bytesDownloaded());
            // wasCompleteOnRestore() is what stops TorrentEventListener (grimtorrenter-app)
            // from recording a fresh COMPLETED library event every time this same
            // already-complete torrent is restored again on a later restart - see
            // design_docs/0055's own real duplicate-event bug this field fixes.
            assertTrue(session.wasCompleteOnRestore());
        } finally {
            session.stop();
        }
    }

    @Test
    void restoreAsyncWithoutAutoStartSettlesToStoppedAndStaysThere(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        Files.write(tempDir.resolve("file.bin"), content);

        FakeTrackerClient tracker = new FakeTrackerClient();
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.restoreAsync(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null, false);
        assertEquals(TorrentState.VERIFYING, session.state());

        awaitState(session, TorrentState.STOPPED);
        assertEquals(1, session.completedPieceCount());
        assertTrue(tracker.requests.isEmpty());
        // wasCompleteOnRestore() must be set even when this restore doesn't auto-start -
        // a later manual resume of this same session object still needs it set correctly.
        assertTrue(session.wasCompleteOnRestore());
    }

    @Test
    void restoreAsyncLeavesIncompleteDataAsNeededAndDownloadsNormally(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        // No file written to tempDir at all - simulates a torrent restored with nothing downloaded yet.

        FakeTrackerClient tracker = new FakeTrackerClient();
        RecordingListener listener = new RecordingListener();

        TorrentSession session = TorrentSession.restoreAsync(
                metadata, tracker, tempDir, fakeRemotePeerId(), 6881, listener, null, true);

        awaitState(session, TorrentState.DOWNLOADING);
        assertEquals(0, session.completedPieceCount());
        assertFalse(session.wasCompleteOnRestore(), "genuinely incomplete data must not be flagged as already complete");
        session.stop();
    }

    /** Proves restoreAsync()'s background verification actually honors a shared
     * pieceVerificationLimiter, not just that the parameter compiles - see design_docs/0048.
     * A zero-permit Semaphore must block verification indefinitely; releasing one permit
     * must let it proceed to completion. */
    @Test
    void restoreVerificationWaitsForAPieceVerificationPermit(@TempDir Path tempDir) throws Exception {
        byte[] content = fill(20, 1);
        TorrentMetadata metadata = singlePieceMetadata(content);
        Files.write(tempDir.resolve("file.bin"), content);

        FakeTrackerClient tracker = new FakeTrackerClient();
        RecordingListener listener = new RecordingListener();
        Semaphore verificationLimiter = new Semaphore(0);

        TorrentSession session = TorrentSession.restoreAsync(metadata, tracker, tempDir, fakeRemotePeerId(), 6881,
                listener, null, RateLimiters.unlimited(), FileHandlePool.unbounded(), verificationLimiter, true);
        assertEquals(TorrentState.VERIFYING, session.state());

        // No permit available - verification must not be able to proceed past its first
        // (and only, for a single-piece torrent) acquire().
        Thread.sleep(200);
        assertEquals(TorrentState.VERIFYING, session.state(), "should still be blocked waiting for a verification permit");

        try {
            verificationLimiter.release();
            assertTrue(listener.seedingLatch.await(5, TimeUnit.SECONDS));
            assertEquals(TorrentState.SEEDING, session.state());
        } finally {
            session.stop();
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
