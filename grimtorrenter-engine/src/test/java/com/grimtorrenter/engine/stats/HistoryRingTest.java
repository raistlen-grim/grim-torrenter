package com.grimtorrenter.engine.stats;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryRingTest {

    private static final long MINUTE = 60_000;

    @Test
    void averagesEachIntervalOverTheTimeItCovered() {
        HistoryRing ring = new HistoryRing(MINUTE, 3);
        // Two one-second ticks in minute 10: 1,000 and 3,000 bytes over 2 s is 2,000 B/s.
        ring.add(1_000, 10, 1_000, 10 * MINUTE + 1_000);
        ring.add(3_000, 30, 1_000, 10 * MINUTE + 2_000);

        TransferHistory history = ring.snapshot(10 * MINUTE + 5_000);

        assertEquals(Arrays.asList(null, null, 2_000L), history.download());
        assertEquals(Arrays.asList(null, null, 20L), history.upload());
        assertEquals(11 * MINUTE, history.endEpochMillis());
        assertEquals(MINUTE, history.intervalMillis());
    }

    @Test
    void intervalsNobodyWroteToAreGaps() {
        HistoryRing ring = new HistoryRing(MINUTE, 4);
        ring.add(60_000, 0, 60_000, 10 * MINUTE);
        ring.add(120_000, 0, 60_000, 12 * MINUTE);

        assertEquals(Arrays.asList(null, 1_000L, null, 2_000L), ring.snapshot(12 * MINUTE).download());
    }

    @Test
    void anOldIntervalInTheSameSlotIsReplacedNotAddedTo() {
        HistoryRing ring = new HistoryRing(MINUTE, 2);
        ring.add(60_000, 0, 60_000, 10 * MINUTE);
        // Minute 12 lands in minute 10's slot.
        ring.add(6_000, 0, 60_000, 12 * MINUTE);

        assertEquals(Arrays.asList(null, 100L), ring.snapshot(12 * MINUTE).download());
    }

    @Test
    void intervalsOutsideTheWindowAreNotShown() {
        HistoryRing ring = new HistoryRing(MINUTE, 2);
        ring.add(60_000, 0, 60_000, 10 * MINUTE);

        assertEquals(Arrays.asList(null, null), ring.snapshot(20 * MINUTE).download());
    }

    @Test
    void restoreRoundTripsAndTheNewerIntervalWins() {
        HistoryRing ring = new HistoryRing(MINUTE, 2);
        ring.add(60_000, 6_000, 60_000, 10 * MINUTE);

        HistoryRing restored = new HistoryRing(MINUTE, 2);
        // 8 and 10 share a slot; 10 is newer whichever order they come in.
        restored.restore(List.of(new HistoryRing.Interval(8, 999, 999, 1_000)));
        restored.restore(ring.intervals());
        restored.restore(List.of(new HistoryRing.Interval(8, 999, 999, 1_000)));

        TransferHistory history = restored.snapshot(10 * MINUTE);
        assertEquals(Arrays.asList(null, 1_000L), history.download());
        assertEquals(Arrays.asList(null, 100L), history.upload());
    }

    @Test
    void anIntervalWithNoCoveredTimeIsAGap() {
        HistoryRing ring = new HistoryRing(MINUTE, 1);
        ring.add(500, 0, 0, 10 * MINUTE);

        assertEquals(Arrays.asList((Long) null), ring.snapshot(10 * MINUTE).download());
    }
}
