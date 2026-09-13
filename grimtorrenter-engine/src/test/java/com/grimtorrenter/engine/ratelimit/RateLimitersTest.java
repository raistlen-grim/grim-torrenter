package com.grimtorrenter.engine.ratelimit;

import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.torrent.TorrentLimitOverride;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0072 - forTorrent()'s own resolving behavior, distinct from RateLimiter's own
 * throttling mechanics (already covered by RateLimiterTest). */
class RateLimitersTest {

    private static long elapsedMs(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void inheritReturnsTheLiteralSharedGlobalRateLimiterInstance() {
        RateLimiters global = RateLimiters.unlimited();
        TorrentLimitOverride[] override = {TorrentLimitOverride.INHERIT};
        RateLimiters perTorrent = RateLimiters.forTorrent(global, new InMemorySettingsStore(), () -> override[0]);

        assertSame(global.upload(), perTorrent.upload(), "an inheriting torrent must share the exact global bucket");
        assertSame(global.download(), perTorrent.download());
    }

    @Test
    void aCustomOverrideDrawsFromADedicatedBucketNotTheGlobalOne() {
        // A global limiter so restrictive it would never let a real request through, proving a
        // custom-overridden torrent's acquire() below genuinely isn't touching it.
        RateLimiters global = RateLimiters.from(new InMemorySettingsStore(
                new com.grimtorrenter.engine.settings.Settings(true, true, 1, 1)));
        TorrentLimitOverride[] override = {new TorrentLimitOverride(1_000_000, -1, -1)};
        RateLimiters perTorrent = RateLimiters.forTorrent(global, new InMemorySettingsStore(), () -> override[0]);

        long elapsed = elapsedMs(() -> perTorrent.upload().acquire(10_000));

        assertTrue(elapsed < 200,
                "a generous custom override (1,000,000 bytes/sec) should let 10,000 bytes through almost "
                        + "immediately, not queue behind the global limiter's 1 byte/sec cap, took " + elapsed + "ms");
    }

    @Test
    void anExplicitZeroOverrideMeansUnlimitedForThisTorrent() {
        RateLimiters global = RateLimiters.from(new InMemorySettingsStore(
                new com.grimtorrenter.engine.settings.Settings(true, true, 1, 1)));
        TorrentLimitOverride[] override = {new TorrentLimitOverride(0, -1, -1)};
        RateLimiters perTorrent = RateLimiters.forTorrent(global, new InMemorySettingsStore(), () -> override[0]);

        long elapsed = elapsedMs(() -> perTorrent.upload().acquire(10_000_000));

        assertTrue(elapsed < 200, "explicit 0 should mean unlimited, took " + elapsed + "ms");
    }

    @Test
    void aLiveOverrideChangeTakesEffectOnTheNextCall() {
        RateLimiters global = RateLimiters.unlimited();
        TorrentLimitOverride[] override = {TorrentLimitOverride.INHERIT};
        RateLimiters perTorrent = RateLimiters.forTorrent(global, new InMemorySettingsStore(), () -> override[0]);

        assertSame(global.upload(), perTorrent.upload());

        override[0] = new TorrentLimitOverride(0, 0, -1);

        assertTrue(perTorrent.upload() != global.upload(), "a live switch away from inherit should stop sharing the global bucket");
    }
}
