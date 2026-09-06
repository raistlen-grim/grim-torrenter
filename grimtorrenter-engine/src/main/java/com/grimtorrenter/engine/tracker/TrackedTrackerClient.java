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
 *
 * <p>Also the sole decision point for TRACKER_UNREACHABLE/TRACKER_RECOVERED library events
 * (design_docs/0055's addendum) - chosen over MultiTrackerClient because this is already the
 * one place with a genuine before/after view of a single tracker's own status;
 * MultiTrackerClient only aggregates statuses this class already decided. Debounced against
 * flapping: each announce() already survives its own transport-level retries
 * (UdpTrackerClient/HttpTrackerClient) before ever throwing, but a tracker can still fail one
 * reannounce cycle and recover the next (a restart, a brief overload) - reporting
 * unreachability on the very first failed cycle would be noisy for that ordinary case.
 * TRACKER_UNREACHABLE fires only once REQUIRED_CONSECUTIVE_FAILURES cycles have failed in a
 * row with no intervening success; TRACKER_RECOVERED fires on the very next success once
 * unreachability was actually reported - asymmetric on purpose, since recovery is unambiguous
 * good news with no reason to delay it.
 */
public final class TrackedTrackerClient implements TrackerClient {

    private static final int REQUIRED_CONSECUTIVE_FAILURES = 2;

    private final String url;
    private final int tier;
    private final TrackerClient delegate;
    private final TrackerStatusListener listener;
    private volatile TrackerStatus status;
    private int consecutiveFailures;
    private boolean reportedUnreachable;

    public TrackedTrackerClient(String url, int tier, TrackerClient delegate) {
        this(url, tier, delegate, NoOpTrackerStatusListener.INSTANCE);
    }

    public TrackedTrackerClient(String url, int tier, TrackerClient delegate, TrackerStatusListener listener) {
        this.url = url;
        this.tier = tier;
        this.delegate = delegate;
        this.listener = listener;
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
        consecutiveFailures = 0;
        if (reportedUnreachable) {
            reportedUnreachable = false;
            listener.onTrackerRecovered(url);
        }
    }

    private void recordFailure(TrackerException e) {
        TrackerStatus previous = status;
        status = new TrackerStatus(url, tier, TrackerStatus.State.ERROR, Instant.now(), null,
                e.getMessage(), previous.seeders(), previous.leechers());
        consecutiveFailures++;
        if (!reportedUnreachable && consecutiveFailures >= REQUIRED_CONSECUTIVE_FAILURES) {
            reportedUnreachable = true;
            listener.onTrackerUnreachable(url, e.getMessage());
        }
    }
}
