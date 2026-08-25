package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.storage.FileHandlePool;
import com.grimtorrenter.engine.tracker.NoOpTrackerClient;
import com.grimtorrenter.engine.tracker.PeerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real, concurrent, many-torrent soak test - not a unit test of any one class, but an
 * integration-level proof that FileHandlePool (design_docs/0047) and the piece-verification
 * Semaphore (design_docs/0048) hold up under the actual scenario that motivated them: many
 * torrents restoring at once, each on its own virtual thread, all sharing one engine-wide
 * pool and one engine-wide verification limiter - exactly what a real process restart with
 * many torrents on disk looks like. FileHandlePoolTest and TorrentSessionTest's own
 * throttling test already prove each mechanism's logic in isolation; this proves the two stay
 * correct wired together under real, deliberately adversarial concurrency - more torrents
 * (TORRENT_COUNT) than open-file slots (MAX_OPEN_FILES), and fewer verification permits
 * (MAX_CONCURRENT_VERIFICATIONS) than torrents, so both mechanisms are forced to actually do
 * their job rather than never being under pressure in the first place.
 */
class ManyTorrentsRestoreLoadTest {

    private static final int TORRENT_COUNT = 40;
    private static final int PIECE_LENGTH = 4096;
    private static final int PIECES_PER_TORRENT = 4;
    private static final int MAX_OPEN_FILES = 5;
    private static final int MAX_CONCURRENT_VERIFICATIONS = 4;

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

    private static TorrentMetadata multiPieceMetadata(byte[] content, int pieceLength, int seed) {
        int pieceCount = (content.length + pieceLength - 1) / pieceLength;
        byte[] hashes = new byte[pieceCount * 20];
        for (int i = 0; i < pieceCount; i++) {
            int start = i * pieceLength;
            int end = Math.min(start + pieceLength, content.length);
            System.arraycopy(sha1(Arrays.copyOfRange(content, start, end)), 0, hashes, i * 20, 20);
        }
        return new SingleFileTorrent("file.bin", content.length, pieceLength,
                new PieceHashes(hashes), InfoHash.of(fill(20, seed)), null, List.of());
    }

    /** Instruments the exact Semaphore every session's verifyThenSettle()/verifyPiece()
     * acquires/releases (see TorrentSession's own pieceVerificationLimiter field) to track
     * real peak concurrent usage precisely - not sampled/polled like FileHandlePool's
     * openCount() below, so this assertion is exact, not best-effort. */
    private static final class PeakTrackingSemaphore extends Semaphore {
        private final AtomicInteger current = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        PeakTrackingSemaphore(int permits) {
            super(permits);
        }

        @Override
        public void acquireUninterruptibly() {
            super.acquireUninterruptibly();
            peak.updateAndGet(p -> Math.max(p, current.incrementAndGet()));
        }

        @Override
        public void release() {
            current.decrementAndGet();
            super.release();
        }

