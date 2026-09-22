package com.grimtorrenter.engine.proxy;

import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0079. */
class ProxyConfigTest {

    private static Settings settings(boolean enabled, String host, int port, String user, boolean block) {
        return Settings.defaults().withProxy(enabled, host, port, user, block);
    }

    @Test
    void noProxyWhenDisabledOrHalfConfigured(@TempDir Path dir) {
        assertTrue(new ProxyConfig(dir, new InMemorySettingsStore(settings(false, "proxy.example", 1080, "", true)))
                .current().isEmpty());
        assertTrue(new ProxyConfig(dir, new InMemorySettingsStore(settings(true, "  ", 1080, "", true)))
                .current().isEmpty());
        assertTrue(new ProxyConfig(dir, new InMemorySettingsStore(settings(true, "proxy.example", 0, "", true)))
                .current().isEmpty());
        assertTrue(new ProxyConfig(dir, new InMemorySettingsStore(settings(true, "proxy.example", 70000, "", true)))
                .current().isEmpty());
    }

    @Test
    void anActiveProxyCarriesHostPortUsernameAndThePassword(@TempDir Path dir) {
        ProxyConfig config = new ProxyConfig(dir,
                new InMemorySettingsStore(settings(true, " proxy.example ", 1080, "alice", true)));
        config.setPassword("s3cret");

        ProxySettings proxy = config.current().orElseThrow();

        assertEquals("proxy.example", proxy.host());
        assertEquals(1080, proxy.port());
        assertEquals("alice", proxy.username());
        assertEquals("s3cret", proxy.password());
    }

    @Test
    void aBlankUsernameMeansNoAuthentication(@TempDir Path dir) {
        ProxyConfig config = new ProxyConfig(dir,
                new InMemorySettingsStore(settings(true, "proxy.example", 1080, "  ", true)));

        assertNull(config.current().orElseThrow().username());
        assertFalse(config.current().orElseThrow().hasCredentials());
    }

    @Test
    void theSettingsAreReadLiveOnEveryCall(@TempDir Path dir) {
        InMemorySettingsStore store = new InMemorySettingsStore(settings(false, "a.example", 1080, "", true));
        ProxyConfig config = new ProxyConfig(dir, store);
        assertTrue(config.current().isEmpty());

        store.update(settings(true, "b.example", 2080, "", true));

        assertEquals("b.example", config.current().orElseThrow().host());
        assertEquals(2080, config.current().orElseThrow().port());
    }

    @Test
    void thePasswordSurvivesARestartAndCanBeCleared(@TempDir Path dir) {
        InMemorySettingsStore store = new InMemorySettingsStore(settings(true, "proxy.example", 1080, "alice", true));
        ProxyConfig first = new ProxyConfig(dir, store);
        assertFalse(first.hasPassword());

        first.setPassword("s3cret");
        assertTrue(first.hasPassword());

        ProxyConfig second = new ProxyConfig(dir, store);
        assertTrue(second.hasPassword());
        assertEquals("s3cret", second.current().orElseThrow().password());

        second.clearPassword();
        assertFalse(second.hasPassword());
        assertFalse(Files.exists(dir.resolve(ProxyConfig.PASSWORD_FILENAME)));
        assertFalse(new ProxyConfig(dir, store).hasPassword());
    }

    @Test
    void anEmptyPasswordClearsIt(@TempDir Path dir) {
        ProxyConfig config = new ProxyConfig(dir,
                new InMemorySettingsStore(settings(true, "proxy.example", 1080, "alice", true)));
        config.setPassword("s3cret");

        config.setPassword("");

        assertFalse(config.hasPassword());
    }

    /** Where the file system supports it, the password file is readable by its owner only. */
    @Test
    void thePasswordFileIsOwnerOnlyWhereSupported(@TempDir Path dir) throws Exception {
        ProxyConfig config = new ProxyConfig(dir,
                new InMemorySettingsStore(settings(true, "proxy.example", 1080, "alice", true)));
        config.setPassword("s3cret");

        Path file = dir.resolve(ProxyConfig.PASSWORD_FILENAME);
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void blocksUnsupportedOnlyWhenAProxyIsActiveAndTheSwitchIsOn() {
        assertTrue(ProxyConfig.blocksUnsupported(settings(true, "proxy.example", 1080, "", true)));
        assertFalse(ProxyConfig.blocksUnsupported(settings(true, "proxy.example", 1080, "", false)));
        assertFalse(ProxyConfig.blocksUnsupported(settings(false, "proxy.example", 1080, "", true)));
        assertFalse(ProxyConfig.blocksUnsupported(settings(true, "", 1080, "", true)));
    }

    /** An absent proxyBlockUnsupported (a settings.json from before this feature) defaults to
     * blocking, the safe choice; only an explicit false turns it off. */
    @Test
    void theBlockSwitchDefaultsToOnWhenTheFieldIsAbsent() {
        assertTrue(Settings.defaults().proxyBlockUnsupported());
        assertFalse(Settings.defaults().withProxy(true, "p.example", 1080, "", false).proxyBlockUnsupported());
    }
}
