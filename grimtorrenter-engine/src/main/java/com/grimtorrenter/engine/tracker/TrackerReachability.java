package com.grimtorrenter.engine.tracker;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine-wide view of which tracker URLs are currently reported unreachable, and by which
 * torrents - collapses N torrents' independent TrackedTrackerClient reports about the same
 * tracker into one TRACKER_UNREACHABLE/TRACKER_RECOVERED library event pair (design_docs/0055's
 * 2026-09-21 revision), since a dead tracker is a fact about the tracker, not about each torrent
 * using it.
 *
 * <p>markUnreachable() returns true only for the first torrent to report a given URL;
 * markRecovered() returns true only if the URL was actually being tracked as unreachable, and
 * clears every torrent's vote at once - the first torrent to see the tracker stably working again
 * is proof enough, and it means a paused torrent's stale vote can't hold the tracker "down"
 * forever. forget() drops a removed torrent's votes without firing anything, so the map is
 * bounded by (live torrents x their trackers) rather than growing over the process lifetime.
 * Lock-free: every mutation is a per-key ConcurrentHashMap compute, safe to call from any
 * session's announce thread.
 */
public final class TrackerReachability {

    private final ConcurrentHashMap<String, Set<String>> unreachableBy = new ConcurrentHashMap<>();

    public boolean markUnreachable(String url, String infoHash) {
        boolean[] first = {false};
        unreachableBy.compute(url, (k, voters) -> {
            if (voters == null) {
                first[0] = true;
                voters = new HashSet<>();
            }
            voters.add(infoHash);
            return voters;
        });
        return first[0];
    }

    public boolean markRecovered(String url) {
        return unreachableBy.remove(url) != null;
    }

    public void forget(String infoHash) {
        for (String url : unreachableBy.keySet()) {
            unreachableBy.computeIfPresent(url, (k, voters) -> {
                voters.remove(infoHash);
                return voters.isEmpty() ? null : voters;
            });
        }
    }
}
