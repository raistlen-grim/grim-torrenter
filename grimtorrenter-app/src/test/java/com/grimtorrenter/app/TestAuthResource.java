package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Overwrites target/test-config/auth.json with "no password set" before the Quarkus test
 * application boots - same "start() always runs before app boot, so always write a known
 * value rather than relying on the file not existing yet" rationale as TestSettingsResource.
 * Without this, a second `mvn test` run without an intervening `mvn clean` would find
 * AuthResourceTest's own previous run's password still on disk under target/ (only
 * settings.json gets this same treatment today via TestSettingsResource - auth.json didn't
 * exist before this feature). See design_docs/0061.
 */
public class TestAuthResource implements QuarkusTestResourceLifecycleManager {

    private static final Path TEST_CONFIG_DIRECTORY = Path.of("target", "test-config");

    @Override
    public Map<String, String> start() {
        try {
            Files.createDirectories(TEST_CONFIG_DIRECTORY);
            new ObjectMapper().writeValue(TEST_CONFIG_DIRECTORY.resolve("auth.json").toFile(), new StoredAuth(null));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not seed test auth store", e);
        }
        return Collections.emptyMap();
    }

    @Override
    public void stop() {
    }
}
