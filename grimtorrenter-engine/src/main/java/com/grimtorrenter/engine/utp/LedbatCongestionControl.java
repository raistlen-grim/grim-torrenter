package com.grimtorrenter.engine.utp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * RFC 6817's LEDBAT delay-based congestion control law - design_docs/0074's slice 5, replacing
 * the interim fixed send-window cap. Deliberately standalone/pure: no threads, no sockets, no
 * dependency on {@link UtpSocket}'s own locking - just numeric state, so the control law's
 * actual behavior can be unit-tested directly with synthetic delay samples and synthetic
 * timestamps, the same reasoning that already keeps {@code UtpPacketCodec}/{@code RateLimiter}
 * independently testable elsewhere in this codebase.
 *
 * <p>Base delay is tracked as the minimum one-way-delay sample observed within a sliding
 * {@link #BASE_DELAY_WINDOW_MILLIS} window - old samples age out so a genuine path change (a
 * new, higher no-queue delay) is eventually reflected rather than pinned to a stale minimum
 * forever.
 *
 * <p>Deliberately deferred (design_docs/0074's own "not a hard requirement for parity" call on
 * extras): selective-ack-driven loss detection (the only loss signal here is {@link #onLoss()},
 * called on an RTO firing) and any distinguished slow-start ramp phase - this control law alone
 * still grows the window, just linearly rather than exponentially, which is a real, intended
 * part of LEDBAT being less aggressive than TCP, not a missing feature.
 */
final class LedbatCongestionControl {

    private static final long TARGET_DELAY_MICROS = 100_000; // RFC 6817's own recommended 100ms
    private static final long BASE_DELAY_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final double GAIN = 1.0; // ~1 MSS/RTT growth when fully under target
    private static final long INITIAL_CWND_BYTES = 3000; // ~2 segments, same order libutp starts at

    /** A remote peer controls every timestamp field it sends (design_docs/0074's own Stability
     * section flagged this ahead of time as a slice-5 concern) - a delay sample outside this
     * range is treated as noise, not trusted, rather than letting it poison base-delay tracking
     * (e.g. a spoofed near-zero delay would make every real future sample look like permanent
     * congestion, throttling this connection to nothing). */
    private static final long MAX_PLAUSIBLE_DELAY_MICROS = TimeUnit.SECONDS.toMicros(10);

    private final long mssBytes;
    private final long minCwndBytes;
    private final Deque<DelaySample> recentDelays = new ArrayDeque<>();
    private double cwndBytes = INITIAL_CWND_BYTES;

    LedbatCongestionControl(long mssBytes) {
        this.mssBytes = mssBytes;
        this.minCwndBytes = mssBytes; // never below one full segment - avoids a real deadlock
    }

    /** Feeds one one-way-delay sample into the sliding base-delay window. Must be called before
     * {@link #onBytesAcked} for the same packet - the very first-ever sample then becomes its
     * own base delay (queuing_delay=0, off_target=1, full growth) rather than spuriously reading
     * an empty/zero base delay before any real sample exists. */
    synchronized void onDelaySample(long delayMicros, long nowMillis) {
        if (delayMicros < 0 || delayMicros > MAX_PLAUSIBLE_DELAY_MICROS) {
            return; // implausible - almost certainly a clock issue or a hostile peer, ignore it
        }
        recentDelays.addLast(new DelaySample(nowMillis, delayMicros));
        while (!recentDelays.isEmpty()
                && nowMillis - recentDelays.peekFirst().atMillis() > BASE_DELAY_WINDOW_MILLIS) {
            recentDelays.removeFirst();
        }
    }

    /** RFC 6817 section 3.3's own control law, verbatim: {@code cwnd += gain * off_target *
     * bytes_newly_acked * MSS / cwnd}. Called once per ack that newly acknowledges more than
     * zero bytes. currentDelayMicros is the delay sample carried by that same ack - the caller
     * must have already fed it to {@link #onDelaySample} first. */
    synchronized void onBytesAcked(int bytesAcked, long currentDelayMicros) {
        long baseDelay = baseDelayMicros();
        long queuingDelay = Math.max(0, currentDelayMicros - baseDelay);
        double offTarget = clamp((TARGET_DELAY_MICROS - queuingDelay) / (double) TARGET_DELAY_MICROS);
        double delta = GAIN * offTarget * bytesAcked * mssBytes / cwndBytes;
        cwndBytes = Math.max(minCwndBytes, cwndBytes + delta);
    }

    /** The one loss signal this implementation has (no selective ack/fast retransmit yet, see
     * this class's own Javadoc) - an RTO firing. RFC 6817 requires LEDBAT flows to also react to
     * real loss, not just delay - a straightforward per-event halving, floored, the same order
     * of magnitude as TCP Reno's own multiplicative decrease. */
    synchronized void onLoss() {
        cwndBytes = Math.max(minCwndBytes, cwndBytes / 2.0);
    }

    synchronized long cwndBytes() {
        return (long) cwndBytes;
    }

    private long baseDelayMicros() {
        long min = Long.MAX_VALUE;
        for (DelaySample sample : recentDelays) {
            min = Math.min(min, sample.delayMicros());
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    private static double clamp(double offTarget) {
        return Math.max(-1.0, Math.min(1.0, offTarget));
    }

    private record DelaySample(long atMillis, long delayMicros) {
    }
}
