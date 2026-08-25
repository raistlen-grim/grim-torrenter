package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.settings.Settings;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Seeds target/test-config/settings.json with dhtEnabled=false/acceptIncomingConnections=false
 * before the Quarkus test application boots - same "start() is the one hook guaranteed to
 * run before app boot" rationale as CleanDownloadsResource. Without this,
 * JsonSettingsStore would fall back to Settings.defaults() (true/true), which binds a real
 * UDP socket and starts a real internet bootstrap (DHT) plus a real listening TCP socket
 * (PeerServer) - exactly the non-hermetic side effects application.properties' own
 * dht-enabled/accept-incoming-connections overrides used to prevent before both moved into
 * SettingsStore. See design_docs/0041.
 */
public class TestSettingsResource implements QuarkusTestResourceLifecycleManager {

    private static final Path TEST_CONFIG_DIRECTORY = Path.of("target", "test-config");

    @Override
    public Map<String, String> start() {
        try {
            Files.createDirectories(TEST_CONFIG_DIRECTORY);
            new ObjectMapper().writeValue(
                    TEST_CONFIG_DIRECTORY.resolve("settings.json").toFile(), new Settings(false, false, 0, 0));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not seed test settings", e);
        }
        return Collections.emptyMap();
    }

    @Override
    public void stop() {
    }
}
