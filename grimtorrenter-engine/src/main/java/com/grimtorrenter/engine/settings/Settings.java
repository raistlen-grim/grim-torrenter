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
 *
 * <p>eventLogRetentionDays bounds how many days of library events (design_docs/0055) are kept
 * on disk as rolling daily files - live, like the rest of this record, but only takes effect on
 * the next hourly prune tick, not synchronously. Deliberately has **no** "0/negative means
 * unlimited" sentinel despite that being this record's own established idiom elsewhere
 * (uploadRateLimitBytesPerSec, seedRatioLimit, ...) - an event log is exactly the kind of thing
 * that grows without bound if "unlimited" is ever selectable, so 0/negative is instead
 * silently normalized to the default of 30 by the compact constructor below, the same
 * mechanism (and the same call site) that backfills a missing (pre-0055) encryptionMode - both
 * a missing-field-in-old-settings.json case and a defensive minimum in one place. A primitive
 * int can't distinguish "the field was absent" from "the field was explicitly 0," so this is a
 * silent normalization, not a validation error - unlike rateLimitScheduleStart/End, there is no
 * corresponding SettingsResource-level rejection for this field, since by the time that layer
 * sees a deserialized Settings there is no longer an invalid value left to reject.
 *
 * <p>watchFolderEnabled/watchFolderRetentionDays govern the watch-folder auto-add feature
 * (design_docs/0056) - both genuinely live, checked fresh on every scan tick, not just at
 * construction (unlike dhtEnabled/acceptIncomingConnections, there's no socket/resource to
 * tear down or recreate here, just "do nothing this tick" vs. "scan"). Defaults disabled -
 * an opt-in feature that moves/deletes files a user drops somewhere is a bigger surprise to
 * default on than DHT/incoming-connections ever were, matching the rate-limit/seeding-limit
 * precedent of defaulting a potentially-surprising behavior off. watchFolderRetentionDays
 * (default 7) bounds how long a resolved file sits in the watch directory's added/failed
 * subfolders before being deleted - same **no** "0/negative means unlimited" treatment as
 * eventLogRetentionDays, and for the same reason: silently normalized to the default by the
 * compact constructor below, not rejected at the REST boundary.
 *
 * <p>theme is the frontend's light/dark preference (design_docs/0032's "Manual theme switcher" section) - the one field
 * here with no engine/protocol relevance at all, unlike everything else in this record; kept
 * in the same store rather than a separate one anyway, since "the same GET/PUT /api/settings
 * round-trip as every other setting" was the explicit point of storing it server-side instead
 * of in browser localStorage. Defaults to SYSTEM (follow the OS/browser preference) - see
 * ThemePreference's own Javadoc.
 *
 * <p>magnetFetchTimeBudgetSeconds/magnetFetchCandidatesPerRound/magnetFetchConcurrencyLimit
 * tune how hard a magnet add tries to find a peer with the metadata (design_docs/0028's
 * addendum) - genuinely live, read fresh by TorrentEngine at the start of each magnet-add
 * attempt (so, like encryptionMode, a change here takes effect on the *next* attempt, not
 * retroactively mid-flight). Defaults (90s / 50 / 64) are meant to work out of the box;
 * exposed as user-editable mainly so an advanced user - or someone actively diagnosing a
 * connectivity issue - can tune them without a rebuild/restart. Same **no** "0/negative means
 * unlimited" treatment as eventLogRetentionDays/watchFolderRetentionDays and for the same
 * reason: silently normalized back to each real default by the compact constructor below, not
 * rejected at the REST boundary, and not a lever for "never give up" or "no concurrency bound
 * at all."
 *
 * <p>trackerlessDhtReannounceIntervalSeconds (design_docs/0036's own addendum) governs how
 * often a genuinely trackerless torrent re-queries DHT for fresh peers while running -
 * mirrors what a real tracker's own announce interval already does for a tracker-bearing
 * torrent, just user-tunable rather than tracker-dictated, since there's no tracker here to
 * dictate it. Read once per `start()` (same "fixed for this torrent's run, re-read on the
 * next start()" precedent a real tracker's own interval already follows - a live-scheduled
 * task's period can't be changed mid-flight without cancelling and rebuilding it). Default
 * 300s (5 minutes) - deliberately not shortened to "every few seconds": DHT re-querying the
 * same info hash that often is poor DHT citizenship (real clients typically use a
 * multi-minute cadence, similar to tracker announce intervals) and risks well-behaved remote
 * nodes deprioritizing overly-frequent queries; the actual bottleneck this doesn't fix is
 * routing-table richness, a separate concern. Same **no** "0/negative means unlimited"
 * treatment as the fields above, for the same reason.
 *
 * <p>dhtRefreshIntervalSeconds (design_docs/0028's own 2026-08-30 addendum) is the "routing-
 * table richness" fix trackerlessDhtReannounceIntervalSeconds's own Javadoc above defers to -
 * how often a background tick re-queries whichever DHT routing-table bucket has gone longest
 * without activity, reaching parts of the 160-bit id space a one-time startup bootstrap lookup
 * never touches (that lookup only explores the neighborhood near our own node id). Unlike
 * trackerlessDhtReannounceIntervalSeconds, this drives an engine-wide scheduled task
 * (TorrentEngine's maintenanceScheduler) rather than a per-torrent one, so a live change here
 * takes effect on the engine's next construction/restart, not retroactively - the same
 * "a ScheduledExecutorService's period can't change mid-flight" limitation, just at engine
 * scope instead of per-torrent scope. Default 300s (5 minutes) - each tick only issues one
 * find_node lookup against a single bucket, much lighter than a full get_peers reannounce, so
 * a shorter-than-libtorrent-typical (~15 minute) default is reasonable DHT etiquette while
 * still visibly filling in the table faster after a fresh start. Same **no** "0/negative means
 * unlimited" treatment as the fields above, for the same reason.
 */
public record Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                        long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                        boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                        long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                        EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                        boolean seedRatioLimitEnabled, double seedRatioLimit,
                        boolean seedTimeLimitEnabled, long seedTimeLimitMinutes,
                        int eventLogRetentionDays,
                        boolean watchFolderEnabled, int watchFolderRetentionDays,
                        ThemePreference theme,
                        int magnetFetchTimeBudgetSeconds, int magnetFetchCandidatesPerRound,
                        int magnetFetchConcurrencyLimit,
                        int trackerlessDhtReannounceIntervalSeconds,
                        int dhtRefreshIntervalSeconds) {

    private static final int DEFAULT_EVENT_LOG_RETENTION_DAYS = 30;
    private static final int DEFAULT_WATCH_FOLDER_RETENTION_DAYS = 7;
    private static final String DEFAULT_SCHEDULE_START = "23:00";
    private static final String DEFAULT_SCHEDULE_END = "07:00";
    /** Starting values shown once a user enables a disabled seeding limit - meaningless while
     * disabled, same spirit as DEFAULT_SCHEDULE_START/END above. */
    private static final double DEFAULT_SEED_RATIO_LIMIT = 2.0;
    private static final long DEFAULT_SEED_TIME_LIMIT_MINUTES = 24 * 60;
    /** design_docs/0028's addendum. Overall wall-clock budget for one magnet-add's whole
     * metadata-fetch phase (tracker or DHT path) - a bounded retry loop, not a single batch,
     * keeps re-announcing/re-querying and racing fresh candidates until this elapses or one
     * succeeds. */
    private static final int DEFAULT_MAGNET_FETCH_TIME_BUDGET_SECONDS = 90;
    /** How many peer candidates one round of the retry loop above races concurrently - also
     * drives the tracker announce's own num_want, so raising this actually asks trackers for
     * more too, not just using more of a fixed-size response. */
    private static final int DEFAULT_MAGNET_FETCH_CANDIDATES_PER_ROUND = 50;
    /** Engine-wide cap (LiveResizableSemaphore) on simultaneous in-flight metadata-fetch
     * connection attempts across every magnet-add happening at once - bounds total socket
     * fan-out regardless of how many magnets or rounds are concurrently in progress. */
    private static final int DEFAULT_MAGNET_FETCH_CONCURRENCY_LIMIT = 64;
    /** design_docs/0036's own addendum - see this record's own class-level Javadoc for the
     * DHT-etiquette reasoning behind 300s rather than something much shorter. */
    private static final int DEFAULT_TRACKERLESS_DHT_REANNOUNCE_INTERVAL_SECONDS = 300;
    /** design_docs/0028's own 2026-08-30 addendum - see this record's own class-level Javadoc
     * for why 300s is reasonable here despite being much shorter than libtorrent's own ~15
     * minute bucket-refresh cadence. */
    private static final int DEFAULT_DHT_REFRESH_INTERVAL_SECONDS = 300;

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
        if (eventLogRetentionDays <= 0) {
            eventLogRetentionDays = DEFAULT_EVENT_LOG_RETENTION_DAYS;
        }
        if (watchFolderRetentionDays <= 0) {
            watchFolderRetentionDays = DEFAULT_WATCH_FOLDER_RETENTION_DAYS;
        }
        if (theme == null) {
            theme = ThemePreference.SYSTEM;
        }
        // Never "unlimited" for the same reason eventLogRetentionDays/watchFolderRetentionDays
        // aren't - see design_docs/0028's addendum. A user can still set any of these
        // generously high (that's the point of them being editable), just not to a degenerate
        // value that would mean "never give up" or "no concurrency bound at all."
        if (magnetFetchTimeBudgetSeconds <= 0) {
            magnetFetchTimeBudgetSeconds = DEFAULT_MAGNET_FETCH_TIME_BUDGET_SECONDS;
        }
        if (magnetFetchCandidatesPerRound <= 0) {
            magnetFetchCandidatesPerRound = DEFAULT_MAGNET_FETCH_CANDIDATES_PER_ROUND;
        }
        if (magnetFetchConcurrencyLimit <= 0) {
            magnetFetchConcurrencyLimit = DEFAULT_MAGNET_FETCH_CONCURRENCY_LIMIT;
        }
        if (trackerlessDhtReannounceIntervalSeconds <= 0) {
            trackerlessDhtReannounceIntervalSeconds = DEFAULT_TRACKERLESS_DHT_REANNOUNCE_INTERVAL_SECONDS;
        }
        if (dhtRefreshIntervalSeconds <= 0) {
            dhtRefreshIntervalSeconds = DEFAULT_DHT_REFRESH_INTERVAL_SECONDS;
        }
    }

    /** Same as the canonical constructor above but without dhtRefreshIntervalSeconds - for
     * every caller that predates this addition (every secondary constructor below, plus any
     * direct twenty-three-arg caller), defaulting it (the compact constructor above normalizes
     * 0 to the real default, so passing 0 here is equivalent to passing the default
     * explicitly). Same "add a sibling overload, touch zero existing call sites" pattern used
     * for every prior field addition to this record. See design_docs/0028's own 2026-08-30
     * addendum. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                     boolean seedRatioLimitEnabled, double seedRatioLimit,
                     boolean seedTimeLimitEnabled, long seedTimeLimitMinutes,
                     int eventLogRetentionDays,
                     boolean watchFolderEnabled, int watchFolderRetentionDays,
                     ThemePreference theme,
                     int magnetFetchTimeBudgetSeconds, int magnetFetchCandidatesPerRound,
                     int magnetFetchConcurrencyLimit,
                     int trackerlessDhtReannounceIntervalSeconds) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode,
                rateLimitBurstSeconds, seedRatioLimitEnabled, seedRatioLimit, seedTimeLimitEnabled,
                seedTimeLimitMinutes, eventLogRetentionDays, watchFolderEnabled, watchFolderRetentionDays, theme,
                magnetFetchTimeBudgetSeconds, magnetFetchCandidatesPerRound, magnetFetchConcurrencyLimit,
                trackerlessDhtReannounceIntervalSeconds, 0);
    }

    /** Same as the canonical constructor above but without trackerlessDhtReannounceIntervalSeconds
     * - for every caller that predates this addition (every secondary constructor below, plus
     * any direct twenty-two-arg caller), defaulting it (the compact constructor above
     * normalizes 0 to the real default, so passing 0 here is equivalent to passing the default
     * explicitly). Same "add a sibling overload, touch zero existing call sites" pattern used
     * for every prior field addition to this record. See design_docs/0036's own addendum. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                     boolean seedRatioLimitEnabled, double seedRatioLimit,
                     boolean seedTimeLimitEnabled, long seedTimeLimitMinutes,
                     int eventLogRetentionDays,
                     boolean watchFolderEnabled, int watchFolderRetentionDays,
                     ThemePreference theme,
                     int magnetFetchTimeBudgetSeconds, int magnetFetchCandidatesPerRound,
                     int magnetFetchConcurrencyLimit) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode,
                rateLimitBurstSeconds, seedRatioLimitEnabled, seedRatioLimit, seedTimeLimitEnabled,
                seedTimeLimitMinutes, eventLogRetentionDays, watchFolderEnabled, watchFolderRetentionDays, theme,
                magnetFetchTimeBudgetSeconds, magnetFetchCandidatesPerRound, magnetFetchConcurrencyLimit, 0);
    }

    /** Same as the canonical constructor above but without the three magnetFetch* fields - for
     * every caller that predates this addition (every secondary constructor below, plus any
     * direct nineteen-arg caller), defaulting all three (the compact constructor above
     * normalizes 0 to each real default, so passing 0 here is equivalent to passing the
     * default explicitly). Same "add a sibling overload, touch zero existing call sites"
     * pattern used for every prior field addition to this record. See design_docs/0028's
     * addendum. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                     boolean seedRatioLimitEnabled, double seedRatioLimit,
                     boolean seedTimeLimitEnabled, long seedTimeLimitMinutes,
                     int eventLogRetentionDays,
                     boolean watchFolderEnabled, int watchFolderRetentionDays,
                     ThemePreference theme) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode,
                rateLimitBurstSeconds, seedRatioLimitEnabled, seedRatioLimit, seedTimeLimitEnabled,
                seedTimeLimitMinutes, eventLogRetentionDays, watchFolderEnabled, watchFolderRetentionDays, theme,
                0, 0, 0);
    }

    /** Same as the canonical constructor above but without theme - for every caller that
     * predates the theme switcher's addition of it (every secondary constructor below, plus any
     * direct eighteen-arg caller, e.g. WatchFolderTest's settingsWithWatchFolder()), defaulting to
     * ThemePreference.SYSTEM. Same "add a sibling overload, touch zero existing call sites"
     * pattern used for every prior field addition to this record. See design_docs/0032's "Manual theme switcher" section. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                     boolean seedRatioLimitEnabled, double seedRatioLimit,
                     boolean seedTimeLimitEnabled, long seedTimeLimitMinutes,
                     int eventLogRetentionDays,
                     boolean watchFolderEnabled, int watchFolderRetentionDays) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode,
                rateLimitBurstSeconds, seedRatioLimitEnabled, seedRatioLimit, seedTimeLimitEnabled,
                seedTimeLimitMinutes, eventLogRetentionDays, watchFolderEnabled, watchFolderRetentionDays,
                ThemePreference.SYSTEM);
    }

    /** Same as the canonical constructor above but without watchFolderEnabled/
     * watchFolderRetentionDays - for every caller that predates the watch folder's addition
     * (every secondary constructor below, plus any direct sixteen-arg caller), defaulting to
     * disabled/DEFAULT_WATCH_FOLDER_RETENTION_DAYS. Same "add a sibling overload, touch zero
     * existing call sites" pattern used for every prior field addition to this record. See
     * design_docs/0056. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                     boolean seedRatioLimitEnabled, double seedRatioLimit,
                     boolean seedTimeLimitEnabled, long seedTimeLimitMinutes,
                     int eventLogRetentionDays) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode,
                rateLimitBurstSeconds, seedRatioLimitEnabled, seedRatioLimit, seedTimeLimitEnabled,
                seedTimeLimitMinutes, eventLogRetentionDays, false, DEFAULT_WATCH_FOLDER_RETENTION_DAYS);
    }

    /** Same as the canonical constructor above but without eventLogRetentionDays or
     * watchFolderEnabled/watchFolderRetentionDays - for every caller that predates library
     * events' addition (every secondary constructor below, plus the two tests that construct
     * the previously-canonical fifteen-arg form directly), defaulting to
     * DEFAULT_EVENT_LOG_RETENTION_DAYS/disabled/DEFAULT_WATCH_FOLDER_RETENTION_DAYS. Same "add
     * a sibling overload, touch zero existing call sites" pattern used for every prior field
     * addition to this record. See design_docs/0055. */
    public Settings(boolean dhtEnabled, boolean acceptIncomingConnections,
                     long uploadRateLimitBytesPerSec, long downloadRateLimitBytesPerSec,
                     boolean rateLimitScheduleEnabled, String rateLimitScheduleStart, String rateLimitScheduleEnd,
                     long scheduledUploadRateLimitBytesPerSec, long scheduledDownloadRateLimitBytesPerSec,
                     EncryptionMode encryptionMode, long rateLimitBurstSeconds,
                     boolean seedRatioLimitEnabled, double seedRatioLimit,
                     boolean seedTimeLimitEnabled, long seedTimeLimitMinutes) {
        this(dhtEnabled, acceptIncomingConnections, uploadRateLimitBytesPerSec, downloadRateLimitBytesPerSec,
                rateLimitScheduleEnabled, rateLimitScheduleStart, rateLimitScheduleEnd,
                scheduledUploadRateLimitBytesPerSec, scheduledDownloadRateLimitBytesPerSec, encryptionMode,
                rateLimitBurstSeconds, seedRatioLimitEnabled, seedRatioLimit, seedTimeLimitEnabled,
                seedTimeLimitMinutes, DEFAULT_EVENT_LOG_RETENTION_DAYS);
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
