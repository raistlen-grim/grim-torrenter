package com.grimtorrenter.engine.ratelimit;

import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static long elapsedMs(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void unlimitedReturnsImmediately() {
        InMemorySettingsStore store = new InMemorySettingsStore(new Settings(true, true, 0, 0));
        RateLimiter limiter = new RateLimiter(store, Settings::uploadRateLimitBytesPerSec);

        long elapsed = elapsedMs(() -> limiter.acquire(10_000_000));

        assertTrue(elapsed < 100, "unlimited acquire() should return immediately, took " + elapsed + "ms");
    }

    /** The bucket starts empty (see RateLimiter's own Javadoc), so a single acquire() for
     * bytes at a given rate should take roughly bytes/rate seconds - asks for one second's
     * worth at a 1000 bytes/sec limit and checks for a generous lower bound, not a tight
     * upper one, to avoid flakiness while still clearly proving real throttling happened
     * (an unthrottled loopback-scale operation would complete in low single-digit ms). */
    @Test
    void limitedBlocksLongEnoughToRespectTheRate() {
        InMemorySettingsStore store = new InMemorySettingsStore(new Settings(true, true, 1000, 1000));
        RateLimiter limiter = new RateLimiter(store, Settings::uploadRateLimitBytesPerSec);

        long elapsed = elapsedMs(() -> limiter.acquire(1000));

        assertTrue(elapsed >= 700, "expected roughly a 1s wait for 1000 bytes at 1000 bytes/sec, took " + elapsed + "ms");
    }

    /** Without a configured burst, capacity stays at exactly one second's worth even after a
     * much longer idle period - the regression this and the next test guard: burstSeconds
     * defaulting to 0 (an unconfigured or legacy settings.json - see Settings' own Javadoc)
     * must not silently change today's default behavior. A single acquire() for more than the
     * capped 1000 bytes can't be used to observe this directly - RateLimiter deliberately
     * widens capacity to fit an over-cap single request (see its own Javadoc and
     * design_docs/0042's real-bug writeup), so requesting 2000 at once would succeed
     * instantly regardless of burst and prove nothing. Draining exactly the capped amount
     * first, then immediately requesting more with no time to accumulate further, is what
     * actually shows the cap held at 1000 rather than the full 2500 that idle time alone
     * would otherwise have produced. */
    @Test
    void withoutBurstConfiguredCapacityStaysAtOneSecondsWorthEvenAfterALongerIdlePeriod() throws InterruptedException {
        InMemorySettingsStore store = new InMemorySettingsStore(new Settings(true, true, 1000, 1000));
        RateLimiter limiter = new RateLimiter(store, Settings::uploadRateLimitBytesPerSec);

        Thread.sleep(2500);

        long firstElapsed = elapsedMs(() -> limiter.acquire(1000));
        assertTrue(firstElapsed < 200, "the capped 1000 bytes should already be available, took " + firstElapsed + "ms");

        long secondElapsed = elapsedMs(() -> limiter.acquire(500));
        assertTrue(secondElapsed >= 300,
                "capacity should have been capped at 1000 (not the full 2500 idle time alone would produce), so "
                        + "this second request should need to wait for more to accumulate, took " + secondElapsed + "ms");
    }

    /** With a 3-second burst configured, letting the limiter sit idle accumulates up to 3
     * seconds' worth of capacity (3000 bytes at 1000 bytes/sec) instead of capping at 1 - a
     * request that fits within that accumulated reserve should complete close to immediately,
     * not wait as if only 1 second's worth were available. */
    @Test
    void burstAllowanceLetsMoreThanOneSecondsWorthAccumulateAndBeSpentAtOnce() throws InterruptedException {
        InMemorySettingsStore store = new InMemorySettingsStore(
                new Settings(true, true, 1000, 1000, false, "23:00", "07:00", 0, 0, EncryptionMode.PREFERRED, 3));
        RateLimiter limiter = new RateLimiter(store, Settings::uploadRateLimitBytesPerSec);

        Thread.sleep(2500);

        long elapsed = elapsedMs(() -> limiter.acquire(2500));

        assertTrue(elapsed < 300,
                "with a 3s burst allowance, ~2500 bytes already accumulated should be available almost immediately, took "
                        + elapsed + "ms");
    }

    @Test
    void aLiveLimitChangeTakesEffectOnTheNextAcquireCall() {
        InMemorySettingsStore store = new InMemorySettingsStore(new Settings(true, true, 1, 1));
        RateLimiter limiter = new RateLimiter(store, Settings::uploadRateLimitBytesPerSec);

        store.update(new Settings(true, true, 0, 0));

        long elapsed = elapsedMs(() -> limiter.acquire(10_000_000));

        assertTrue(elapsed < 100, "settings change should have made this unlimited, took " + elapsed + "ms");
    }
}
