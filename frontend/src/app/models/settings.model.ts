/** Matches the backend's EncryptionMode enum (grimtorrenter-engine, mse package) - a plain
 * string-literal union, not a TS enum, same convention already used for TorrentState. See
 * design_docs/0052. */
export type EncryptionMode = 'DISABLED' | 'PREFERRED' | 'REQUIRED';

/** Matches the backend's ThemePreference enum (grimtorrenter-engine, settings package) - same
 * plain string-literal union convention as EncryptionMode above. SYSTEM means "follow the
 * OS/browser preference," resolved live in ThemeService rather than by this value alone. See
 * design_docs/0032's "Manual theme switcher" section. */
export type ThemePreference = 'SYSTEM' | 'LIGHT' | 'DARK';

/** Matches the backend's Settings record (grimtorrenter-engine, settings package) - see
 * design_docs/0041, design_docs/0042, design_docs/0045, design_docs/0046, design_docs/0052,
 * design_docs/0053, design_docs/0054. Rate limits are in bytes/sec, 0 meaning unlimited; the
 * settings page converts to/from KiB/s for display only, at its own load/save boundary, so
 * this stays the unit of record everywhere else. rateLimitScheduleStart/End are "HH:mm"
 * strings - the same format a native <input type="time"> reads and writes, so no conversion
 * is needed for those either. encryptionMode is live (takes effect on the next connection, no
 * restart), unlike dhtEnabled/acceptIncomingConnections. rateLimitBurstSeconds is also live
 * and applies to whichever limit (base or scheduled) is currently active - 0 (or less) means
 * the backend's original 1-second default, not "no burst" (see Settings.java's own Javadoc
 * for why). seedRatioLimit/seedTimeLimitMinutes are the global seeding-limit defaults - a
 * per-torrent SeedingLimitOverride (see torrent.model.ts) can override either independently;
 * neither survives a process restart (computed from byte counters that already reset on
 * restart today - see Settings.java's own Javadoc). eventLogRetentionDays bounds how many days
 * of library events (see events.model.ts) are kept - live, but only takes effect on the
 * backend's next hourly prune tick, not synchronously; unlike the rate limits, this has no
 * "unlimited" value - the backend silently normalizes anything below 1 to a default of 30
 * rather than rejecting it, since an unbounded event log is exactly what this field exists to
 * prevent. The settings form still enforces a minimum of 1 in its own input so a user never
 * sees that silent substitution happen. See design_docs/0055. watchFolderEnabled/
 * watchFolderRetentionDays govern the watch-folder auto-add feature (see design_docs/0056) -
 * both live, checked fresh on every scan tick; defaults disabled/7 days.
 * watchFolderRetentionDays follows the same no-unlimited-value, silently-normalized-below-1
 * treatment as eventLogRetentionDays, for the same reason. theme is the frontend's light/dark
 * preference (design_docs/0032's "Manual theme switcher" section) - the only field here with
 * no engine relevance at all,
 * stored the same way as everything else anyway (a deliberate choice over browser
 * localStorage, so it follows the user across devices/browsers hitting this instance).
 * magnetFetchTimeBudgetSeconds/magnetFetchCandidatesPerRound/magnetFetchConcurrencyLimit tune
 * how hard a magnet add tries to find a peer with the metadata (design_docs/0028's addendum) -
 * live, read fresh by the backend at the start of each magnet-add attempt, so (like
 * encryptionMode) a change here takes effect on the next attempt, not retroactively. Same
 * no-unlimited-value, silently-normalized-below-1 treatment as eventLogRetentionDays/
 * watchFolderRetentionDays, for the same reason - not a lever for "never give up" or "no
 * concurrency bound at all." Defaults (90s / 50 / 64) work out of the box; exposed as
 * user-editable mainly for an advanced user, or someone actively diagnosing a connectivity
 * issue, to tune without a rebuild/restart.
 * trackerlessDhtReannounceIntervalSeconds (design_docs/0036's own addendum) governs how often
 * a genuinely trackerless torrent re-queries DHT for fresh peers while running, mirroring what
 * a real tracker's own announce interval already does - live, but read once per torrent
 * start() (a live-scheduled task's period can't change mid-flight), so a change here takes
 * effect on that torrent's next start(), not retroactively. Default 300s (5 minutes) -
 * deliberately not much shorter: re-querying DHT for the same info hash too often is poor DHT
 * citizenship, and the real bottleneck behind a slow-growing peer count is usually
 * routing-table richness, not query frequency. Same no-unlimited-value,
 * silently-normalized-below-1 treatment as the other tunable fields above.
 * dhtRefreshIntervalSeconds (design_docs/0028's own 2026-08-30 addendum) is that
 * routing-table-richness fix: how often a background tick re-queries whichever DHT bucket has
 * gone longest without activity, reaching parts of the id space a one-time startup bootstrap
 * lookup never touches. Live, but drives an engine-wide scheduled task rather than a per-torrent
 * one, so a change here takes effect on the engine's next construction/restart, not
 * retroactively. Default 300s (5 minutes) - each tick is one lightweight lookup against a single
 * bucket, so a shorter-than-trackerlessDhtReannounceIntervalSeconds-style default is reasonable
 * DHT etiquette here. Same no-unlimited-value, silently-normalized-below-1 treatment as the
 * other tunable fields above. */
export interface Settings {
  dhtEnabled: boolean;
  acceptIncomingConnections: boolean;
  uploadRateLimitBytesPerSec: number;
  downloadRateLimitBytesPerSec: number;
  rateLimitScheduleEnabled: boolean;
  rateLimitScheduleStart: string;
  rateLimitScheduleEnd: string;
  scheduledUploadRateLimitBytesPerSec: number;
  scheduledDownloadRateLimitBytesPerSec: number;
  encryptionMode: EncryptionMode;
  rateLimitBurstSeconds: number;
  seedRatioLimitEnabled: boolean;
  seedRatioLimit: number;
  seedTimeLimitEnabled: boolean;
  seedTimeLimitMinutes: number;
  eventLogRetentionDays: number;
  watchFolderEnabled: boolean;
  watchFolderRetentionDays: number;
  theme: ThemePreference;
  magnetFetchTimeBudgetSeconds: number;
  magnetFetchCandidatesPerRound: number;
  magnetFetchConcurrencyLimit: number;
  trackerlessDhtReannounceIntervalSeconds: number;
  dhtRefreshIntervalSeconds: number;
}
