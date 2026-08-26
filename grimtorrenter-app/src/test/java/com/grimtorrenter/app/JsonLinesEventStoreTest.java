package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit, not @QuarkusTest - same rationale as JsonSettingsStoreTest: this class's own
 * logic (append-to-today's-file, read-all-day-files, prune-by-retention) doesn't need a running
 * container, only a directly-constructed instance with its @ConfigProperty/@Inject fields set
 * by hand. See design_docs/0055.
 */
class JsonLinesEventStoreTest {

    private static JsonLinesEventStore createStore(Path configDirectory, int retentionDays) {
        JsonLinesEventStore store = new JsonLinesEventStore();
        store.configDirectory = configDirectory.toString();
        store.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        store.settingsStore = new InMemorySettingsStore(settingsWithRetention(retentionDays));
        store.init();
        return store;
    }

    private static Settings settingsWithRetention(int retentionDays) {
        return new Settings(true, true, 0, 0, false, "23:00", "07:00", 0, 0,
                com.grimtorrenter.engine.mse.EncryptionMode.PREFERRED, 0, false, 2.0, false, 1440, retentionDays);
    }

    private static LibraryEvent eventAt(Instant timestamp) {
        return new LibraryEvent(timestamp, EventType.ADDED, "abc123", "some-torrent", null);
    }

    @Test
    void recordThenAllRoundTrips(@TempDir Path tempDir) {
        JsonLinesEventStore store = createStore(tempDir, 30);
        LibraryEvent event = eventAt(Instant.now());

        store.record(event);

        assertEquals(List.of(event), store.all());
    }

    @Test
    void recordWritesToTodaysFile(@TempDir Path tempDir) throws IOException {
        JsonLinesEventStore store = createStore(tempDir, 30);

        store.record(eventAt(Instant.now()));

        String today = java.time.LocalDate.now().toString();
        assertTrue(Files.exists(tempDir.resolve("events").resolve("events-" + today + ".jsonl")));
    }

    @Test
    void forTorrentFiltersByInfoHash(@TempDir Path tempDir) {
        JsonLinesEventStore store = createStore(tempDir, 30);
        LibraryEvent match = eventAt(Instant.now());
        LibraryEvent other = new LibraryEvent(Instant.now(), EventType.ADDED, "other-hash", "other-torrent", null);
        store.record(match);
        store.record(other);

        assertEquals(List.of(match), store.forTorrent("abc123"));
    }

    @Test
    void pruneDeletesADayFileOlderThanTheRetentionWindowAndKeepsOneWithinIt(@TempDir Path tempDir) throws IOException {
        Path eventsDir = tempDir.resolve("events");
        Files.createDirectories(eventsDir);
        String staleDate = java.time.LocalDate.now().minusDays(40).toString();
        String freshDate = java.time.LocalDate.now().minusDays(5).toString();
        Path staleFile = eventsDir.resolve("events-" + staleDate + ".jsonl");
        Path freshFile = eventsDir.resolve("events-" + freshDate + ".jsonl");
        Files.writeString(staleFile, "");
        Files.writeString(freshFile, "");

        JsonLinesEventStore store = createStore(tempDir, 30);
        store.prune();

        assertFalse(Files.exists(staleFile));
        assertTrue(Files.exists(freshFile));
    }

    @Test
    void missingEventsDirectoryIsCreatedOnInit(@TempDir Path tempDir) {
        Path nested = tempDir.resolve("nested").resolve("config");

        createStore(nested, 30);

        assertTrue(Files.isDirectory(nested.resolve("events")));
    }
}
