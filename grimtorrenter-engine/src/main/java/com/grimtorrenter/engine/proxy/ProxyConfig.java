package com.grimtorrenter.engine.proxy;

import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * The live proxy configuration (design_docs/0079): host, port and username are ordinary
 * {@link Settings} fields, read fresh on every call so a change applies to the very next
 * connection; the password is deliberately *not* a Settings field. {@code GET /api/settings}
 * echoes the whole record back, and unlike the login password (which is only ever compared, so
 * it's stored hashed) a proxy password has to be recoverable to send it to the proxy - so it lives
 * in its own file, owner-only where the platform supports that, and is never returned by any API.
 *
 * <p>Stored as plaintext because it has to be replayed; anyone who can read this file can read the
 * proxy password. That's the same exposure every torrent client with proxy auth has.
 */
public final class ProxyConfig implements ProxyProvider {

    public static final String PASSWORD_FILENAME = ".grimtorrenter-proxy-password";

    private final Path passwordFile;
    private final SettingsStore settingsStore;
    private volatile String password;

    public ProxyConfig(Path configDirectory, SettingsStore settingsStore) {
        this.passwordFile = configDirectory.resolve(PASSWORD_FILENAME);
        this.settingsStore = settingsStore;
        this.password = readPassword(passwordFile);
    }

    /** A usable proxy: enabled, with a host and a valid port. A half-filled configuration is
     * treated as no proxy at all rather than an error on every connection. */
    public static boolean isActive(Settings settings) {
        String host = settings.proxyHost();
        return settings.proxyEnabled() && host != null && !host.isBlank()
                && settings.proxyPort() > 0 && settings.proxyPort() <= 65535;
    }

    /** True when a proxy is active AND the operator asked for anything that can't use it to be
     * turned off - the DHT/µTP/LSD/inbound switch-off this engine applies at startup. */
    public static boolean blocksUnsupported(Settings settings) {
        return isActive(settings) && settings.proxyBlockUnsupported();
    }

    @Override
    public Optional<ProxySettings> current() {
        Settings settings = settingsStore.current();
        if (!isActive(settings)) {
            return Optional.empty();
        }
        String username = settings.proxyUsername();
        return Optional.of(new ProxySettings(settings.proxyHost().strip(), settings.proxyPort(),
                username == null || username.isBlank() ? null : username, password));
    }

    public boolean hasPassword() {
        String current = password;
        return current != null && !current.isEmpty();
    }

    /** Persists the new password (an empty one clears it). Written before it takes effect, so a
     * failed write leaves the old password in force. */
    public void setPassword(String newPassword) {
        if (newPassword == null || newPassword.isEmpty()) {
            clearPassword();
            return;
        }
        try {
            Path directory = passwordFile.getParent();
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, PASSWORD_FILENAME, ".tmp");
            try {
                restrictToOwner(temp);
                Files.writeString(temp, newPassword, StandardCharsets.UTF_8);
                Files.move(temp, passwordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the proxy password: " + e.getMessage(), e);
        }
        this.password = newPassword;
    }

    public void clearPassword() {
        try {
            Files.deleteIfExists(passwordFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not remove the proxy password: " + e.getMessage(), e);
        }
        this.password = null;
    }

    private static String readPassword(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            return null;
        }
    }

    /** Best effort - a no-op on a filesystem without POSIX permissions (Windows), where the file's
     * ACL inherits from the config directory instead. */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX filesystem, or not permitted - leave the default
        }
    }
}
