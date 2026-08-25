package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit, not @QuarkusTest - JsonSettingsStore's own logic (read-or-create-default,
 * write-then-swap on update) doesn't need a running container to exercise, only a
 * directly-constructed instance with its @ConfigProperty/@Inject fields set by hand (both
 * package-private, same as init(), specifically so this works). See design_docs/0041.
 */
class JsonSettingsStoreTest {

    private static JsonSettingsStore createStore(Path configDirectory) {
        JsonSettingsStore store = new JsonSettingsStore();
        store.configDirectory = configDirectory.toString();
        store.objectMapper = new ObjectMapper();
        store.init();
        return store;
    }

    @Test
    void createsDefaultsAndWritesThemWhenNoSettingsFileExistsYet(@TempDir Path tempDir) {
        JsonSettingsStore store = createStore(tempDir);

        assertEquals(Settings.defaults(), store.current());
        assertTrue(Files.exists(tempDir.resolve("settings.json")));
    }

    @Test
    void loadsAnExistingSettingsFileInsteadOfOverwritingIt(@TempDir Path tempDir) throws IOException {
        new ObjectMapper().writeValue(tempDir.resolve("settings.json").toFile(), new Settings(false, false, 0, 0));

        JsonSettingsStore store = createStore(tempDir);

        assertEquals(new Settings(false, false, 0, 0), store.current());
    }

    /** Reproduces a real settings.json persisted before encryptionMode existed (design_docs/0052)
     * - the field is simply absent from the JSON, which Jackson's record deserialization
     * otherwise leaves null. Settings' own compact constructor is what's actually under test
     * here; this confirms it's reached via the real load path, not just via a direct
     * `new Settings(...)` call. */
    @Test
    void loadsALegacySettingsFileMissingEncryptionModeWithThePreferredDefaultBackfilled(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("settings.json"), """
                {
                  "dhtEnabled": true,
                  "acceptIncomingConnections": true,
                  "uploadRateLimitBytesPerSec": 0,
                  "downloadRateLimitBytesPerSec": 0,
                  "rateLimitScheduleEnabled": false,
                  "rateLimitScheduleStart": "23:00",
                  "rateLimitScheduleEnd": "07:00",
                  "scheduledUploadRateLimitBytesPerSec": 0,
                  "scheduledDownloadRateLimitBytesPerSec": 0
                }
                """);

        JsonSettingsStore store = createStore(tempDir);

        assertEquals(EncryptionMode.PREFERRED, store.current().encryptionMode());
    }

    @Test
    void updatePersistsToDiskAndUpdatesCurrent(@TempDir Path tempDir) throws IOException {
        JsonSettingsStore store = createStore(tempDir);

        store.update(new Settings(false, true, 0, 0));

        assertEquals(new Settings(false, true, 0, 0), store.current());
        Settings onDisk = new ObjectMapper().readValue(tempDir.resolve("settings.json").toFile(), Settings.class);
        assertEquals(new Settings(false, true, 0, 0), onDisk);
    }

    @Test
    void createsTheConfigDirectoryIfItDoesNotExistYet(@TempDir Path tempDir) {
        Path nested = tempDir.resolve("nested").resolve("config");

        JsonSettingsStore store = createStore(nested);

        assertTrue(Files.isDirectory(nested));
        assertEquals(Settings.defaults(), store.current());
    }
}
