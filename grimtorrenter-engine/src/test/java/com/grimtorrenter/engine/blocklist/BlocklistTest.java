package com.grimtorrenter.engine.blocklist;

import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.InMemoryEventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0078. */
class BlocklistTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static Settings settings(boolean enabled, String source) {
        return Settings.defaults().withBlocklist(enabled, source, 168);
    }

    private static Blocklist newBlocklist(Path configDir, InMemorySettingsStore store, InMemoryEventStore events,
                                          AtomicInteger changes) {
        return new Blocklist(configDir, store, events, changes::incrementAndGet);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 5s");
    }

    /** Waits until no load is in flight and the outcome (loaded or failed) has landed. */
    private static void awaitSettled(Blocklist blocklist, BooleanSupplier outcome) throws InterruptedException {
        await(() -> !blocklist.status().loading() && outcome.getAsBoolean());
    }

    /** Unchecked, so it can be used inside the await lambdas below. */
    private static boolean blocked(Blocklist blocklist, String dotted) {
        try {
            return blocklist.isBlocked(InetAddress.getByName(dotted));
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasEvent(InMemoryEventStore events, EventType type) {
        return events.all().stream().map(LibraryEvent::type).anyMatch(type::equals);
    }

    private static long countEvents(InMemoryEventStore events, EventType type) {
        return events.all().stream().map(LibraryEvent::type).filter(type::equals).count();
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
        return out.toByteArray();
    }

    /** Returns the URL; counts every request in hits. Status 200 with the body, or the given
     * error status with none. */
    private String serve(byte[] body, int status, AtomicInteger hits) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/list.gz", exchange -> {
            hits.incrementAndGet();
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/list.gz";
    }

    @Test
    void aFileSourceIsLoadedAndEnforced(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "Bad:1.2.3.0-1.2.3.255\n");
        InMemoryEventStore events = new InMemoryEventStore();
        AtomicInteger changes = new AtomicInteger();
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, file.toString())), events, changes);

        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocklist.status().rangeCount() == 1);

        assertTrue(blocked(blocklist, "1.2.3.77"));
        assertFalse(blocked(blocklist, "1.2.4.1"));
        assertTrue(blocklist.status().loadedAtEpochMillis() > 0);
        assertNull(blocklist.status().lastError());
        assertTrue(changes.get() >= 1);
        assertTrue(hasEvent(events, EventType.BLOCKLIST_UPDATED));
    }

    @Test
    void aRelativeFilePathResolvesAgainstTheConfigDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("my-list.txt"), "5.5.5.5\n");
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, "my-list.txt")),
                new InMemoryEventStore(), new AtomicInteger());

        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocklist.status().rangeCount() == 1);

        assertTrue(blocked(blocklist, "5.5.5.5"));
    }

    @Test
    void nothingIsEnforcedWhileTheBlocklistIsDisabled(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "1.2.3.4\n");
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(false, file.toString())),
                new InMemoryEventStore(), new AtomicInteger());

        blocklist.refreshIfNeeded();

        assertFalse(blocked(blocklist, "1.2.3.4"));
        assertFalse(blocklist.status().enabled());
        assertEquals(0, blocklist.status().rangeCount());
    }

    @Test
    void editingTheFileIsPickedUpOnTheNextRefresh(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "1.2.3.4\n");
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, file.toString())),
                new InMemoryEventStore(), new AtomicInteger());
        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocked(blocklist, "1.2.3.4"));

        Files.writeString(file, "9.9.9.9\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocked(blocklist, "9.9.9.9"));

        assertFalse(blocked(blocklist, "1.2.3.4"));
    }

    /** Disabling (or clearing the source) drops enforcement and tells the engine so it can stop
     * treating already-known peers as blocked. */
    @Test
    void disablingAfterALoadClearsTheListAndNotifiesTheEngine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "1.2.3.4\n");
        InMemorySettingsStore store = new InMemorySettingsStore(settings(true, file.toString()));
        AtomicInteger changes = new AtomicInteger();
        Blocklist blocklist = newBlocklist(dir, store, new InMemoryEventStore(), changes);
        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocked(blocklist, "1.2.3.4"));
        int changesBefore = changes.get();

        store.update(settings(false, file.toString()));
        blocklist.refreshIfNeeded();

        assertFalse(blocked(blocklist, "1.2.3.4"));
        assertEquals(0, blocklist.status().rangeCount());
        assertTrue(changes.get() > changesBefore);
    }

    @Test
    void aMissingFileIsReportedOnceAndNotRetriedEveryTick(@TempDir Path dir) throws Exception {
        InMemoryEventStore events = new InMemoryEventStore();
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, dir.resolve("nope.txt").toString())),
                events, new AtomicInteger());

        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocklist.status().lastError() != null);
        blocklist.refreshIfNeeded();
        Thread.sleep(100);

        assertTrue(blocklist.status().lastError().contains("File not found"));
        assertEquals(1, countEvents(events, EventType.BLOCKLIST_FAILED));
        assertEquals(0, blocklist.status().rangeCount());
    }

    /** A failed refresh must never drop protection - the previous list stays in force. */
    @Test
    void aFailedReloadKeepsThePreviousListInForce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "1.2.3.4\n");
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, file.toString())),
                new InMemoryEventStore(), new AtomicInteger());
        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocked(blocklist, "1.2.3.4"));

        Files.delete(file);
        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocklist.status().lastError() != null);

        assertTrue(blocked(blocklist, "1.2.3.4"));
    }

    @Test
    void aUrlSourceIsDownloadedEnforcedAndCached(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = serve(gzip("Bad:40.0.0.0-40.0.0.255\n"), 200, hits);
        InMemoryEventStore events = new InMemoryEventStore();
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, url)), events, new AtomicInteger());

        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocklist.status().rangeCount() == 1);

        assertTrue(blocked(blocklist, "40.0.0.9"));
        assertEquals(1, hits.get());
        assertTrue(Files.exists(dir.resolve(Blocklist.CACHE_FILENAME)));
        assertTrue(Files.readString(dir.resolve(Blocklist.CACHE_META_FILENAME)).contains(url));
        assertTrue(hasEvent(events, EventType.BLOCKLIST_UPDATED));
    }

    /** The cache is what keeps a restart (or being offline) protected: a fresh Blocklist over
     * the same config directory enforces the cached list without downloading anything. */
    @Test
    void aRestartEnforcesTheCachedListWithoutRedownloading(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = serve(gzip("40.0.0.0/24\n"), 200, hits);
        Blocklist first = newBlocklist(dir, new InMemorySettingsStore(settings(true, url)),
                new InMemoryEventStore(), new AtomicInteger());
        first.refreshIfNeeded();
        awaitSettled(first, () -> first.status().rangeCount() == 1);
        server.stop(0);
        server = null;

        Blocklist second = newBlocklist(dir, new InMemorySettingsStore(settings(true, url)),
                new InMemoryEventStore(), new AtomicInteger());
        second.refreshIfNeeded();
        awaitSettled(second, () -> second.status().rangeCount() == 1);

        assertTrue(blocked(second, "40.0.0.200"));
        assertNull(second.status().lastError());
        assertEquals(1, hits.get(), "the second instance must not have downloaded again");
    }

    @Test
    void aCacheFromADifferentSourceIsNotTrusted(@TempDir Path dir) throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        String urlA = serve(gzip("40.0.0.0/24\n"), 200, hitsA);
        Blocklist first = newBlocklist(dir, new InMemorySettingsStore(settings(true, urlA)),
                new InMemoryEventStore(), new AtomicInteger());
        first.refreshIfNeeded();
        awaitSettled(first, () -> first.status().rangeCount() == 1);
        server.stop(0);

        AtomicInteger hitsB = new AtomicInteger();
        String urlB = serve(gzip("50.0.0.0/24\n"), 200, hitsB);
        Blocklist second = newBlocklist(dir, new InMemorySettingsStore(settings(true, urlB)),
                new InMemoryEventStore(), new AtomicInteger());
        second.refreshIfNeeded();
        awaitSettled(second, () -> blocked(second, "50.0.0.5"));

        assertFalse(blocked(second, "40.0.0.5"));
        assertEquals(1, hitsB.get());
    }

    @Test
    void anHttpErrorIsReportedAndLeavesNothingEnforced(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = serve(new byte[0], 500, hits);
        InMemoryEventStore events = new InMemoryEventStore();
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, url)), events, new AtomicInteger());

        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocklist.status().lastError() != null);

        assertTrue(blocklist.status().lastError().contains("HTTP 500"));
        assertEquals(0, blocklist.status().rangeCount());
        assertFalse(Files.exists(dir.resolve(Blocklist.CACHE_FILENAME)));
        assertTrue(hasEvent(events, EventType.BLOCKLIST_FAILED));
    }

    @Test
    void reloadNowRedownloadsEvenThoughTheCacheIsFresh(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = serve(gzip("40.0.0.0/24\n"), 200, hits);
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(true, url)),
                new InMemoryEventStore(), new AtomicInteger());
        blocklist.refreshIfNeeded();
        awaitSettled(blocklist, () -> blocklist.status().rangeCount() == 1);

        blocklist.reloadNow();
        await(() -> hits.get() == 2);
        awaitSettled(blocklist, () -> true);

        assertEquals(2, hits.get());
    }

    @Test
    void reloadNowWithNoSourceConfiguredReportsWhy(@TempDir Path dir) {
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(false, "")),
                new InMemoryEventStore(), new AtomicInteger());

        blocklist.reloadNow();

        assertNotNull(blocklist.status().lastError());
        assertFalse(blocklist.status().loading());
    }

    @Test
    void recordBlockedCountsRefusals(@TempDir Path dir) {
        Blocklist blocklist = newBlocklist(dir, new InMemorySettingsStore(settings(false, "")),
                new InMemoryEventStore(), new AtomicInteger());

        blocklist.recordBlocked();
        blocklist.recordBlocked();

        assertEquals(2, blocklist.status().blockedCount());
    }
}
