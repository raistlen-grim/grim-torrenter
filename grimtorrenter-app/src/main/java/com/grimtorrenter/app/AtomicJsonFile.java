package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The load and save code shared by JsonSettingsStore and JsonAuthStore (design_docs/0100).
 *
 * <p>Writes a JSON file so that a crash or power cut mid-write leaves either the old file or the new
 * one, never a truncated one: serialise to a temp file in the same directory, then rename over the
 * target atomically. settings.json and auth.json are read at startup and a torn one fails it, so
 * the container would restart-loop until someone repaired the file by hand. See design_docs/0093.
 */
final class AtomicJsonFile {

    private AtomicJsonFile() {
    }

    /** Reads {@code file}, or - when it doesn't exist yet - writes {@code seed}'s value there and
     * returns it. Creates the parent directory first. A file that exists but can't be read or
     * parsed throws: for settings.json and auth.json, refusing to start is safer than silently
     * starting on defaults (design_docs/0093). */
    static <T> T readOrSeed(ObjectMapper objectMapper, Path file, Class<T> type, Supplier<T> seed,
                            boolean ownerOnly) {
        Path directory = file.getParent();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create config directory " + directory, e);
        }
        if (Files.exists(file)) {
            try {
                return objectMapper.readValue(file.toFile(), type);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + file, e);
            }
        }
        T seeded = seed.get();
        write(objectMapper, file, seeded, ownerOnly);
        return seeded;
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
