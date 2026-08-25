package com.grimtorrenter.app;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

/**
 * Deletes target/test-downloads before the Quarkus test application boots. A plain
 * {@code @BeforeAll} runs too late for this - JUnit5 invokes QuarkusTestExtension's own
 * beforeAll (which starts the app) before a test class's own {@code @BeforeAll} methods,
 * and by then TorrentEngineLifecycle.onStart has already called restore(). A
 * QuarkusTestResourceLifecycleManager's start() is the one hook guaranteed to run first.
 *
 * <p>Without this, a leftover directory from an earlier "mvn test" run (target/ is only
 * wiped by "mvn clean") still carries its marker files, so restore() re-registers it as a
 * session before any test body runs - producing a non-empty torrent list and a spurious
 * "already existed" on what a test expected to be a fresh upload.
 */
public class CleanDownloadsResource implements QuarkusTestResourceLifecycleManager {

    private static final Path TEST_DOWNLOADS = Path.of("target", "test-downloads");

    @Override
    public Map<String, String> start() {
        deleteRecursively(TEST_DOWNLOADS);
        return Collections.emptyMap();
    }

    @Override
    public void stop() {
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not clean " + directory, e);
        }
    }
}
