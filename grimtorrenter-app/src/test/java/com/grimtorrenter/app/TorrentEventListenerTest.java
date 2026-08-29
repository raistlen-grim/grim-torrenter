package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.InMemoryEventStore;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import com.grimtorrenter.engine.tracker.NoOpTrackerClient;
import com.grimtorrenter.engine.tracker.PeerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit, not @QuarkusTest - TorrentEventListener's own logic doesn't need a running
 * container, only its two @Inject fields set by hand, same rationale as JsonLinesEventStoreTest.
 *
 * <p>Regression coverage for a real bug that shipped with design_docs/0055's first cut:
 * restoring an already-complete torrent replays its DOWNLOADING -> SEEDING transition (every
 * start() re-checks completion via enterDownloading() -> checkForCompletion()), and the naive
 * oldState/newState check alone can't tell that apart from a genuine first completion - so the
 * exact same long-since-finished torrent recorded a fresh COMPLETED library event on every
 * server restart. Fixed via TorrentSession.wasCompleteOnRestore() - see its own Javadoc.
 */
class TorrentEventListenerTest {

    private static final class NoOpListener implements TorrentSessionListener {
        @Override
        public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
        }

        @Override
        public void onPieceCompleted(TorrentSession session, int pieceIndex) {
        }
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TorrentMetadata singlePieceMetadata(byte[] content) {
        return new SingleFileTorrent("file.bin", content.length, content.length,
                new PieceHashes(sha1(content)), InfoHash.of(sha1(content)), null, List.of());
    }

    private static TorrentEventListener newListener(InMemoryEventStore eventStore) {
        TorrentEventListener listener = new TorrentEventListener();
        // JavaTimeModule registered by hand, same as JsonLinesEventStoreTest's own
        // objectMapper - the real CDI-managed ObjectMapper gets it via quarkus-rest-jackson's
        // auto-registration, which this hand-built one doesn't get for free. Needed since
        // TorrentView.from(session) (broadcast() below) serializes an Instant addedAt
        // (design_docs/0057); a bare ObjectMapper() throws on that with no module for it.
        listener.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener.eventStore = eventStore;
        return listener;
    }

    /** A freshly create()d session (never restored, never previously completed) is the one
     * case that must still fire - completedAtEpochMillis() is 0 and wasCompleteOnRestore() is
     * false, so this doesn't even need to actually reach SEEDING for real; the listener only
     * ever reads those two flags plus the oldState/newState arguments passed to it. */
    @Test
    void aGenuinelyFreshSessionRecordsACompletedEvent(@TempDir Path tempDir) throws IOException {
        byte[] content = new byte[]{1, 2, 3};
        TorrentSession session = TorrentSession.create(singlePieceMetadata(content), new NoOpTrackerClient(),
                tempDir, PeerId.generate(), 6881, new NoOpListener(), null);
        InMemoryEventStore eventStore = new InMemoryEventStore();

        newListener(eventStore).onStateChanged(session, TorrentState.DOWNLOADING, TorrentState.SEEDING);

        assertEquals(1, eventStore.all().stream().filter(e -> e.type() == EventType.COMPLETED).count());
    }

    @Test
    void aTorrentAlreadyCompleteOnRestoreDoesNotRecordASecondCompletedEvent(@TempDir Path tempDir) throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        Files.write(tempDir.resolve("file.bin"), content);
        TorrentSession session = TorrentSession.restoreAsync(singlePieceMetadata(content), new NoOpTrackerClient(),
                tempDir, PeerId.generate(), 6881, new NoOpListener(), null, false);
        long deadline = System.currentTimeMillis() + 5000;
        while (session.state() != TorrentState.STOPPED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(session.wasCompleteOnRestore(), "background verification should have found the data already complete");
        InMemoryEventStore eventStore = new InMemoryEventStore();

        // The real bug: TorrentSession fires this exact callback (DOWNLOADING -> SEEDING) every
        // time an already-complete torrent is (re)started, restart after restart.
        newListener(eventStore).onStateChanged(session, TorrentState.DOWNLOADING, TorrentState.SEEDING);

        assertEquals(0, eventStore.all().stream().filter(e -> e.type() == EventType.COMPLETED).count());
    }
}
