package com.grimtorrenter.engine.tracker;

import java.time.Instant;

/**
 * A snapshot of one tracker's most recent announce outcome, for external consumers (see
 * design_docs/0031's Trackers endpoint). Produced by TrackedTrackerClient, which owns when
 * each field updates.
 *
 * <p>seeders/leechers deliberately survive a subsequent ERROR (not cleared to null) - a
 * stale-but-recent count is more useful than none, and lastAnnouncedAt/state already tell
 * the caller how fresh it is. nextAnnounceAt is this tracker's own view (its own response's
 * interval, from its own last successful announce) - null whenever that's not known, which
 * includes every ERROR/UNKNOWN tracker. UNKNOWN itself should now be rare/transient in
 * steady state - MultiTrackerClient announces to every configured tracker concurrently on
 * every call (design_docs/0022's own 2026-09-06 revision), rather than only reaching a
 * lower-priority tracker once every higher-priority one had failed.
 */
public record TrackerStatus(
        String url,
        int tier,
        State state,
        Instant lastAnnouncedAt,
        Instant nextAnnounceAt,
        String lastError,
        Integer seeders,
        Integer leechers,
        /** Size of the peer list *that announce's response actually returned* (bounded by this
         * tracker's own num_want handling) - not the swarm's total size. Same
         * survives-a-subsequent-ERROR treatment as seeders/leechers. See design_docs/0067. */
        Integer peers
) {
    public enum State {
        UNKNOWN, WORKING, ERROR
    }

    static TrackerStatus initial(String url, int tier) {
        return new TrackerStatus(url, tier, State.UNKNOWN, null, null, null, null, null, null);
    }
}
