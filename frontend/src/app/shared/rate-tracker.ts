interface Reading {
  value: number;
  timestampMs: number;
}

export interface RateSnapshot {
  /** The smoothed rate (value/sec) over this tracker's primary window - the one number
   * shown inline (e.g. in a list row). */
  current: number;
  /** Instantaneous rate (value/sec) between each consecutive pair of readings kept in this
   * key's history, oldest first - covers up to TREND_WINDOW_MS of history, however many
   * readings actually landed in that span. Feeds a trend sparkline (a secondary display) -
   * not a fixed-interval sample buffer, since piggybacking on the same history already kept
   * for `current` needs no separate timer/buffer of its own. */
  trend: number[];
}

const ZERO_SNAPSHOT: RateSnapshot = { current: 0, trend: [] };

/** How far back history is kept regardless of primaryWindowMs - long enough to back a "last
 * 60s" trend sparkline even when the primary window itself is shorter. */
const TREND_WINDOW_MS = 60_000;

/**
 * Tracks a rolling window of cumulative-value readings per key and derives a smoothed
 * rate (value/sec) over primaryWindowMs, rather than a raw two-sample delta - a single-delta
 * rate is noisy (one unusually slow or fast tick swings the displayed number), a window
 * average is what real torrent clients show.
 *
 * <p>Framework-agnostic (not an Angular service) - a caller that needs session-scoped
 * tracking (TorrentEventsService, one shared instance for the app's lifetime) and a
 * caller that needs a shorter-lived, component-scoped one (a Peers tab, torn down when
 * its torrent's detail view closes) each own their own instance rather than sharing a
 * single global keyspace with no natural cleanup point.
 */
export class RateTracker {
  private readonly maxHistoryMs: number;
  private readonly historyByKey = new Map<string, Reading[]>();

  constructor(private readonly primaryWindowMs: number) {
    this.maxHistoryMs = Math.max(primaryWindowMs, TREND_WINDOW_MS);
  }

  /** Records a new cumulative reading for key and returns the resulting rate snapshot.
   * value must only ever increase for a given key (matches how bytesDownloaded/
   * bytesUploaded behave) - a lower value than a previous reading isn't rejected, but
   * produces a clamped-to-zero rate for any window it ends up as the newest point of. */
  record(key: string, value: number, timestampMs = Date.now()): RateSnapshot {
    const history = this.historyByKey.get(key) ?? [];
    history.push({ value, timestampMs });
    // Trim to maxHistoryMs, but always keep at least one reading so the next call has
    // something to measure from.
    while (history.length > 1 && timestampMs - history[0].timestampMs > this.maxHistoryMs) {
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
    const cutoff = now - this.primaryWindowMs;
    const oldest = history.find((reading) => reading.timestampMs >= cutoff) ?? history[0];
    const elapsedSeconds = (now - oldest.timestampMs) / 1000;
    const current = elapsedSeconds > 0 ? Math.max(0, (newest.value - oldest.value) / elapsedSeconds) : 0;

    const trend: number[] = [];
    for (let i = 1; i < history.length; i++) {
      const prev = history[i - 1];
      const curr = history[i];
      const stepSeconds = (curr.timestampMs - prev.timestampMs) / 1000;
      trend.push(stepSeconds > 0 ? Math.max(0, (curr.value - prev.value) / stepSeconds) : 0);
    }
    return { current, trend };
  }
}