        int peak() {
            return peak.get();
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

    /** Setup (creating directories, writing each torrent's content, building metadata) is
     * real, sequential file I/O - fine to do upfront on the main thread, since none of it
     * contends for pool/verificationLimiter. What must NOT happen sequentially is the actual
     * restoreAsync() call: it does its own synchronous file-I/O setup (TorrentStorage.create())
     * on the calling thread before ever spawning the verification virtual thread that's
     * actually under test - calling it 40 times in a tight loop on one thread staggers when
     * each torrent's verification actually starts by exactly that per-call setup cost. With
     * only 4 tiny pieces per torrent, verification itself is fast enough that earlier
     * torrents can fully finish and release their permits before later ones even begin,
     * so the semaphore's peak concurrent usage never rises above 1 - not because nothing was
     * bounding it, but because nothing ever asked for more than one permit at a time in the
     * first place. A cold JVM/filesystem cache (e.g. right after `mvn clean`) makes this
     * worse, not better: slower per-call setup work widens the stagger. Fixed by having every
     * restoreAsync() call itself happen from its own thread, released together via a shared
     * start gate, so all 40 genuinely race for the shared pool/semaphore at once - the actual
     * scenario this test exists to prove ("many torrents restoring at once"), not an
     * accidentally-serialized approximation of it. */
    @Test
    void manyTorrentsRestoreConcurrentlyWithoutExceedingSharedResourceBounds(@TempDir Path rootDir) throws Exception {
        FileHandlePool pool = new FileHandlePool(MAX_OPEN_FILES);
        PeakTrackingSemaphore verificationLimiter = new PeakTrackingSemaphore(MAX_CONCURRENT_VERIFICATIONS);
        AtomicInteger peakOpenFiles = new AtomicInteger();

        List<TorrentMetadata> metadataList = new ArrayList<>();
        List<Path> torrentDirs = new ArrayList<>();
        for (int i = 0; i < TORRENT_COUNT; i++) {
            Path torrentDir = Files.createDirectories(rootDir.resolve("torrent" + i));
            byte[] content = fill(PIECE_LENGTH * PIECES_PER_TORRENT, i);
            Files.write(torrentDir.resolve("file.bin"), content);
            torrentDirs.add(torrentDir);
            metadataList.add(multiPieceMetadata(content, PIECE_LENGTH, i));
        }

        CountDownLatch startGate = new CountDownLatch(1);
        // Set by index, not appended - each launcher thread writes to its own distinct slot,
        // and every launcher.join() below happens-before this array is read again, so no
        // further synchronization is needed. Kept index-aligned with metadataList/torrentDirs
        // (rather than a concurrently-appended list) specifically so the per-torrent
        // assertions further down can still say "torrent i" meaningfully.
        TorrentSession[] sessions = new TorrentSession[TORRENT_COUNT];
        List<Thread> launchers = new ArrayList<>();
        for (int i = 0; i < TORRENT_COUNT; i++) {
            int index = i;
            Thread launcher = new Thread(() -> {
                try {
                    startGate.await();
                    // autoStart=false - this test is about the restore/verify burst itself,
                    // not steady-state downloading/seeding; every session settles to STOPPED
                    // once verification finishes, no tracker announce or peer connections
                    // involved.
                    sessions[index] = TorrentSession.restoreAsync(metadataList.get(index),
                            new NoOpTrackerClient(), torrentDirs.get(index), PeerId.of(fill(20, 200 + index)), 6881,
                            new NoOpListener(), null, RateLimiters.unlimited(), pool, verificationLimiter, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "restore-launcher-" + i);
            launchers.add(launcher);
            launcher.start();
        }
        startGate.countDown();
        for (Thread launcher : launchers) {
            launcher.join();
        }

        try {
            // Polls every session's state (not per-session latches) so sampling spans the
            // whole burst until every last session settles, not just until the first one
            // does. Best-effort, not exact - unlike verificationLimiter's peak below,
            // FileHandlePool isn't instrumentable without modifying production code - still
            // meaningful corroboration alongside FileHandlePoolTest's own precise,
            // deterministic coverage of the eviction algorithm itself.
            long deadline = System.currentTimeMillis() + 10_000;
            boolean allSettled;
            do {
                peakOpenFiles.updateAndGet(p -> Math.max(p, pool.openCount()));
                allSettled = Arrays.stream(sessions).allMatch(s -> s.state() == TorrentState.STOPPED);
                if (!allSettled) {
                    Thread.sleep(1);
                }
            } while (!allSettled && System.currentTimeMillis() < deadline);

            assertTrue(allSettled, "every session should have finished restoring within the deadline");
            for (int i = 0; i < TORRENT_COUNT; i++) {
                assertEquals(PIECES_PER_TORRENT, sessions[i].completedPieceCount(),
                        "torrent " + i + " should have verified every piece correctly despite pool churn");
            }
            assertTrue(peakOpenFiles.get() <= MAX_OPEN_FILES,
                    "observed peak open file count " + peakOpenFiles.get() + " exceeded the configured budget "
                            + MAX_OPEN_FILES);
            assertTrue(verificationLimiter.peak() <= MAX_CONCURRENT_VERIFICATIONS,
                    "observed peak concurrent verification count " + verificationLimiter.peak()
                            + " exceeded the configured limit " + MAX_CONCURRENT_VERIFICATIONS);
            assertTrue(verificationLimiter.peak() > 1,
                    "expected real concurrency to have happened at all with " + TORRENT_COUNT
                            + " torrents restoring at once - this assertion failing would mean the test wasn't "
                            + "actually exercising concurrent verification");
        } finally {
            Arrays.stream(sessions).forEach(TorrentSession::close);
        }
    }
}
