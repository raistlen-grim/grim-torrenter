package com.grimtorrenter.engine.ratelimit;

import com.grimtorrenter.engine.settings.Settings;

import java.time.LocalTime;

/**
 * Resolves Settings' rate-limit schedule (design_docs/0046) - a single daily off-hours
 * window, the same every day - against a clock, picking the scheduled upload/download limit
 * over the base one while "now" falls inside it. A pure function of (Settings, LocalTime)
 * rather than reaching for LocalTime.now() itself, so it's testable against a fixed time
 * instead of the real wall clock - RateLimiters.from() is what supplies the real clock in
 * production, the same seam RateLimiter itself already reads its base limit through
 * (a ToLongFunction&lt;Settings&gt; supplied at construction, see design_docs/0042).
 */
final class RateLimitSchedule {

    private RateLimitSchedule() {
    }

    static long effectiveUploadLimit(Settings settings, LocalTime now) {
        return effectiveLimit(settings, now, settings.uploadRateLimitBytesPerSec(), settings.scheduledUploadRateLimitBytesPerSec());
    }

    static long effectiveDownloadLimit(Settings settings, LocalTime now) {
        return effectiveLimit(settings, now, settings.downloadRateLimitBytesPerSec(), settings.scheduledDownloadRateLimitBytesPerSec());
    }

    private static long effectiveLimit(Settings settings, LocalTime now, long baseLimit, long scheduledLimit) {
        if (!settings.rateLimitScheduleEnabled() || !isWithinWindow(settings, now)) {
            return baseLimit;
        }
        return scheduledLimit;
    }

    /** Handles a window that crosses midnight (e.g. 23:00-07:00) the same as one that
     * doesn't (e.g. 09:00-17:00) - the wrap case is simply "now is at/after start OR before
     * end" instead of "between start and end". A zero-length window (start == end) is
     * treated as never active rather than as either always-on or always-off - either of
     * those would be a surprising, silent 24h-wide effect from what looks like an empty
     * window. */
    private static boolean isWithinWindow(Settings settings, LocalTime now) {
        LocalTime start = LocalTime.parse(settings.rateLimitScheduleStart());
        LocalTime end = LocalTime.parse(settings.rateLimitScheduleEnd());
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }
}
