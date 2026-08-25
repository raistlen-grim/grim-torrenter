package com.grimtorrenter.engine.settings;

import com.grimtorrenter.engine.mse.EncryptionMode;

/**
 * User-editable, persisted application settings - distinct from deploy-time config
 * (download directory, listen port), which stays in grimtorrenter-app's own
 * application.properties and isn't user-editable at runtime. Always read through
 * SettingsStore.current() - never cached or read independently elsewhere. See
 * design_docs/0041.
 *
 * <p>dhtEnabled/acceptIncomingConnections both save live but only take effect on the next
 * service start - DhtNode/PeerServer are each created once, at TorrentEngine construction,
 * and don't yet support being started or stopped afterward. That's a property of what they
 * control, not a limitation of this store or a violation of "settings are live" - see
 * design_docs/0041 for the explicit call-out.
 *
 * <p>uploadRateLimitBytesPerSec/downloadRateLimitBytesPerSec are global caps shared across
 * every torrent's combined traffic, not per-torrent - genuinely live: RateLimiter reads
 * the current value on every acquire() call, so a change here takes effect on the very
 * next block sent/received, no restart needed. 0 (or negative) means unlimited. See
 * design_docs/0042.
 *
 * <p>rateLimitScheduleEnabled/rateLimitScheduleStart/rateLimitScheduleEnd/
 * scheduledUploadRateLimitBytesPerSec/scheduledDownloadRateLimitBytesPerSec define a single
 * daily off-hours window (the same window every day, "HH:mm" 24h strings parsed via
 * LocalTime.parse) that swaps in a different upload/download limit pair while it's active -
 * evaluated live, same as the base limits (see RateLimitSchedule in grimtorrenter-engine's
 * ratelimit package). A window may cross midnight (start after end); start == end is treated
 * as never active rather than as either always-on or always-off. The scheduled limits follow
 * the same 0-or-negative-means-unlimited rule as the base ones, and aren't required to be
 * higher - a schedule can just as well tighten the cap during a window as loosen it. See
 * design_docs/0046.
 *
 * <p>encryptionMode governs Message Stream Encryption (design_docs/0052) - genuinely live,
 * same as the rate limits: read fresh on every connection attempt/accept, so a change here
 * takes effect on the very next connection, no restart needed. Defaults to PREFERRED (attempt
 * encryption, fall back to plaintext for peers that don't support it) - unlike the rate limits
 * and schedule, which default to "off" as a deliberate opt-in, since MSE's whole benefit
 * (resisting naive ISP traffic-shaping) is one most users would want without having to find
 * the setting first, and PREFERRED never refuses a connection a plaintext-only peer would
 * otherwise have completed.
 *
 * <p>rateLimitBurstSeconds widens RateLimiter's bucket capacity beyond the steady-state
 * "one second's worth of the current limit" default - e.g. 3 lets up to 3 seconds' worth of
 * unused bandwidth accumulate and be spent at once, for a workload that's naturally bursty
 * rather than steady. Applies to whichever limit (base or scheduled) is currently active - one
 * shared knob for both directions and both windows, not a separate value per limit, since the
 * capacity it controls is always derived from whatever limit is in effect at the time. 0 (or
 * negative) means the original default of 1 second, not "no burst" - unlike the rate limits'
 * own 0-means-unlimited convention, since a burst of exactly 0 would make the limiter stricter
 * than it was before this setting existed, which is never what "burst allowance" should mean
 * to someone who hasn't touched the field. See design_docs/0053.
 *
 * <p>seedRatioLimitEnabled/seedRatioLimit and seedTimeLimitEnabled/seedTimeLimitMinutes are the
 * global defaults for automatically stopping a torrent once it's seeded enough - whichever
 * limit is enabled and reached first. Both default disabled (opt-in, like the rate limits -
 * see design_docs/0053's own reasoning; a limit that can silently stop a torrent is a much
 * bigger surprise to default on than encryptionMode's PREFERRED ever was). Neither limit
 * survives a process restart - both are computed from TorrentSession's own upload/download
 * byte counters and a completedAt timestamp, none of which are persisted, consistent with
 * those counters already resetting on every restart today (not a new limitation this feature
 * introduces). A per-torrent SeedingLimitOverride can override either default independently -
 * see that class's own Javadoc for its sentinel convention. See design_docs/0054.
 */
