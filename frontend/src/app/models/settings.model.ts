/** Matches the backend's EncryptionMode enum (grimtorrenter-engine, mse package) - a plain
 * string-literal union, not a TS enum, same convention already used for TorrentState. See
 * design_docs/0052. */
export type EncryptionMode = 'DISABLED' | 'PREFERRED' | 'REQUIRED';

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
 * treatment as eventLogRetentionDays, for the same reason. */
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
}
