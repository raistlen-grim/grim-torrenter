package com.grimtorrenter.engine.engine;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.InMemoryEventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.storage.FileHandlePool;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** See design_docs/0056. scanWatchFolder() is called directly, twice per file (a stability
 * check needs one full "unchanged since last tick" interval before a file is ever read), rather
 * than waiting on the real WATCH_FOLDER_SCAN_INTERVAL_SECONDS-second scheduler tick - same
 * spirit as TorrentEngineTest's own checkSeedingLimits() tests. Announce URLs are deliberately
 * unreachable (same trick TorrentResourceTest/EventsResourceTest use) - these tests exercise
 * the watch-folder mechanism itself, not tracker communication; a resulting ERROR state
 * transition (and its own, unrelated ERROR library event) is expected and not asserted against. */
class WatchFolderTest {

    /** Every test's engine is tracked here and shut down in tearDown() - each construction
     * starts a daemon-threaded maintenanceScheduler running two periodic tasks
     * (checkSeedingLimits()/scanWatchFolder()); left unshut down, it keeps ticking for the
     * rest of this JVM's test run instead of just this one test's duration. Unlike
     * TorrentEngineTest's own many intentionally-unshut-down engines (an accepted tradeoff
     * there, see TorrentEngine's own maintenanceScheduler field Javadoc), this file's tests
     * all construct their engine through the one newEngine() helper, so tracking and shutting
     * down here costs nothing extra per test. */
    private TorrentEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.shutdown();
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

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] torrentBytes(String name, byte[] content) {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of(name),
                BString.of("piece length"), new BInteger(content.length),
                BString.of("pieces"), BString.of(sha1(content)),
                BString.of("length"), new BInteger(content.length)));
        BDictionary top = new BDictionary(Map.of(
                BString.of("announce"), BString.of("http://127.0.0.1:1/announce"),
                BString.of("info"), info));
        return BencodeEncoder.encode(top);
    }

    private static Settings settingsWithWatchFolder(boolean enabled, int retentionDays) {
        return new Settings(true, true, 0, 0, false, "23:00", "07:00", 0, 0,
                EncryptionMode.PREFERRED, 0, false, 2.0, false, 1440, 30, enabled, retentionDays);
    }

    private static TorrentEngine newEngine(Path downloadDir, Path watchDir, Settings settings, InMemoryEventStore eventStore) {
        return new TorrentEngine(downloadDir, 6881, new NoOpListener(), false, false,
                new InMemorySettingsStore(settings), FileHandlePool.unbounded(), Integer.MAX_VALUE, eventStore, watchDir);
    }

    @Test
    void scanWatchFolderIsANoOpWhenDisabled(@TempDir Path root) {
        Path watchDir = root.resolve("watch");
        engine = newEngine(root.resolve("downloads"), watchDir,
                settingsWithWatchFolder(false, 7), new InMemoryEventStore());

        engine.scanWatchFolder();

        assertFalse(Files.exists(watchDir), "the watch directory should never even be created while disabled");
    }

    @Test
    void aDroppedValidTorrentFileIsAddedAndMovedToAdded(@TempDir Path root) throws IOException {
        Path watchDir = Files.createDirectories(root.resolve("watch"));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        engine = newEngine(root.resolve("downloads"), watchDir,
                settingsWithWatchFolder(true, 7), eventStore);
        Files.write(watchDir.resolve("drop-me.torrent"), torrentBytes("watch-add.bin", new byte[]{1, 2, 3}));

        engine.scanWatchFolder(); // sees it for the first time - not yet stable, not processed
        engine.scanWatchFolder(); // unchanged since the last tick - now processed

        assertFalse(Files.exists(watchDir.resolve("drop-me.torrent")));
        assertTrue(Files.exists(watchDir.resolve("added").resolve("drop-me.torrent")));
        assertEquals(1, eventStore.all().stream()
                .filter(e -> e.type() == EventType.ADDED && "Added via watch folder".equals(e.message())
                        && "watch-add.bin".equals(e.torrentName()))
                .count());
    }

    @Test
    void aMalformedFileIsMovedToFailedAndRecordsAnErrorEvent(@TempDir Path root) throws IOException {
        Path watchDir = Files.createDirectories(root.resolve("watch"));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        engine = newEngine(root.resolve("downloads"), watchDir,
                settingsWithWatchFolder(true, 7), eventStore);
        Files.write(watchDir.resolve("garbage.torrent"), "not a real torrent file".getBytes());

        engine.scanWatchFolder();
        engine.scanWatchFolder();

        assertFalse(Files.exists(watchDir.resolve("garbage.torrent")));
        assertTrue(Files.exists(watchDir.resolve("failed").resolve("garbage.torrent")));
        List<LibraryEvent> errors = eventStore.all().stream().filter(e -> e.type() == EventType.ERROR).toList();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).message().contains("garbage.torrent"));
    }

    @Test
    void anIdempotentReAddStillMovesToAddedWithoutASecondAddedEvent(@TempDir Path root) throws IOException {
        Path watchDir = Files.createDirectories(root.resolve("watch"));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        engine = newEngine(root.resolve("downloads"), watchDir,
                settingsWithWatchFolder(true, 7), eventStore);
        byte[] torrent = torrentBytes("watch-dup.bin", new byte[]{9, 9, 9});
        Files.write(watchDir.resolve("first.torrent"), torrent);
        engine.scanWatchFolder();
        engine.scanWatchFolder();

        Files.write(watchDir.resolve("second.torrent"), torrent);
        engine.scanWatchFolder();
        engine.scanWatchFolder();

        assertTrue(Files.exists(watchDir.resolve("added").resolve("first.torrent")));
        assertTrue(Files.exists(watchDir.resolve("added").resolve("second.torrent")),
                "the re-add should still be filed as a success even though no new session was created");
        assertEquals(1, eventStore.all().stream()
                .filter(e -> e.type() == EventType.ADDED && "watch-dup.bin".equals(e.torrentName()))
                .count());
    }

    @Test
    void aSameNamedFileDroppedTwiceGetsACollisionSuffixOnItsSecondMove(@TempDir Path root) throws IOException {
        Path watchDir = Files.createDirectories(root.resolve("watch"));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        engine = newEngine(root.resolve("downloads"), watchDir,
                settingsWithWatchFolder(true, 7), eventStore);

        Files.write(watchDir.resolve("same-name.torrent"), torrentBytes("watch-first.bin", new byte[]{1}));
        engine.scanWatchFolder();
        engine.scanWatchFolder();

        Files.write(watchDir.resolve("same-name.torrent"), torrentBytes("watch-second.bin", new byte[]{2}));
        engine.scanWatchFolder();
        engine.scanWatchFolder();

        assertTrue(Files.exists(watchDir.resolve("added").resolve("same-name.torrent")));
        assertTrue(Files.exists(watchDir.resolve("added").resolve("same-name-2.torrent")));
    }

    @Test
    void aFileThatChangesBetweenTicksIsNotYetProcessed(@TempDir Path root) throws IOException {
        Path watchDir = Files.createDirectories(root.resolve("watch"));
        InMemoryEventStore eventStore = new InMemoryEventStore();
        engine = newEngine(root.resolve("downloads"), watchDir,
                settingsWithWatchFolder(true, 7), eventStore);
        Path file = watchDir.resolve("still-writing.torrent");
        Files.write(file, torrentBytes("watch-partial.bin", new byte[]{1, 2, 3}));

        engine.scanWatchFolder(); // first sighting
        // Simulate a still-in-progress write: content (and therefore size) changes here.
        Files.write(file, torrentBytes("watch-partial.bin", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        engine.scanWatchFolder(); // changed since the last tick - still not stable

        assertTrue(Files.exists(file), "a file that's still changing must not be read or moved yet");
        assertFalse(Files.exists(watchDir.resolve("added").resolve("still-writing.torrent")));
        assertFalse(Files.exists(watchDir.resolve("failed").resolve("still-writing.torrent")));
    }

    @Test
    void retentionPruneDeletesAnOldResolvedFileAndKeepsARecentOne(@TempDir Path root) throws IOException {
        Path watchDir = Files.createDirectories(root.resolve("watch"));
        Path addedDir = Files.createDirectories(watchDir.resolve("added"));
        Path oldFile = addedDir.resolve("old.torrent");
        Path recentFile = addedDir.resolve("recent.torrent");
        Files.write(oldFile, new byte[]{1});
        Files.write(recentFile, new byte[]{2});
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(40, ChronoUnit.DAYS)));

        engine = newEngine(root.resolve("downloads"), watchDir,
                settingsWithWatchFolder(true, 7), new InMemoryEventStore());
        engine.scanWatchFolder();

        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(recentFile));
    }
}
