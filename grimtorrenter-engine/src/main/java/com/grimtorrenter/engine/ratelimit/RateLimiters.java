package com.grimtorrenter.engine.ratelimit;

import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.SettingsStore;
import com.grimtorrenter.engine.torrent.TorrentLimitOverride;

import java.time.LocalTime;
import java.util.function.Supplier;

/**
 * The upload/download pair every connection actually throttles against - bundled into one
 * object so TorrentEngine only has to construct and thread through one thing, not two. See
 * design_docs/0042.
 *
 * <p>Backed by a Supplier per direction rather than a bare field - the plain global pair
 * (from()/unlimited()) just returns a fixed RateLimiter every time, but forTorrent()'s
 * resolving pair needs to pick a different underlying RateLimiter on every single call. See
 * design_docs/0072.
 */
public final class RateLimiters {

    private final Supplier<RateLimiter> upload;
    private final Supplier<RateLimiter> download;

    private RateLimiters(Supplier<RateLimiter> upload, Supplier<RateLimiter> download) {
        this.upload = upload;
        this.download = download;
    }

    public RateLimiter upload() {
        return upload.get();
    }

    public RateLimiter download() {
        return download.get();
    }

    /** Each RateLimiter's limit function calls LocalTime.now() itself, not once up front -
     * RateLimiter already re-reads settingsStore.current() on every acquire() so a live
     * settings change takes effect immediately (design_docs/0042); reading the clock the
     * same way is what makes a schedule window's start/end actually take effect the moment
     * it's crossed, not just on the next settings change. See design_docs/0046. */
    public static RateLimiters from(SettingsStore settingsStore) {
        RateLimiter fixedUpload =
                new RateLimiter(settingsStore, settings -> RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.now()));
        RateLimiter fixedDownload =
                new RateLimiter(settingsStore, settings -> RateLimitSchedule.effectiveDownloadLimit(settings, LocalTime.now()));
        return new RateLimiters(() -> fixedUpload, () -> fixedDownload);
    }

    /** For every pre-existing caller/test that predates rate limiting and doesn't care
     * about it - backed by its own always-unlimited store, not shared with anything real,
     * so it can never accidentally throttle production traffic. */
    public static RateLimiters unlimited() {
        return from(new InMemorySettingsStore());
    }

    /** A per-torrent resolving pair - upload()/download() pick, live on every call, either the
     * literal shared global RateLimiter (when this torrent's override is currently INHERIT, so
     * every inheriting torrent still draws from the exact same bucket as today) or a dedicated
     * per-torrent RateLimiter that never touches the global bucket at all. This is a deliberate
     * departure from design_docs/0042's original "one global cap over combined traffic" - an
     * overridden torrent's traffic is no longer bounded by the global cap, not merely nested
     * under it. See design_docs/0072.
     *
     * <p>liveOverride is read fresh on every upload()/download() call (not cached), so a live
     * PUT to the override takes effect on the very next acquire() - no restart, matching every
     * other RateLimiter-consuming setting in this codebase. Explicit 0 ("no limit for this
     * torrent") needs no special-casing here: RateLimiter.acquire() already treats a limit
     * <= 0 as instant-return. */
    public static RateLimiters forTorrent(RateLimiters global, SettingsStore settingsStore,
                                           Supplier<TorrentLimitOverride> liveOverride) {
        // dedicatedUpload/dedicatedDownload's own limit suppliers read the override's raw
        // sentinel directly, not through an inherit-aware resolver - they're only ever
        // actually consulted (via the outer suppliers below) while the override is non-
        // negative, so the raw value is always either 0 (RateLimiter already treats <= 0 as
        // unlimited) or a positive custom cap. Never invoked while negative (inherit).
        RateLimiter dedicatedUpload = new RateLimiter(
                () -> liveOverride.get().uploadBytesPerSecOverride(),
                () -> RateLimiter.burstSeconds(settingsStore.current()));
        RateLimiter dedicatedDownload = new RateLimiter(
                () -> liveOverride.get().downloadBytesPerSecOverride(),
                () -> RateLimiter.burstSeconds(settingsStore.current()));
        return new RateLimiters(
                () -> liveOverride.get().uploadBytesPerSecOverride() < 0 ? global.upload() : dedicatedUpload,
                () -> liveOverride.get().downloadBytesPerSecOverride() < 0 ? global.download() : dedicatedDownload);
    }
}
