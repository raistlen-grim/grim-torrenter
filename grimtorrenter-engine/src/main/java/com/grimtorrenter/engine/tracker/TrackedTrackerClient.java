package com.grimtorrenter.engine.tracker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Wraps a single tracker client (HttpTrackerClient/UdpTrackerClient) and records a
 * TrackerStatus snapshot on every announce() call, without changing announce()'s own
 * behavior at all - success/failure still returns/throws exactly as the delegate would, so
 * MultiTrackerClient's own concurrent-announce/aggregation logic (design_docs/0022's own
 * 2026-09-06 revision) needs no changes here. One instance is
 * expected to live for a torrent session's whole lifetime, same as the delegate it wraps.
 * See design_docs/0031.
 *
 * <p>Also the sole per-torrent decision point for TRACKER_UNREACHABLE/TRACKER_RECOVERED library
 * events (design_docs/0055's addendum) - chosen over MultiTrackerClient because this is already
 * the one place with a genuine before/after view of a single tracker's own status;
 * MultiTrackerClient only aggregates statuses this class already decided. Debounced against
 * flapping by <em>elapsed time</em>, not announce count (design_docs/0055's 2026-09-21
 * revision): TRACKER_UNREACHABLE fires only once announces have been failing continuously - at
 * least REQUIRED_CONSECUTIVE_FAILURES of them, spanning at least STABLE_WINDOW - with no
 * intervening success; TRACKER_RECOVERED fires only once announces have then succeeded
 * continuously for STABLE_WINDOW. Either streak is broken by a single opposite result, so a
 * tracker that alternates fail/succeed reports nothing at all. Note this class only decides
 * <em>this torrent's</em> view; TorrentEngine's TrackerReachability further collapses that
 * across every torrent sharing the tracker URL before anything reaches the events feed.
 */
public final class TrackedTrackerClient implements TrackerClient {

    private static final int REQUIRED_CONSECUTIVE_FAILURES = 2;
    static final Duration STABLE_WINDOW = Duration.ofMinutes(30);

    private final String url;
    private final int tier;
    private final TrackerClient delegate;
    private final TrackerStatusListener listener;
    private volatile TrackerStatus status;
    private final Clock clock;
    private int consecutiveFailures;
    private Instant failingSince;
    private Instant succeedingSince;
    private boolean reportedUnreachable;

    public TrackedTrackerClient(String url, int tier, TrackerClient delegate) {
        this(url, tier, delegate, NoOpTrackerStatusListener.INSTANCE);
    }

    public TrackedTrackerClient(String url, int tier, TrackerClient delegate, TrackerStatusListener listener) {
        this(url, tier, delegate, listener, Clock.systemUTC());
    }

    /** Clock is injectable so tests can cross STABLE_WINDOW without sleeping. */
    TrackedTrackerClient(String url, int tier, TrackerClient delegate, TrackerStatusListener listener,
                         Clock clock) {
        this.clock = clock;
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
        Instant now = clock.instant();
        status = new TrackerStatus(url, tier, TrackerStatus.State.WORKING, now,
                now.plusSeconds(response.interval()), null, response.complete(), response.incomplete(),
                response.peers().size());
        consecutiveFailures = 0;
        failingSince = null;
        if (!reportedUnreachable) {
            return;
        }
        if (succeedingSince == null) {
            succeedingSince = now;
        }
        if (Duration.between(succeedingSince, now).compareTo(STABLE_WINDOW) >= 0) {
            reportedUnreachable = false;
            succeedingSince = null;
            listener.onTrackerRecovered(url);
        }
    }

    private void recordFailure(TrackerException e) {
        Instant now = clock.instant();
        TrackerStatus previous = status;
        status = new TrackerStatus(url, tier, TrackerStatus.State.ERROR, now, null,
                e.getMessage(), previous.seeders(), previous.leechers(), previous.peers());
        succeedingSince = null;
        consecutiveFailures++;
        if (failingSince == null) {
            failingSince = now;
        }
        if (!reportedUnreachable && consecutiveFailures >= REQUIRED_CONSECUTIVE_FAILURES
                && Duration.between(failingSince, now).compareTo(STABLE_WINDOW) >= 0) {
            reportedUnreachable = true;
            listener.onTrackerUnreachable(url, e.getMessage());
        }
    }
}
