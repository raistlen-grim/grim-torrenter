package com.grimtorrenter.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The three deploy-time directories, each independently mountable: downloads, config
 * (settings.json, auth.json, events/ and the engine's per-torrent markers) and the watch folder.
 * Every class that needs one injects this, so each property and its default is declared exactly
 * once. See design_docs/0100.
 */
@ApplicationScoped
public class AppDirectories {

    @ConfigProperty(name = "grimtorrenter.download-directory", defaultValue = "downloads")
    String downloadDirectory;

    @ConfigProperty(name = "grimtorrenter.config-directory", defaultValue = "config")
    String configDirectory;

    @ConfigProperty(name = "grimtorrenter.watch-directory", defaultValue = "watch")
    String watchDirectory;

    /** For plain JUnit tests that build a bean by hand, without CDI. */
    static AppDirectories of(Path downloads, Path config, Path watch) {
        AppDirectories directories = new AppDirectories();
        directories.downloadDirectory = downloads.toString();
        directories.configDirectory = config.toString();
        directories.watchDirectory = watch.toString();
        return directories;
    }

    public Path downloads() {
        return Path.of(downloadDirectory);
    }

    public Path config() {
        return Path.of(configDirectory);
    }

    public Path watch() {
        return Path.of(watchDirectory);
    }

    /** Usable space on the downloads volume. Creates the directory first: the engine only
     * creates it once something is downloaded, and a fresh install asks before that. */
    long downloadsUsableSpace() throws IOException {
        Path downloads = downloads();
        Files.createDirectories(downloads);
        return Files.getFileStore(downloads).getUsableSpace();
    }
}
