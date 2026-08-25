package com.grimtorrenter.engine.tracker;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A TrackerClient for a torrent with no trackers at all - e.g. a trackerless magnet
 * relying entirely on DHT for peer discovery (see design_docs/0028). announce() always
 * succeeds with zero peers rather than throwing, so TorrentSession's start()/reannounce()
 * cycle - built around always having a real TrackerClient - needs no null-tracker special
 * case.
 */
public final class NoOpTrackerClient implements TrackerClient {

    /** Not Long.MAX_VALUE - that overflows when the scheduler converts it to nanoseconds.
     * A reannounce here would only ever repeat the same zero-peers result anyway, so
     * "effectively never" (a year) is as good as truly never. */
    private static final long INTERVAL_SECONDS = TimeUnit.DAYS.toSeconds(365);

    @Override
    public TrackerResponse announce(TrackerRequest request) {
        return new TrackerResponse(INTERVAL_SECONDS, null, 0, 0, List.of(), null, null);
    }
}
