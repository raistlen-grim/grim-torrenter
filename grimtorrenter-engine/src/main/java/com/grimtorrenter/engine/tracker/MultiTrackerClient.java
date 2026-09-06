package com.grimtorrenter.engine.tracker;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Announces to every configured tracker concurrently on every call, aggregating peers from
 * whichever succeed - not BEP 12 tier-fallback semantics (a prior version of this class tried
 * tiers in order, stopping at the first success; see design_docs/0022's own 2026-09-06
 * revision for why). A tier still exists as a construction-time grouping (each individual
 * tracker keeps its own tier index for TrackerStatus/UI display, via TrackedTrackerClient) but
 * no longer confers any priority/fallback behavior here - confirmed real-world evidence (a
 * qBittorrent trackers-tab screenshot showing several different tiers all independently
 * "Working," each with its own distinct, non-duplicate seed/peer/leech count) showed real
 * clients don't limit themselves to one tracker's slice of the swarm either, and a torrent's
 * announce-list commonly puts each tracker in its own tier even when the torrent's author
 * meant "here are some alternates," not a genuine priority chain.
 *
 * <p>One shared announce cycle/interval for every tracker (not each on its own independently-
 * timed schedule) - confirmed with the user as the simpler, smaller option: some trackers with
 * a longer stated interval than the group's minimum get polled somewhat more often than they'd
 * prefer, a soft politeness cost rather than a correctness one, in exchange for a much smaller
 * change (TorrentSession's own announce()-returns-a-response call shape is completely
 * unaffected - only this class's internals changed).
 */
public final class MultiTrackerClient implements TrackerClient {

    private final List<TrackerClient> trackers;

    public MultiTrackerClient(List<List<TrackerClient>> tiers) {
        if (tiers.stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("At least one tracker is required");
        }
        this.trackers = tiers.stream().flatMap(List::stream).toList();
    }

    /** Announces to every tracker concurrently (virtual-thread-per-task, per
     * [[0007-concurrency-model]] - the same pattern TorrentEngine.raceOneRound() already uses
     * for magnet peer racing) and blocks until every one has either responded or failed on its
     * own terms - each TrackerClient implementation already bounds its own worst case
     * (HttpTrackerClient's connect timeout, UdpTrackerClient's retry/timeout policy), so this
     * is bounded by the slowest single tracker's own timeout, not the sum of every tracker's
     * (a latency improvement over the old sequential-tiers behavior, not just a peer-count one).
     *
     * <p>Aggregates peers from every tracker that succeeded into one deduplicated set (a
     * PeerAddress is a record - two trackers reporting the same peer collapse to one entry
     * naturally) and uses the minimum interval among the successes, so the shared reannounce
     * cycle stays at least as responsive as the most demanding tracker asks for. Only throws
     * if every tracker failed - same "last failure wins" reporting the old tier-fallback
     * version already used, for the same reason (nothing downstream distinguishes multiple
     * simultaneous failure causes any more usefully than one). Every individual tracker's own
     * TrackedTrackerClient wrapper still records its own success/failure regardless of what
     * this method returns - see statuses(). */
    @Override
    public TrackerResponse announce(TrackerRequest request) {
        List<Callable<TrackerResponse>> tasks = trackers.stream()
                .<Callable<TrackerResponse>>map(client -> () -> client.announce(request))
                .toList();

        List<Future<TrackerResponse>> futures;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackerException("Interrupted while announcing to trackers", e);
        }

        Set<PeerAddress> peers = new LinkedHashSet<>();
        long minInterval = Long.MAX_VALUE;
        TrackerException lastFailure = null;
        boolean anySucceeded = false;
        for (Future<TrackerResponse> future : futures) {
            try {
                TrackerResponse response = future.get();
                anySucceeded = true;
                peers.addAll(response.peers());
                minInterval = Math.min(minInterval, response.interval());
            } catch (ExecutionException e) {
                lastFailure = e.getCause() instanceof TrackerException te
                        ? te : new TrackerException("Tracker announce failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TrackerException("Interrupted while announcing to trackers", e);
            }
        }

        if (!anySucceeded) {
            throw lastFailure != null ? lastFailure : new TrackerException("No trackers configured");
        }
        return new TrackerResponse(minInterval, null, 0, 0, List.copyOf(peers), null, null);
    }

    /** Aggregates every wrapped tracker's own status - announce() above now actually reaches
     * every one of them on every call, so in steady state none should linger at UNKNOWN the
     * way a never-reached lower-tier tracker used to. See design_docs/0031. */
    @Override
    public List<TrackerStatus> statuses() {
        return trackers.stream().flatMap(client -> client.statuses().stream()).toList();
    }
}
