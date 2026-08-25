interface Reading {
  value: number;
  timestampMs: number;
}

/** Label -> window length, e.g. { '5s': 5_000, '15s': 15_000, '60s': 60_000 }. */
export type RateWindows = Record<string, number>;

export interface RateSnapshot {
  /** The rate for whichever window was configured as primary - the one number shown
   * inline (e.g. in a list row), everything else being secondary/tooltip material. */
  current: number;
  /** Every tracked window's rate, keyed by the same labels as the configured windows -
   * for a secondary display (a tooltip) that can surface a short-window rate well below
   * the long-window one as a likely recent stall or interruption. */
  byWindow: Record<string, number>;
}

const ZERO_SNAPSHOT: RateSnapshot = { current: 0, byWindow: {} };

/**
 * Tracks a rolling window of cumulative-value readings per key and derives a smoothed
 * rate (value/sec) for each of several configured time windows, rather than a raw
 * two-sample delta - a single-delta rate is noisy (one unusually slow or fast tick swings
 * the displayed number), a window average is what real torrent clients show. One bounded
 * reading history per key backs every window - no separate history is kept per window,
 * each window's rate is just (newest - oldest-within-that-window) / elapsed.
 *
 * <p>Framework-agnostic (not an Angular service) - a caller that needs session-scoped
 * tracking (TorrentEventsService, one shared instance for the app's lifetime) and a
 * caller that needs a shorter-lived, component-scoped one (a Peers tab, torn down when
 * its torrent's detail view closes) each own their own instance rather than sharing a
 * single global keyspace with no natural cleanup point.
 */
export class RateTracker {
  private readonly maxWindowMs: number;
  private readonly historyByKey = new Map<string, Reading[]>();

  constructor(
    private readonly windows: RateWindows,
    private readonly primaryLabel: string,
  ) {
    this.maxWindowMs = Math.max(...Object.values(windows));
  }

  /** Records a new cumulative reading for key and returns the resulting rate snapshot.
   * value must only ever increase for a given key (matches how bytesDownloaded/
   * bytesUploaded behave) - a lower value than a previous reading isn't rejected, but
   * produces a clamped-to-zero rate for any window it ends up as the newest point of. */
  record(key: string, value: number, timestampMs = Date.now()): RateSnapshot {
    const history = this.historyByKey.get(key) ?? [];
    history.push({ value, timestampMs });
    // Trim to maxWindowMs, but always keep at least one reading so the next call has
    // something to measure from.
    while (history.length > 1 && timestampMs - history[0].timestampMs > this.maxWindowMs) {
      history.shift();
    }
    this.historyByKey.set(key, history);
    return this.snapshotFrom(history, timestampMs);
  }

  /** Stops tracking key - for a peer that disconnected or a torrent that was removed, so
   * its history doesn't linger forever. */
  delete(key: string): void {
    this.historyByKey.delete(key);
  }

  private snapshotFrom(history: Reading[], now: number): RateSnapshot {
    if (history.length < 2) {
      return ZERO_SNAPSHOT;
    }
    const newest = history[history.length - 1];
    const byWindow: Record<string, number> = {};
    for (const [label, windowMs] of Object.entries(this.windows)) {
      const cutoff = now - windowMs;
      const oldest = history.find((reading) => reading.timestampMs >= cutoff) ?? history[0];
      const elapsedSeconds = (now - oldest.timestampMs) / 1000;
      byWindow[label] = elapsedSeconds > 0 ? Math.max(0, (newest.value - oldest.value) / elapsedSeconds) : 0;
    }
    return { current: byWindow[this.primaryLabel] ?? 0, byWindow };
  }
}