public record Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                        long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                        boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                        long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                        EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                        boolean seedRatioLimitEnabled, double seedRatioLimit,
                        boolean seedTimeLimitEnabled, long seedTimeLimitMinutes) {

    private static final String DEFAULT_SCHEDULE_START = "23:00";
    private static final String DEFAULT_SCHEDULE_END = "07:00";
    /** Starting values shown once a user enables a disabled seeding limit - meaningless while
     * disabled, same spirit as DEFAULT_SCHEDULE_START/END above. */
    private static final double DEFAULT_SEED_RATIO_LIMIT = 2.0;
    private static final long DEFAULT_SEED_TIME_LIMIT_MINUTES = 24 * 60;

    /** Backfills encryptionMode to PREFERRED when null - the common case being a settings.json
     * persisted before this field existed, which Jackson otherwise deserializes with this
     * field simply absent. Every caller, including Jackson's own record deserialization,
     * goes through the canonical constructor this compact form guards, so this is the one
     * place the default actually needs enforcing - the secondary constructors below already
     * pass PREFERRED explicitly and are unaffected. Same defensive-compact-constructor idiom
     * InfoHash already uses in this codebase. See design_docs/0052. */
    public Settings {
        if (encryptionMode == null) {
            encryptionMode = EncryptionMode.PREFERRED;
        }
    }

    /** Convenience constructor for every caller that doesn't care about the rate-limit
     * schedule or encryption mode (most of this project's existing tests, which predate
     * both) - defaults the schedule to disabled with placeholder times and encryption mode to
     * PREFERRED, matching defaults()' own defaults. Same "add a sibling overload, touch zero
     * existing call sites" pattern already used for RateLimiters/enableDht/
     * acceptIncomingConnections - see design_docs/0042. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                false, DEFAULT_SCHEDULE_START, DEFAULT_SCHEDULE_END, 0, 0, EncryptionMode.PREFERRED, 0,
                false, DEFAULT_SEED_RATIO_LIMIT, false, DEFAULT_SEED_TIME_LIMIT_MINUTES);
    }

    /** Same as the nine-arg constructor above but with an explicit rate-limit schedule and
     * PREFERRED encryption mode - for callers that predate encryptionMode's addition but do
     * care about the schedule. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, EncryptionMode.PREFERRED, 0,
                false, DEFAULT_SEED_RATIO_LIMIT, false, DEFAULT_SEED_TIME_LIMIT_MINUTES);
    }

    /** Same as the ten-arg constructor above but with an explicit encryption mode - for
     * callers that predate rateLimitBurstSeconds' addition but do care about encryption mode.
     * See design_docs/0053. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode, 0,
                false, DEFAULT_SEED_RATIO_LIMIT, false, DEFAULT_SEED_TIME_LIMIT_MINUTES);
    }

    /** Same as the eleven-arg constructor above but with an explicit rateLimitBurstSeconds -
     * for callers that predate seeding limits' addition but do care about burst. See
     * design_docs/0054. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode, long rateLimitBurstSeconds) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode,
                rateLimitBurstSeconds, false, DEFAULT_SEED_RATIO_LIMIT, false, DEFAULT_SEED_TIME_LIMIT_MINUTES);
    }

    /** Both rate limits (base and scheduled) default to unlimited (0) - opting into a cap is
     * a deliberate user action, not a surprise out-of-the-box slowdown. The schedule itself
     * defaults to disabled. Encryption mode defaults to PREFERRED - see this record's own
     * Javadoc for why that default differs from the rate limits' opt-in default. Burst
     * defaults to 0, meaning "the original 1-second default" - see this record's own Javadoc.
     * Both seeding limits default disabled, same opt-in reasoning as the rate limits. */
    public static Settings defaults() {
        return new Settings(true, true, 0, 0);
    }
}
