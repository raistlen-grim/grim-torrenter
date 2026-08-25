package com.grimtorrenter.app;

import com.grimtorrenter.engine.tracker.TrackerStatus;

import java.time.Instant;

public record TrackerView(
        String url,
        int tier,
        String status,
        Instant lastAnnouncedAt,
        Instant nextAnnounceAt,
        String lastError,
        Integer seeders,
        Integer leechers
) {
    public static TrackerView from(TrackerStatus status) {
        return new TrackerView(status.url(), status.tier(), status.state().name(), status.lastAnnouncedAt(),
                status.nextAnnounceAt(), status.lastError(), status.seeders(), status.leechers());
    }
}
