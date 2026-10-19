package com.grimtorrenter.engine.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A window of transfer history at one resolution, oldest first. Each value is the average
 * bytes/sec over that interval; null where nothing was recorded (the app wasn't running, or the
 * torrent didn't exist yet), so a graph can show a gap rather than a false zero. The last value
 * covers the interval still in progress. See design_docs/0102.
 *
 * @param endEpochMillis where the window ends - the end of the newest interval
 */
public record TransferHistory(long intervalMillis, long endEpochMillis, List<Long> download, List<Long> upload) {

    /** The per-second history straight from two meters' samples - a full
     * {@link TransferMeter#TREND_SECONDS} window, with nulls before the first sample. */
    public static TransferHistory ofSeconds(TransferRate download, TransferRate upload, long nowMillis) {
        long end = download.atEpochMillis() != 0 ? download.atEpochMillis() : nowMillis;
        return new TransferHistory(1000, end, padded(download.samples()), padded(upload.samples()));
    }

    private static List<Long> padded(long[] samples) {
        List<Long> values = new ArrayList<>(TransferMeter.TREND_SECONDS);
        for (int i = samples.length; i < TransferMeter.TREND_SECONDS; i++) {
            values.add(null);
        }
        for (long sample : samples) {
            values.add(sample);
        }
        return Collections.unmodifiableList(values);
    }
}
