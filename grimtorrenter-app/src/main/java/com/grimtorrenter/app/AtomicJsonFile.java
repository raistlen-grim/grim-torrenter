package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Writes a JSON file so that a crash or power cut mid-write leaves either the old file or the new
 * one, never a truncated one: serialise to a temp file in the same directory, then rename over the
 * target atomically. settings.json and auth.json are read at startup and a torn one fails it, so
 * the container would restart-loop until someone repaired the file by hand. See design_docs/0093.
 */
final class AtomicJsonFile {

    private AtomicJsonFile() {
    }

    /** ownerOnly restricts the file to rw------- (where the filesystem supports POSIX
     * permissions) - for auth.json's password hash, matching the proxy password file. */
    static void write(ObjectMapper objectMapper, Path target, Object value, boolean ownerOnly) {
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            if (ownerOnly) {
                restrictToOwner(temp);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // a stray temp file is harmless - it is never read
                }
            }
        }
    }

    /** Best-effort; also used on an existing auth.json written before this was owner-only. */
    static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX filesystem - nothing finer-grained to set
        }
    }
}
