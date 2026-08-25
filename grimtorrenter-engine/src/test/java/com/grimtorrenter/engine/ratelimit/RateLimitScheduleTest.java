package com.grimtorrenter.engine.ratelimit;

import com.grimtorrenter.engine.settings.Settings;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitScheduleTest {

    private static Settings scheduled(String start, String end, long scheduledUpload, long scheduledDownload) {
        return new Settings(true, true, 1000, 2000, true, start, end, scheduledUpload, scheduledDownload);
    }

    @Test
    void disabledScheduleAlwaysUsesTheBaseLimitRegardlessOfTime() {
        Settings settings = new Settings(true, true, 1000, 2000, false, "23:00", "07:00", 9999, 9999);

        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(1, 0)));
        assertEquals(2000, RateLimitSchedule.effectiveDownloadLimit(settings, LocalTime.of(1, 0)));
    }

    @Test
    void nonWrappingWindowUsesScheduledLimitOnlyInsideIt() {
        Settings settings = scheduled("09:00", "17:00", 5000, 6000);

        assertEquals(5000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(12, 0)));
        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(8, 59)));
        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(17, 0)));
    }

    @Test
    void windowStartIsInclusiveAndEndIsExclusive() {
        Settings settings = scheduled("09:00", "17:00", 5000, 6000);

        assertEquals(5000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(9, 0)));
        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(17, 0)));
    }

    @Test
    void windowCrossingMidnightWrapsCorrectly() {
        Settings settings = scheduled("23:00", "07:00", 5000, 6000);

        assertEquals(5000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(23, 30)));
        assertEquals(5000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(1, 0)));
        assertEquals(5000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(6, 59)));
        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(7, 0)));
        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(12, 0)));
    }

    @Test
    void zeroLengthWindowIsNeverActive() {
        Settings settings = scheduled("09:00", "09:00", 5000, 6000);

        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(9, 0)));
        assertEquals(1000, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(0, 0)));
    }

    @Test
    void scheduledLimitCanBeLowerThanTheBaseLimit() {
        Settings settings = scheduled("09:00", "17:00", 100, 200);

        assertEquals(100, RateLimitSchedule.effectiveUploadLimit(settings, LocalTime.of(12, 0)));
        assertEquals(200, RateLimitSchedule.effectiveDownloadLimit(settings, LocalTime.of(12, 0)));
    }
}
