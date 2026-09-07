package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit, not @QuarkusTest - same reasoning as JsonSettingsStoreTest: this store's own
 * logic doesn't need a running container, only a directly-constructed instance with its
 * @ConfigProperty/@Inject fields set by hand (all package-private, same as init(),
 * specifically so this works). See design_docs/0061.
 */
class JsonAuthStoreTest {

    private static JsonAuthStore createStore(Path configDirectory, String bootstrapPassword) {
        JsonAuthStore store = new JsonAuthStore();
        store.configDirectory = configDirectory.toString();
        store.bootstrapPassword = Optional.ofNullable(bootstrapPassword);
        store.objectMapper = new ObjectMapper();
        store.init();
        return store;
    }

    @Test
    void hasNoPasswordWhenNoAuthFileAndNoBootstrapPasswordExist(@TempDir Path tempDir) {
        JsonAuthStore store = createStore(tempDir, "");

        assertFalse(store.hasPassword());
        assertFalse(store.verify("anything"));
        assertTrue(Files.exists(tempDir.resolve("auth.json")));
    }

    @Test
    void seedsFromTheBootstrapPasswordWhenAuthFileDoesNotExistYet(@TempDir Path tempDir) {
        JsonAuthStore store = createStore(tempDir, "bootstrap-password");

        assertTrue(store.hasPassword());
        assertTrue(store.verify("bootstrap-password"));
        assertFalse(store.verify("something else"));
    }

    /** Grafana-style first-run-only bootstrap - a bootstrap password is ignored once auth.json
     * already exists, so a stale/leftover env var can't silently reset a password the user has
     * since changed through the app. */
    @Test
    void ignoresTheBootstrapPasswordOnceAuthFileAlreadyExists(@TempDir Path tempDir) throws IOException {
        new ObjectMapper().writeValue(
                tempDir.resolve("auth.json").toFile(), new StoredAuth(PasswordHasher.hash("already set")));

        JsonAuthStore store = createStore(tempDir, "bootstrap-password");

        assertTrue(store.verify("already set"));
        assertFalse(store.verify("bootstrap-password"));
    }

    @Test
    void setPasswordPersistsToDiskAndUpdatesCurrent(@TempDir Path tempDir) throws IOException {
        JsonAuthStore store = createStore(tempDir, "");

        store.setPassword("a new password");

        assertTrue(store.verify("a new password"));
        StoredAuth onDisk = new ObjectMapper().readValue(tempDir.resolve("auth.json").toFile(), StoredAuth.class);
        assertTrue(PasswordHasher.matches("a new password", onDisk.passwordHash()));
    }

    @Test
    void createsTheConfigDirectoryIfItDoesNotExistYet(@TempDir Path tempDir) {
        Path nested = tempDir.resolve("nested").resolve("config");

        JsonAuthStore store = createStore(nested, "");

        assertTrue(Files.isDirectory(nested));
        assertFalse(store.hasPassword());
    }
}
