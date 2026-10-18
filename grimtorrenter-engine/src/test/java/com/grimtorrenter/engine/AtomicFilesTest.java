package com.grimtorrenter.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** design_docs/0099. */
class AtomicFilesTest {

    @Test
    void writesAndReplacesCreatingTheDirectory(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested").resolve("file.txt");

        AtomicFiles.write(target, "first");
        AtomicFiles.write(target, "second");

        assertEquals("second", Files.readString(target));
        try (var entries = Files.list(target.getParent())) {
            assertEquals(1, entries.count(), "no temp file left behind");
        }
    }

    @Test
    void aFailingWriteLeavesTheOldFileAndNoTempFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("file.txt");
        AtomicFiles.write(target, "kept");

        assertThrows(IOException.class, () -> AtomicFiles.write(target, temp -> {
            Files.writeString(temp, "half written");
            throw new IOException("disk full");
        }));

        assertEquals("kept", Files.readString(target));
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count(), "no temp file left behind");
        }
    }
}
