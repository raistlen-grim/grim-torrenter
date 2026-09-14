package com.grimtorrenter.engine.utp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests against synthetic delay samples/timestamps - no sockets, no threads - design_docs/
 * 0074's slice 5 own Testing section. Each test constructs its own instance (1400, matching
 * UtpSocket's own MAX_PAYLOAD_LENGTH) since state isn't reset between calls. */
class LedbatCongestionControlTest {

    private static final long MSS = 1400;

    @Test
    void growsTheWindowWhenDelayStaysAtBaseline() {
        LedbatCongestionControl control = new LedbatCongestionControl(MSS);
        long initialCwnd = control.cwndBytes();
        long now = 0;
        long stableDelayMicros = 20_000; // well under TARGET_DELAY_MICROS once it's also the base

        // Repeatedly "ack a full window" at a stable delay - off_target stays ~1 once this same
        // delay has become the base (see onDelaySample()'s own bootstrap note), so cwnd should
        // grow every round rather than plateau or shrink.
        long cwnd = initialCwnd;
        for (int round = 0; round < 20; round++) {
            now += 50; // advances the synthetic clock; nowhere near BASE_DELAY_WINDOW_MILLIS
            control.onDelaySample(stableDelayMicros, now);
            control.onBytesAcked((int) cwnd, stableDelayMicros);
            long newCwnd = control.cwndBytes();
            assertTrue(newCwnd >= cwnd, "cwnd shrank on round " + round + ": " + cwnd + " -> " + newCwnd);
            cwnd = newCwnd;
        }
        assertTrue(cwnd > initialCwnd, "cwnd never grew past its initial value: " + cwnd);
    }

    @Test
    void shrinksTheWindowWhenQueuingDelayExceedsTarget() {
        LedbatCongestionControl control = new LedbatCongestionControl(MSS);
        // Establish a low base delay first...
        control.onDelaySample(10_000, 0);
        control.onBytesAcked((int) control.cwndBytes(), 10_000);
        long cwndAfterBaseline = control.cwndBytes();

        // ...then a much higher delay (well over TARGET_DELAY_MICROS of queuing) should read as
        // real queuing against that established base and shrink the window.
        long highDelayMicros = 10_000 + 500_000;
        control.onDelaySample(highDelayMicros, 100);
        control.onBytesAcked((int) cwndAfterBaseline, highDelayMicros);

        assertTrue(control.cwndBytes() < cwndAfterBaseline,
                "cwnd did not shrink under a large queuing delay: " + control.cwndBytes());
    }

    @Test
    void onLossHalvesTheWindowFlooredAtOneSegment() {
        LedbatCongestionControl control = new LedbatCongestionControl(MSS);
        control.onDelaySample(10_000, 0);
        control.onBytesAcked((int) control.cwndBytes(), 10_000); // grow it a bit past the floor first
        long beforeLoss = control.cwndBytes();

        control.onLoss();
        assertEquals(Math.max(MSS, beforeLoss / 2), control.cwndBytes());

        // Repeated loss events never push it below one full segment.
        for (int i = 0; i < 20; i++) {
            control.onLoss();
        }
        assertEquals(MSS, control.cwndBytes());
    }

    @Test
    void baseDelayForgetsSamplesOnceTheyAgeOutOfTheWindow() {
        LedbatCongestionControl control = new LedbatCongestionControl(MSS);
        long earlyLowDelay = 5_000;
        control.onDelaySample(earlyLowDelay, 0);
        control.onBytesAcked((int) control.cwndBytes(), earlyLowDelay); // baseline = 5ms, off_target ~1

        long laterHigherDelay = 5_000 + 50_000; // 50ms of queuing against the 5ms base - still under target
        long cwndWithOldBaseline = control.cwndBytes();
        control.onDelaySample(laterHigherDelay, 1000);
        control.onBytesAcked((int) cwndWithOldBaseline, laterHigherDelay);
        long cwndBeforeAgingOut = control.cwndBytes();

        // Advance the synthetic clock well past BASE_DELAY_WINDOW_MILLIS (2 minutes) - the early
        // 5ms sample should now be forgotten, leaving the 55ms sample as the new base delay, so
        // the exact same delay sample now reads as ~0 queuing (off_target back near 1) instead of
        // ~50ms of queuing - i.e. cwnd grows at least as fast now as it did while the old, lower
        // base delay was still artificially inflating the perceived queuing delay.
        long muchLater = 1000 + TimeUnit.MINUTES.toMillis(3);
        control.onDelaySample(laterHigherDelay, muchLater);
        double deltaWithOldBase = cwndBeforeAgingOut - cwndWithOldBaseline;
        control.onBytesAcked((int) cwndBeforeAgingOut, laterHigherDelay);
        double deltaWithForgottenBase = control.cwndBytes() - cwndBeforeAgingOut;

        assertTrue(deltaWithForgottenBase > deltaWithOldBase,
                "aging out the old low base delay should make the same sample look less congested, "
                        + "not more: delta with stale base=" + deltaWithOldBase
                        + ", delta after forgetting it=" + deltaWithForgottenBase);
    }

    @Test
    void ignoresImplausibleDelaySamples() {
        LedbatCongestionControl control = new LedbatCongestionControl(MSS);
        control.onDelaySample(-1, 0); // negative - implausible
        control.onDelaySample(TimeUnit.SECONDS.toMicros(30), 0); // far past the 10s ceiling

        long cwndBefore = control.cwndBytes();
        // Neither implausible sample should have entered the base-delay window - acking against
        // a real, small delay should still read as "at/under target" (off_target > 0, grows),
        // not poisoned toward "no queuing possible, everything looks congested."
        control.onDelaySample(10_000, 1);
        control.onBytesAcked((int) cwndBefore, 10_000);
        assertTrue(control.cwndBytes() >= cwndBefore);
    }
}
