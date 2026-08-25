package com.grimtorrenter.engine.tracker;

import java.time.Instant;
import java.util.List;

/**
 * Wraps a single tracker client (HttpTrackerClient/UdpTrackerClient) and records a
 * TrackerStatus snapshot on every announce() call, without changing announce()'s own
 * behavior at all - success/failure still returns/throws exactly as the delegate would, so
 * MultiTrackerClient's existing tier-fallback logic needs no changes. One instance is
 * expected to live for a torrent session's whole lifetime, same as the delegate it wraps.
 * See design_docs/0031.
 */
public final class TrackedTrackerClient implements TrackerClient {

    private final String url;
    private final int tier;
    private final TrackerClient delegate;
    private volatile TrackerStatus status;

    public TrackedTrackerClient(String url, int tier, TrackerClient delegate) {
        this.url = url;
        this.tier = tier;
        this.delegate = delegate;
        this.status = TrackerStatus.initial(url, tier);
    }

    @Override
    public TrackerResponse announce(TrackerRequest request) {
        try {
            TrackerResponse response = delegate.announce(request);
            recordSuccess(response);
            return response;
        } catch (TrackerException e) {
            recordFailure(e);
            throw e;
        }
    }

    @Override
    public List<TrackerStatus> statuses() {
        return List.of(status);
    }

    private void recordSuccess(TrackerResponse response) {
        Instant now = Instant.now();
        status = new TrackerStatus(url, tier, TrackerStatus.State.WORKING, now,
                now.plusSeconds(response.interval()), null, response.complete(), response.incomplete());
    }

    private void recordFailure(TrackerException e) {
        TrackerStatus previous = status;
        status = new TrackerStatus(url, tier, TrackerStatus.State.ERROR, Instant.now(), null,
                e.getMessage(), previous.seeders(), previous.leechers());
    }
}
