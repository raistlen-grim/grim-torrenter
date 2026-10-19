package com.grimtorrenter.engine.stats;

/**
 * Per-second transfer buckets for one direction of one counted thing (a peer connection, a
 * torrent, the whole engine), and the rates derived from them. See design_docs/0102.
 *
 * <p>Single writer: only the engine's 1 s sampling tick calls record() and clear(). Each call
 * publishes an immutable TransferRate through a volatile field, so readers (the REST layer, the
 * WebSocket snapshot) never lock and never see a half-rolled ring.
 *
 * <p>record() takes the bytes moved since the previous tick and how long that actually was, and
 * stores bytes per second. A late tick therefore reads as the right average over its longer
 * interval, not as a spike.
 */
public final class TransferMeter {

    /** How many seconds the sparkline and the ETA average cover. */
    public static final int TREND_SECONDS = 60;
    /** How many seconds the displayed rate averages over - short enough that a stalled torrent
     * reads 0 within a few seconds. */
    public static final int RATE_SECONDS = 5;

    private final long[] ring = new long[TREND_SECONDS];
    /** The slot the next record() writes. Tick thread only, like ring and count. */
    private int next;
    private int count;
    private volatile TransferRate published = TransferRate.IDLE;

    /** Tick thread only. bytes below 0 (a counter that went backwards) records as 0. */
    public void record(long bytes, long elapsedNanos, long nowMillis) {
        long perSecond = elapsedNanos <= 0 ? 0 : (long) (Math.max(0, bytes) * 1_000_000_000.0 / elapsedNanos);
        ring[next] = perSecond;
        next = (next + 1) % TREND_SECONDS;
        count = Math.min(count + 1, TREND_SECONDS);
        published = derive(nowMillis);
    }

    /** Tick thread only. Forgets every sample - for a torrent that stopped, so it reads 0 at once
     * and a resume starts fresh rather than averaging in the time before the pause. */
    public void clear() {
        next = 0;
        count = 0;
        published = TransferRate.IDLE;
    }

    public TransferRate current() {
        return published;
    }

    private TransferRate derive(long nowMillis) {
        long[] samples = new long[count];
        int oldest = Math.floorMod(next - count, TREND_SECONDS);
        long total = 0;
        for (int i = 0; i < count; i++) {
            samples[i] = ring[(oldest + i) % TREND_SECONDS];
            total += samples[i];
        }
        int recent = Math.min(RATE_SECONDS, count);
        long recentTotal = 0;
        for (int i = count - recent; i < count; i++) {
            recentTotal += samples[i];
        }
        return new TransferRate(recentTotal / recent, total / count, samples, nowMillis);
    }
}
