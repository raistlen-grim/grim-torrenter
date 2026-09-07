package com.grimtorrenter.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * File-backed AuthStore - auth.json, in the same grimtorrenter.config-directory as
 * settings.json but deliberately a separate file, so a password hash can never end up
 * reachable through GET /api/settings (which echoes that whole record back verbatim). Same
 * "write-then-swap on update, read-or-create-default on init" shape as JsonSettingsStore. See
 * design_docs/0061.
 *
 * <p>bootstrapPassword (grimtorrenter.bootstrap-password, an optional deploy-time env var,
 * e.g. `-e grimtorrenter.bootstrap-password=...` matching this project's existing
 * listen-port-style override convention) seeds auth.json only the first time it's created -
 * a convenience for a first `docker run` so the app need not sit open until someone finds the
 * Settings page. Ignored on every later startup once auth.json already exists (same
 * first-run-only behavior Grafana's own GF_SECURITY_ADMIN_PASSWORD uses) - a stale/leftover
 * env var can't silently reset a password the user has since changed through the app.
 */
@ApplicationScoped
public class JsonAuthStore implements AuthStore {

    private static final String AUTH_FILENAME = "auth.json";

    @ConfigProperty(name = "grimtorrenter.config-directory", defaultValue = "config")
    String configDirectory;

    /** Optional&lt;String&gt;, not a plain String with defaultValue = "" - Quarkus/SmallRye's
     * build-time config validation treats an empty-string defaultValue on a String-typed
     * @ConfigProperty as "no default provided," so it fails startup demanding this property
     * exist somewhere (a real, observed failure - not a hypothetical). Optional is the
     * standard idiom for a config property that's genuinely allowed to be absent. */
    @ConfigProperty(name = "grimtorrenter.bootstrap-password")
    Optional<String> bootstrapPassword;

    @Inject
    ObjectMapper objectMapper;

    private Path authFile;
    private volatile StoredAuth current;

    /** Package-private (not private), same reason as JsonSettingsStore's own init() - a test
     * can call it directly after setting configDirectory/bootstrapPassword/objectMapper
     * itself, without going through CDI. */
    @PostConstruct
    void init() {
        Path directory = Path.of(configDirectory);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create config directory " + directory, e);
        }
        authFile = directory.resolve(AUTH_FILENAME);
        current = readOrSeed();
    }

    @Override
    public boolean hasPassword() {
        String hash = current.passwordHash();
        return hash != null && !hash.isBlank();
    }

    @Override
    public boolean verify(String password) {
        return hasPassword() && password != null && PasswordHasher.matches(password, current.passwordHash());
    }

    /** synchronized, same reason as JsonSettingsStore.update() - writes to disk before
     * swapping the in-memory value, so two concurrent password changes can't interleave their
     * writes and a crash between the two never leaves the in-memory view claiming a change
     * that isn't actually durable yet. */
    @Override
    public synchronized void setPassword(String newPassword) {
        StoredAuth updated = new StoredAuth(PasswordHasher.hash(newPassword));
        writeToDisk(updated);
        current = updated;
    }

    private StoredAuth readOrSeed() {
        if (Files.exists(authFile)) {
            try {
                return objectMapper.readValue(authFile.toFile(), StoredAuth.class);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + authFile, e);
            }
        }
        StoredAuth seeded = bootstrapPassword.filter(password -> !password.isBlank())
                .map(password -> new StoredAuth(PasswordHasher.hash(password)))
                .orElseGet(() -> new StoredAuth(null));
        writeToDisk(seeded);
        return seeded;
    }

    private void writeToDisk(StoredAuth auth) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(authFile.toFile(), auth);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + authFile, e);
        }
    }
}
