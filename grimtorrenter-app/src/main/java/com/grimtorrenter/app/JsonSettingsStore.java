package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON-file-backed SettingsStore - @ApplicationScoped, so every injector shares the exact
 * same in-memory instance (CDI's own singleton guarantee is what makes "the store is the
 * only thing that holds a Settings instance" actually true, not just a convention).
 * settings.json lives in its own configurable directory (grimtorrenter.config-directory),
 * deliberately separate from the download directory, so a Docker deployment can bind-mount
 * config and downloads to different host paths - matching how self-hosted tools like this
 * are typically deployed. See design_docs/0041.
 */
@ApplicationScoped
public class JsonSettingsStore implements SettingsStore {

    private static final String SETTINGS_FILENAME = "settings.json";

    @ConfigProperty(name = "grimtorrenter.config-directory", defaultValue = "config")
    String configDirectory;

    @Inject
    ObjectMapper objectMapper;

    private Path settingsFile;
    private volatile Settings current;

    /** Package-private (not private) so a test can call it directly after setting
     * configDirectory/objectMapper itself, without going through CDI. */
    @PostConstruct
    void init() {
        Path directory = Path.of(configDirectory);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create config directory " + directory, e);
        }
        settingsFile = directory.resolve(SETTINGS_FILENAME);
        current = readOrCreateDefault();
    }

    @Override
    public Settings current() {
        return current;
    }

    /** Writes to disk before updating the in-memory value, not after - a crash between the
     * two can then never leave the in-memory view claiming a change that isn't actually
     * durable yet. synchronized so two concurrent updates can't interleave their writes.
     * See design_docs/0041. */
    @Override
    public synchronized void update(Settings settings) {
        writeToDisk(settings);
        current = settings;
    }

    private Settings readOrCreateDefault() {
        if (Files.exists(settingsFile)) {
            try {
                return objectMapper.readValue(settingsFile.toFile(), Settings.class);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + settingsFile, e);
            }
        }
        Settings defaults = Settings.defaults();
        writeToDisk(defaults);
        return defaults;
    }

    private void writeToDisk(Settings settings) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), settings);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + settingsFile, e);
        }
    }
}
