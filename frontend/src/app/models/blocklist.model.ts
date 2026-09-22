/** Matches the backend's Blocklist.Status record (GET /api/blocklist). `enabled` reflects the
 * live setting; `rangeCount` is how many merged IP ranges are being enforced right now;
 * `blockedCount` is addresses refused since the backend started. See design_docs/0078. */
export interface BlocklistStatus {
  enabled: boolean;
  source: string;
  rangeCount: number;
  /** Epoch millis of the last successful load, 0 if none yet. */
  loadedAtEpochMillis: number;
  lastError: string | null;
  loading: boolean;
  blockedCount: number;
}
