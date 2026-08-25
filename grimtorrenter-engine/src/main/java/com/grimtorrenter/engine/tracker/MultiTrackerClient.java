package com.grimtorrenter.engine.tracker;

import java.util.List;

/**
 * Tries each tracker within a tier, in order, until one succeeds; only
 * advances to the next tier if every tracker in the current one fails.
 * Matches BEP 12 (Multitracker Metadata Extension) tier semantics.
 *
 * <p>Does not shuffle trackers within a tier or promote a working tracker
 * to the front for next time (both real BEP 12 refinements) - see
 * design_docs/0022. Those are swarm-politeness/latency optimizations, not
 * needed to fix the actual problem this class exists for: real-world
 * torrents routinely have several dead/blocking trackers, and a client
 * that gives up after the first failure can't complete an otherwise
 * perfectly normal download.
 */
public final class MultiTrackerClient implements TrackerClient {

    private final List<List<TrackerClient>> tiers;

    public MultiTrackerClient(List<List<TrackerClient>> tiers) {
        if (tiers.stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("At least one tracker is required");
        }
        this.tiers = tiers;
    }

    @Override
    public TrackerResponse announce(TrackerRequest request) {
        TrackerException lastFailure = null;
        for (List<TrackerClient> tier : tiers) {
            for (TrackerClient client : tier) {
                try {
                    return client.announce(request);
                } catch (TrackerException e) {
                    lastFailure = e;
                }
            }
        }
        throw lastFailure != null ? lastFailure : new TrackerException("No trackers configured");
    }

    /** Aggregates every wrapped tracker's own status - including ones this call's
     * short-circuiting announce() never reached, whose status simply reflects whenever they
     * were last (or never) attempted. See design_docs/0031. */
    @Override
    public List<TrackerStatus> statuses() {
        return tiers.stream().flatMap(List::stream).flatMap(client -> client.statuses().stream()).toList();
    }
}
