package com.grimtorrenter.engine.ratelimit;

import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.SettingsStore;

import java.time.LocalTime;

/**
 * The upload/download pair every connection actually throttles against - bundled into one
 * object so TorrentEngine only has to construct and thread through one thing, not two. See
 * design_docs/0042.
 */
public record RateLimiters(RateLimiter upload, RateLimiter download) {

    /** Each RateLimiter's limit function calls LocalTime.now() itself, not once up front -
     * RateLimiter already re-reads settingsStore.current() on every acquire() so a live
     * settings change takes effect immediately (design_docs/0042); reading the clock the
     * same way is what makes a schedule window's start/end actually take effect the moment
     * it's crossed, not just on the next settings change. See design_docs/0046. */
    public static RateLimiters from(SettingsStore settingsStore) {
        return new RateLimiters(
                new RateLimiter(settingsStore, settings -> RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.now())),
                new RateLimiter(settingsStore, settings -> RateLimitSchedule.effectiveDownloadLimit(settings, LocalTime.now())));
    }

    /** For every pre-existing caller/test that predates rate limiting and doesn't care
     * about it - backed by its own always-unlimited store, not shared with anything real,
     * so it can never accidentally throttle production traffic. */
    public static RateLimiters unlimited() {
        return from(new InMemorySettingsStore());
    }
}
