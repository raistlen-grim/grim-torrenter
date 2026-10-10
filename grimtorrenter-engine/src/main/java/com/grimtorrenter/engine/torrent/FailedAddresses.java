package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.tracker.PeerAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Addresses one torrent's outbound connection attempts have failed to reach, and when each may
 * be tried again. A failure excludes the address for a backoff that doubles with every further
 * failure, up to a cap - a peer that was briefly offline or at its own connection limit gets
 * another chance, while one that is permanently unreachable (the large majority in a real
 * swarm) costs fewer and fewer attempts. exclude() is for addresses that must never be dialled
 * again this session (our own). See design_docs/0017's 2026-10-06 revision.
 *
 * <p>Lock-free (ConcurrentHashMap only), per design_docs/0007. One small entry per address ever
 * failed, never pruned - the same lifetime TorrentSession's knownAddresses already has.
 */
final class FailedAddresses {

    private static final long NEVER = Long.MAX_VALUE;

    private record Entry(int failures, long retryAtMillis) {}

    private final ConcurrentHashMap<PeerAddress, Entry> entries = new ConcurrentHashMap<>();
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;

    FailedAddresses(long initialBackoffMillis, long maxBackoffMillis) {
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
    }

    int size() {
        return entries.size();
    }

    /** Has failed at least once and not connected since - whether or not its backoff is over. */
    boolean contains(PeerAddress address) {
        return entries.containsKey(address);
    }

    /** Failed, and its backoff has not run out yet (always true for an excluded address). */
    boolean isBackingOff(PeerAddress address, long nowMillis) {
        Entry entry = entries.get(address);
        return entry != null && nowMillis < entry.retryAtMillis();
    }

    void recordFailure(PeerAddress address, long nowMillis) {
        entries.compute(address, (key, entry) -> {
            if (entry != null && entry.retryAtMillis() == NEVER) {
                return entry;
            }
            int failures = entry == null ? 1 : entry.failures() + 1;
            return new Entry(failures, nowMillis + backoffFor(failures));
        });
    }

    /** Never dial this address again this session. */
    void exclude(PeerAddress address) {
        entries.put(address, new Entry(1, NEVER));
    }

    /** The address connected - forget its failures, so a later one starts from the first backoff. */
    void clear(PeerAddress address) {
        entries.computeIfPresent(address, (key, entry) -> entry.retryAtMillis() == NEVER ? entry : null);
    }

    /** Addresses whose backoff is over, longest-waiting first. */
    List<PeerAddress> dueForRetry(long nowMillis) {
        return entries.entrySet().stream()
                .filter(e -> nowMillis >= e.getValue().retryAtMillis())
                .sorted(Comparator.comparingLong((Map.Entry<PeerAddress, Entry> e) -> e.getValue().retryAtMillis()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private long backoffFor(int failures) {
        // Shift capped well below 63 so a long-failing address can't overflow into a short wait.
        long backoff = initialBackoffMillis << Math.min(failures - 1, 20);
        return Math.min(backoff, maxBackoffMillis);
    }
}
