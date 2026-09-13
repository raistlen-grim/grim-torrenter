package com.grimtorrenter.engine.ratelimit;

import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * A token bucket shared across every connection that draws from it - one instance per
 * direction (upload, download), owned once at the TorrentEngine level and threaded down
 * through every TorrentSession/PeerConnection it creates, not per-session or
 * per-connection, since the limit is a single global cap over the engine's combined
 * traffic. See design_docs/0042.
 *
 * <p>Reads its current limit from SettingsStore.current() on every acquire() call rather
 * than caching it at construction - a live settings change takes effect on the very next
 * block sent/received, no separate reload step, consistent with SettingsStore itself being
 * the only holder of a Settings instance (design_docs/0041).
 *
 * <p>Steady-state bucket capacity is burstSeconds' worth of the current limit - refilled
 * continuously based on elapsed wall-clock time since the last refill, not in discrete
 * per-second ticks. burstSeconds itself comes from Settings.rateLimitBurstSeconds(), read
 * live on every acquire() the same as the limit itself; 0-or-less means the original default
 * of one second (see design_docs/0053). A single acquire() for more than the current capacity
 * (a real BitTorrent block can be up to 16 KiB - PieceManager.BLOCK_SIZE - which already
 * exceeds one second's worth of any limit under 16 KiB/s) temporarily extends the cap to fit
 * that one request, rather than being capped at the steady-state capacity and permanently
 * unable to ever accumulate enough - that would stall forever instead of just taking
 * proportionally longer, the whole point of a rate *limit* instead of a rate *wall*.
 */
public final class RateLimiter {

    private static final long DEFAULT_BURST_SECONDS = 1;

    private final LongSupplier limitBytesPerSecond;
    private final LongSupplier burstSecondsSupplier;

    private double availableTokens;
    private long lastRefillNanos = System.nanoTime();

    /** The general-purpose constructor - both the limit and the burst window are read fresh
     * on every acquire() call via these suppliers, so this class has no idea whether either
     * one is backed by a shared SettingsStore, a per-torrent override, or anything else. See
     * design_docs/0072 - this generalization is what lets RateLimiters.forTorrent() build a
     * dedicated per-torrent RateLimiter without needing a SettingsStore of its own. */
    public RateLimiter(LongSupplier limitBytesPerSecond, LongSupplier burstSecondsSupplier) {
        this.limitBytesPerSecond = limitBytesPerSecond;
        this.burstSecondsSupplier = burstSecondsSupplier;
    }

    /** Convenience overload for the common case - reads both the limit and the burst window
     * from the same live Settings snapshot. */
    public RateLimiter(SettingsStore settingsStore, ToLongFunction<Settings> limitBytesPerSecond) {
        this(() -> limitBytesPerSecond.applyAsLong(settingsStore.current()),
                () -> burstSeconds(settingsStore.current()));
    }

    /** Blocks the calling thread, in short increments (re-reading the live limit each time
     * a wait is needed, so a settings change mid-wait is picked up promptly rather than
     * only on the next call), until bytes worth of budget is available, then consumes it.
     * A limit of 0 or less means unlimited - returns immediately without ever taking the
     * lock's wait path. */
    public void acquire(int bytes) {
        if (bytes <= 0) {
            return;
        }
        while (true) {
            long waitMs;
            synchronized (this) {
                long limit = limitBytesPerSecond.getAsLong();
                if (limit <= 0) {
                    return;
                }
                refill(limit, burstSecondsSupplier.getAsLong(), bytes);
                if (availableTokens >= bytes) {
                    availableTokens -= bytes;
                    return;
                }
                double missing = bytes - availableTokens;
                waitMs = Math.max(1, (long) Math.ceil(missing * 1000.0 / limit));
            }
            sleep(waitMs);
        }
    }

    /** Package-private, not private - RateLimiters.forTorrent() also needs this exact
     * normalization for its dedicated per-torrent limiters, not just the SettingsStore-backed
     * convenience constructor above. See design_docs/0072. */
    static long burstSeconds(Settings settings) {
        long configured = settings.rateLimitBurstSeconds();
        return configured > 0 ? configured : DEFAULT_BURST_SECONDS;
    }

    /** Caller already holds the monitor - not re-synchronized here. pendingRequestBytes
     * only widens the cap for this one refill (never shrinks it below the steady-state
     * capacity) - the refill *rate* is still governed purely by limit either way, so an
     * over-capacity request still takes proportionally longer, it just isn't stuck
     * forever. See this class's own Javadoc. */
    private void refill(long limit, long burstSeconds, int pendingRequestBytes) {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        double capacity = Math.max(limit * burstSeconds, pendingRequestBytes);
        availableTokens = Math.min(capacity, availableTokens + elapsedSeconds * limit);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
