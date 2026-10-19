/** second, minute or hour; a torrent has no hourly history. See design_docs/0102. */
export type HistoryResolution = 'second' | 'minute' | 'hour';

/** Matches the backend's HistoryView: average bytes/sec per interval, oldest first, null where
 * nothing was recorded (the app wasn't running, or the torrent didn't exist yet). The last value
 * is the interval still in progress. See design_docs/0102. */
export interface TransferHistory {
  resolution: HistoryResolution;
  intervalSeconds: number;
  /** Epoch millis where the window ends - the end of the newest interval. */
  endEpochMillis: number;
  download: (number | null)[];
  upload: (number | null)[];
}
