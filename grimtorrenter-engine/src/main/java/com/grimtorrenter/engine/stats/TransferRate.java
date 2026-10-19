package com.grimtorrenter.engine.stats;

/**
 * One direction's rates as of the last sampling tick - what TransferMeter publishes. See
 * design_docs/0102.
 *
 * @param bytesPerSec        "how fast right now": the mean of the last
 *                           {@link TransferMeter#RATE_SECONDS} seconds. Reads 0 once that many
 *                           seconds pass with nothing moved.
 * @param averageBytesPerSec "how fast on average": the mean of the last
 *                           {@link TransferMeter#TREND_SECONDS} seconds, or of every second since
 *                           the meter was last cleared if that's fewer. What ETA divides by.
 * @param samples            bytes/sec for each second recorded, oldest first, up to
 *                           {@link TransferMeter#TREND_SECONDS}. Never mutated after publishing.
 * @param atEpochMillis      when the newest sample was taken; 0 before the first.
 */
public record TransferRate(long bytesPerSec, long averageBytesPerSec, long[] samples, long atEpochMillis) {

    private static final long[] NO_SAMPLES = new long[0];

    public static final TransferRate IDLE = new TransferRate(0, 0, NO_SAMPLES, 0);

    /** The samples for a sparkline, or none if nothing moved in them - an idle torrent sends an
     * empty trend rather than sixty zeros. */
    public long[] recentTrend() {
        for (long sample : samples) {
            if (sample > 0) {
                return samples;
            }
        }
        return NO_SAMPLES;
    }
}
