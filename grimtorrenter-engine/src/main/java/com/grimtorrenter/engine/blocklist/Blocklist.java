package com.grimtorrenter.engine.blocklist;

import com.grimtorrenter.engine.events.EventStore;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.proxy.MiniHttp;
import com.grimtorrenter.engine.proxy.ProxyProvider;
import com.grimtorrenter.engine.proxy.ProxySettings;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The engine-wide IP blocklist (design_docs/0078): loads a list from a local file or an
 * {@code http(s)://} URL per the live {@link Settings}, keeps the last good download cached so
 * a restart (or being offline) never leaves the engine unprotected, and answers
 * {@link #isBlocked} for every connection path.
 *
 * <p>{@link #refreshIfNeeded()} is the one entry point the engine's maintenance tick calls; it
 * decides whether anything needs (re)loading and, if so, does the work on its own virtual
 * thread - never on the shared scheduler thread, and at most one load at a time. A failed load
 * leaves the previous list in force (never silently dropping protection) and is retried no
 * sooner than {@link #RETRY_DELAY_MILLIS} later.
 */
public final class Blocklist implements IpFilter {

    /** enabled reflects the live setting; rangeCount is the number of merged ranges currently
     * enforced; blockedCount is addresses refused since this process started. */
    public record Status(boolean enabled, String source, int rangeCount, long loadedAtEpochMillis,
                         String lastError, boolean loading, long blockedCount) {
    }

    static final long MAX_DOWNLOAD_BYTES = 32L << 20;
    static final String CACHE_FILENAME = ".grimtorrenter-blocklist-cache";
    static final String CACHE_META_FILENAME = ".grimtorrenter-blocklist-cache.meta";
    private static final long RETRY_DELAY_MILLIS = 15 * 60_000L;
    private static final long TRANSFER_DEADLINE_SECONDS = 180;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(60);

    private static final System.Logger LOG = System.getLogger(Blocklist.class.getName());

    private final Path configDirectory;
    private final SettingsStore settingsStore;
    private final EventStore eventStore;
    private final Runnable onChanged;
    private final ProxyProvider proxyProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicLong blockedCount = new AtomicLong();

    private volatile IpRangeSet ranges = IpRangeSet.EMPTY;
    /** The source {@link #ranges} came from, or null when nothing is loaded. */
    private volatile String loadedSource;
    private volatile long loadedAtMillis;
    /** When a URL's list was last downloaded (the cache file's mtime) - drives the refresh interval. */
    private volatile long lastFetchMillis;
    /** For a file source: its mtime when last read, so an edit is noticed. */
    private volatile long loadedFileMtime;
    private volatile String lastError;
    private volatile long nextRetryAtMillis;
    private volatile String failedSource;

    /**
     * @param onChanged called (from the loading thread) whenever the enforced list changes, so
     *                  the engine can drop already-known and already-connected blocked peers
     */
    public Blocklist(Path configDirectory, SettingsStore settingsStore, EventStore eventStore, Runnable onChanged) {
        this(configDirectory, settingsStore, eventStore, onChanged, ProxyProvider.NONE);
    }

    /** proxyProvider is read on every download: while it names a proxy the list is fetched through
     * it (so the list host never sees this machine's real address), otherwise directly. See
     * design_docs/0079. */
    public Blocklist(Path configDirectory, SettingsStore settingsStore, EventStore eventStore, Runnable onChanged,
                     ProxyProvider proxyProvider) {
        this.configDirectory = configDirectory;
        this.settingsStore = settingsStore;
        this.eventStore = eventStore;
        this.onChanged = onChanged;
        this.proxyProvider = proxyProvider;
    }

    @Override
    public boolean isBlocked(InetAddress address) {
        return ranges.contains(address);
    }

    @Override
    public void recordBlocked() {
        blockedCount.incrementAndGet();
    }

    public Status status() {
        Settings settings = settingsStore.current();
        return new Status(settings.blocklistEnabled(), sourceOf(settings), ranges.size(), loadedAtMillis,
                lastError, loading.get(), blockedCount.get());
    }

    /** Called periodically by the engine. Cheap when nothing needs doing. */
    public void refreshIfNeeded() {
        Settings settings = settingsStore.current();
        String source = sourceOf(settings);
        if (!settings.blocklistEnabled() || source.isEmpty()) {
            if (loadedSource != null || !ranges.isEmpty()) {
                clear();
            }
            lastError = null;
            return;
        }
        if (loading.get()) {
            return;
        }
        boolean sourceChanged = !source.equals(loadedSource);
        boolean needed;
        if (sourceChanged) {
            needed = true;
        } else if (isUrl(source)) {
            long hours = settings.blocklistRefreshHours();
            needed = hours > 0 && System.currentTimeMillis() - lastFetchMillis >= hours * 3_600_000L;
        } else {
            needed = fileMtime(source) != loadedFileMtime;
        }
        if (!needed) {
            return;
        }
        boolean retryGated = source.equals(failedSource) && System.currentTimeMillis() < nextRetryAtMillis;
        if (!retryGated) {
            startReload(source, false);
        }
    }

    /** Forces a reload now (a URL is re-downloaded regardless of its refresh interval). Returns
     * immediately - the load runs on its own thread; poll {@link #status()}. */
    public void reloadNow() {
        Settings settings = settingsStore.current();
        String source = sourceOf(settings);
        if (!settings.blocklistEnabled() || source.isEmpty()) {
            lastError = "No blocklist source is configured";
            return;
        }
        startReload(source, true);
    }

    private void startReload(String source, boolean force) {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("blocklist-reload").start(() -> {
            try {
                if (isUrl(source)) {
                    reloadFromUrl(source, force);
                } else {
                    reloadFromFile(source);
                }
            } catch (Exception e) {
                fail(source, e);
            } finally {
                loading.set(false);
            }
        });
    }

    private void reloadFromFile(String source) throws IOException {
        Path path = resolveFile(source);
        if (!Files.isRegularFile(path)) {
            throw new IOException("File not found: " + path);
        }
        long mtime = Files.getLastModifiedTime(path).toMillis();
        BlocklistParser.Result result;
        try (InputStream in = Files.newInputStream(path)) {
            result = BlocklistParser.parse(in);
        }
        loadedFileMtime = mtime;
        publish(result.ranges(), source, 0, true, result);
    }

    private void reloadFromUrl(String source, boolean force) throws Exception {
        long periodMillis = settingsStore.current().blocklistRefreshHours() * 3_600_000L;
        Path cache = configDirectory.resolve(CACHE_FILENAME);
        boolean haveCache = false;
        long cacheMtime = 0;
        if (cacheMatches(source)) {
            try (InputStream in = Files.newInputStream(cache)) {
                BlocklistParser.Result result = BlocklistParser.parse(in);
                cacheMtime = Files.getLastModifiedTime(cache).toMillis();
                haveCache = true;
                if (!force && !source.equals(loadedSource)) {
                    // Startup, or this source was just (re)selected: enforce the cached copy
                    // straight away, before any download has a chance to fail or take a while.
                    publish(result.ranges(), source, cacheMtime, false, result);
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable blocklist cache: " + e.getMessage());
            }
        }
        boolean stale = force || !haveCache || (periodMillis > 0 && System.currentTimeMillis() - cacheMtime >= periodMillis);
        if (!stale) {
            return;
        }
        Files.createDirectories(configDirectory);
        Path temp = Files.createTempFile(configDirectory, CACHE_FILENAME, ".tmp");
        try {
            download(source, temp);
            BlocklistParser.Result result;
            try (InputStream in = Files.newInputStream(temp)) {
                result = BlocklistParser.parse(in);
            }
            Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(configDirectory.resolve(CACHE_META_FILENAME), "source=" + source + "\n");
            publish(result.ranges(), source, System.currentTimeMillis(), true, result);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void download(String url, Path target) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Only http(s) URLs are supported");
        }
        Optional<ProxySettings> proxy = proxyProvider.current();
        if (proxy.isPresent()) {
            try (OutputStream out = Files.newOutputStream(target)) {
                int status = MiniHttp.get(uri, proxy.get(), MAX_DOWNLOAD_BYTES, out, MAX_REDIRECTS,
                        (int) TRANSFER_DEADLINE_SECONDS);
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }
            }
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(HEADERS_TIMEOUT)
                .header("User-Agent", "GrimTorrenter")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        // The request timeout above only covers the headers - this bounds the transfer itself, so
        // a server that trickles bytes can't hold the (single) load slot forever. Closing the
        // stream unblocks a read in progress with an IOException.
        CompletableFuture.delayedExecutor(TRANSFER_DEADLINE_SECONDS, TimeUnit.SECONDS).execute(() -> closeQuietly(body));
        try {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            try (InputStream in = new LimitedInputStream(body, MAX_DOWNLOAD_BYTES, "The download");
                 OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
        } finally {
            closeQuietly(body);
        }
    }

    private void publish(IpRangeSet newRanges, String source, long fetchMillis, boolean announce,
                         BlocklistParser.Result result) {
        this.ranges = newRanges;
        this.loadedSource = source;
        this.loadedAtMillis = System.currentTimeMillis();
        if (fetchMillis > 0) {
            this.lastFetchMillis = fetchMillis;
        }
        this.lastError = null;
        this.failedSource = null;
        LOG.log(System.Logger.Level.INFO, "Blocklist loaded: " + newRanges.size() + " ranges from " + source
                + " (" + result.skipped() + " of " + result.lines() + " lines skipped)");
        if (announce) {
            eventStore.record(new LibraryEvent(Instant.now(), EventType.BLOCKLIST_UPDATED, null, null,
                    newRanges.size() + " ranges from " + source));
        }
        runOnChanged();
    }

    private void fail(String source, Exception e) {
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        boolean firstTime = !reason.equals(lastError);
        this.lastError = reason;
        this.failedSource = source;
        this.nextRetryAtMillis = System.currentTimeMillis() + RETRY_DELAY_MILLIS;
        LOG.log(System.Logger.Level.WARNING, "Blocklist load failed for " + source + ": " + reason);
        if (firstTime) {
            eventStore.record(new LibraryEvent(Instant.now(), EventType.BLOCKLIST_FAILED, null, null,
                    "Could not load " + source + ": " + reason));
        }
    }

    private void clear() {
        this.ranges = IpRangeSet.EMPTY;
        this.loadedSource = null;
        this.loadedAtMillis = 0;
        this.failedSource = null;
        runOnChanged();
    }

    private void runOnChanged() {
        try {
            onChanged.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Blocklist change handler failed", e);
        }
    }

    private boolean cacheMatches(String source) {
        Path meta = configDirectory.resolve(CACHE_META_FILENAME);
        if (!Files.isRegularFile(configDirectory.resolve(CACHE_FILENAME)) || !Files.isRegularFile(meta)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(meta);
            return lines.contains("source=" + source);
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolveFile(String source) {
        Path path = Path.of(source);
        return path.isAbsolute() ? path : configDirectory.resolve(path);
    }

    private long fileMtime(String source) {
        try {
            return Files.getLastModifiedTime(resolveFile(source)).toMillis();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static String sourceOf(Settings settings) {
        String source = settings.blocklistSource();
        return source == null ? "" : source.strip();
    }

    private static boolean isUrl(String source) {
        String lower = source.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // already closed or failing - nothing more to do
        }
    }
}
