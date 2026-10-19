package com.grimtorrenter.engine.engine;

import com.grimtorrenter.engine.ratelimit.AppliedLimit;
import com.grimtorrenter.engine.stats.HistoryResolution;
import com.grimtorrenter.engine.stats.HistoryRing;
import com.grimtorrenter.engine.stats.TransferHistory;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine-wide transfer figures and the persisted hourly history. See design_docs/0102. */
class EngineTransferTest {

    private static final String HISTORY_FILE = ".grimtorrenter-transfer-history";

    private static final class NoOpListener implements TorrentSessionListener {
        @Override
        public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
        }

        @Override
        public void onPieceCompleted(TorrentSession session, int pieceIndex) {
        }
    }

    @Test
    void anIdleEngineReadsZeroWithNoLimits(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        engine.sampleTransfer();

        TorrentEngine.GlobalTransfer transfer = engine.globalTransfer();

        assertEquals(0, transfer.download().bytesPerSec());
        assertEquals(0, transfer.upload().bytesPerSec());
        assertEquals(AppliedLimit.NONE, transfer.downloadLimit());
        assertFalse(transfer.downloadCapped());
        engine.shutdown();
    }

    @Test
    void theHourlyHistorySurvivesARestart(@TempDir Path tempDir) {
        TorrentEngine first = new TorrentEngine(tempDir, 6881, new NoOpListener());
        first.sampleTransfer();
        first.shutdown();
        assertTrue(Files.exists(tempDir.resolve(HISTORY_FILE)));

        TorrentEngine second = new TorrentEngine(tempDir, 6881, new NoOpListener());
        TransferHistory hours = second.transferHistory(HistoryResolution.HOUR);

        assertEquals(168, hours.download().size());
        // The sampled hour came back (as 0 B/s - nothing moved), not as a gap.
        assertEquals(0L, hours.download().get(167));
        assertNull(hours.download().get(0));
        second.shutdown();
    }

    @Test
    void aCorruptHistoryFileStartsEmptyInsteadOfFailing(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve(HISTORY_FILE), "hour=not,numbers\n");

        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());

        TransferHistory hours = engine.transferHistory(HistoryResolution.HOUR);
        assertTrue(hours.download().stream().allMatch(value -> value == null));
        engine.shutdown();
    }

    @Test
    void historyLinesRoundTrip(@TempDir Path tempDir) throws Exception {
        List<HistoryRing.Interval> intervals = List.of(
                new HistoryRing.Interval(490_000, 1_000, 2_000, 3_600_000),
                new HistoryRing.Interval(490_001, 0, 5, 1_000));
        Path file = tempDir.resolve("history");
        Files.writeString(file, TorrentEngine.formatTransferHistory(intervals));

        assertEquals(intervals, TorrentEngine.parseTransferHistory(KeyValueFile.read(file)));
    }

    @Test
    void aMalformedHistoryLineIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TorrentEngine.parseTransferHistory(
                List.of(new KeyValueFile.Entry("hour", "1,2,3"))));
    }

    @Test
    void secondAndMinuteHistoryAreAvailableEngineWide(@TempDir Path tempDir) {
        TorrentEngine engine = new TorrentEngine(tempDir, 6881, new NoOpListener());
        engine.sampleTransfer();

        assertNotNull(engine.transferHistory(HistoryResolution.SECOND));
        assertEquals(120, engine.transferHistory(HistoryResolution.MINUTE).download().size());
        assertEquals(60, engine.transferHistory(HistoryResolution.SECOND).download().size());
        engine.shutdown();
    }
}
