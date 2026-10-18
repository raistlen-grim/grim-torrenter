package com.grimtorrenter.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a file atomically: the content goes to a temp file in the same directory, which is
 * then renamed over the target. A crash or power cut mid-write leaves the old file or the new
 * one, never a torn file that the next read has to reject. Every file the engine rewrites in
 * place goes through here. See design_docs/0092 and design_docs/0099.
 */
public final class AtomicFiles {

    /** Fills the temp file; it's renamed over the target only if this returns normally. */
    @FunctionalInterface
    public interface Content {
        void writeTo(Path temp) throws IOException;
    }

    private AtomicFiles() {
    }

    /** Creates the parent directory if needed. The temp file is deleted on every exit path;
     * only a process killed between creating it and the rename leaves one behind. */
    public static void write(Path target, Content content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            content.writeTo(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void write(Path target, byte[] content) throws IOException {
        write(target, temp -> Files.write(temp, content));
    }

    /** UTF-8. */
    public static void write(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
