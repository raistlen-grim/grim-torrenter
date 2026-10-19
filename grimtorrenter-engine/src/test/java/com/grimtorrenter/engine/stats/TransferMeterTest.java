package com.grimtorrenter.engine.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TransferMeterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void readsIdleBeforeAnything() {
        assertSame(TransferRate.IDLE, new TransferMeter().current());
    }

    @Test
    void rateIsTheMeanOfTheLastFiveSeconds() {
        TransferMeter meter = new TransferMeter();
        for (long bytes : new long[] {100, 100, 100, 600, 600, 600, 600, 600}) {
            meter.record(bytes, SECOND, 1);
        }
        assertEquals(600, meter.current().bytesPerSec());
    }

    @Test
    void aYoungMeterAveragesWhatItHas() {
        TransferMeter meter = new TransferMeter();
        meter.record(300, SECOND, 1);
        meter.record(100, SECOND, 2);

        assertEquals(200, meter.current().bytesPerSec());
        assertEquals(200, meter.current().averageBytesPerSec());
        assertArrayEquals(new long[] {300, 100}, meter.current().samples());
        assertEquals(2, meter.current().atEpochMillis());
    }

    @Test
    void readsZeroOnceFiveIdleSecondsPass() {
        TransferMeter meter = new TransferMeter();
        meter.record(1_000_000, SECOND, 0);
        for (int i = 0; i < TransferMeter.RATE_SECONDS; i++) {
            meter.record(0, SECOND, 0);
        }
        assertEquals(0, meter.current().bytesPerSec());
        // The ETA average still remembers the burst.
        assertEquals(1_000_000 / (TransferMeter.RATE_SECONDS + 1), meter.current().averageBytesPerSec());
    }

    @Test
    void averageCoversTheLastSixtySecondsOnly() {
        TransferMeter meter = new TransferMeter();
        meter.record(6_000, SECOND, 0);
        for (int i = 0; i < TransferMeter.TREND_SECONDS; i++) {
            meter.record(60, SECOND, 0);
        }
        assertEquals(60, meter.current().averageBytesPerSec());
        assertEquals(TransferMeter.TREND_SECONDS, meter.current().samples().length);
    }

    @Test
    void aLateTickReadsAsAnAverageNotASpike() {
        TransferMeter meter = new TransferMeter();
        meter.record(10_000, 10 * SECOND, 0);
        assertEquals(1_000, meter.current().bytesPerSec());
    }

    @Test
    void aCounterGoingBackwardsRecordsZero() {
        TransferMeter meter = new TransferMeter();
        meter.record(-500, SECOND, 0);
        assertEquals(0, meter.current().bytesPerSec());
    }

    @Test
    void clearForgetsEverything() {
        TransferMeter meter = new TransferMeter();
        meter.record(500, SECOND, 0);
        meter.clear();
        assertSame(TransferRate.IDLE, meter.current());

        meter.record(100, SECOND, 0);
        assertArrayEquals(new long[] {100}, meter.current().samples());
    }

    @Test
    void recentTrendIsEmptyWhenNothingMoved() {
        TransferMeter meter = new TransferMeter();
        meter.record(0, SECOND, 0);
        meter.record(0, SECOND, 0);
        assertEquals(0, meter.current().recentTrend().length);

        meter.record(5, SECOND, 0);
        assertArrayEquals(new long[] {0, 0, 5}, meter.current().recentTrend());
    }

    @Test
    void secondHistoryPadsTheFrontWithGaps() {
        TransferMeter download = new TransferMeter();
        TransferMeter upload = new TransferMeter();
        download.record(7, SECOND, 5_000);
        upload.record(3, SECOND, 5_000);

        TransferHistory history = TransferHistory.ofSeconds(download.current(), upload.current(), 9_999);

        assertEquals(1000, history.intervalMillis());
        assertEquals(5_000, history.endEpochMillis());
        assertEquals(TransferMeter.TREND_SECONDS, history.download().size());
        assertEquals(null, history.download().get(0));
        assertEquals(7L, history.download().get(TransferMeter.TREND_SECONDS - 1));
        assertEquals(3L, history.upload().get(TransferMeter.TREND_SECONDS - 1));
    }
}
