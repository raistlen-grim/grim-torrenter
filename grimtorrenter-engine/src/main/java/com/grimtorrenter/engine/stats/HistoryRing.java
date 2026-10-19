package com.grimtorrenter.engine.stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Transfer totals in wall-clock-aligned intervals (minutes, hours), both directions, for the last
 * capacity intervals. See design_docs/0102.
 *
 * <p>Interval n is [n x intervalMillis, (n + 1) x intervalMillis) since the epoch, and lives in
 * slot n mod capacity. Writing an interval whose slot still holds an older one replaces it, so
 * there's no separate close or expire step, and intervals nobody wrote to - the app was down -
 * read as gaps. Each interval also keeps how much of it was actually sampled, so a partial one
 * (the app started half way through the hour) averages over the time it covers.
 *
 * <p>Synchronized: written once a second by the sampling tick, read only when someone asks for a
 * graph. Neither is on the block path, so the no-synchronized-in-the-hot-path rule
 * (design_docs/0007) isn't in play, and a lock keeps the three arrays consistent.
 */
public final class HistoryRing {

    /** One interval's raw totals - what persistence saves and restores. */
    public record Interval(long index, long downloadBytes, long uploadBytes, long coveredMillis) {
    }

    private static final long EMPTY = Long.MIN_VALUE;

    private final long intervalMillis;
    private final int capacity;
    private final long[] index;
    private final long[] downloadBytes;
    private final long[] uploadBytes;
    private final long[] coveredMillis;

    public HistoryRing(long intervalMillis, int capacity) {
        this.intervalMillis = intervalMillis;
        this.capacity = capacity;
        this.index = new long[capacity];
        this.downloadBytes = new long[capacity];
        this.uploadBytes = new long[capacity];
        this.coveredMillis = new long[capacity];
        Arrays.fill(index, EMPTY);
    }

    /** Adds one tick's bytes, which covered coveredMillis, to the interval nowMillis falls in. */
    public synchronized void add(long download, long upload, long covered, long nowMillis) {
        int slot = slotFor(Math.floorDiv(nowMillis, intervalMillis));
        downloadBytes[slot] += Math.max(0, download);
        uploadBytes[slot] += Math.max(0, upload);
        coveredMillis[slot] += Math.max(0, covered);
    }

    /** The last capacity intervals, ending with the one nowMillis falls in. */
    public synchronized TransferHistory snapshot(long nowMillis) {
        long newest = Math.floorDiv(nowMillis, intervalMillis);
        List<Long> download = new ArrayList<>(capacity);
        List<Long> upload = new ArrayList<>(capacity);
        for (long n = newest - capacity + 1; n <= newest; n++) {
            int slot = (int) Math.floorMod(n, (long) capacity);
            if (index[slot] == n && coveredMillis[slot] > 0) {
                download.add(downloadBytes[slot] * 1000 / coveredMillis[slot]);
                upload.add(uploadBytes[slot] * 1000 / coveredMillis[slot]);
            } else {
                download.add(null);
                upload.add(null);
            }
        }
        return new TransferHistory(intervalMillis, (newest + 1) * intervalMillis,
                Collections.unmodifiableList(download), Collections.unmodifiableList(upload));
    }

    /** Every interval held, for saving. */
    public synchronized List<Interval> intervals() {
        List<Interval> intervals = new ArrayList<>();
        for (int slot = 0; slot < capacity; slot++) {
            if (index[slot] != EMPTY) {
                intervals.add(new Interval(index[slot], downloadBytes[slot], uploadBytes[slot], coveredMillis[slot]));
            }
        }
        return intervals;
    }

    /** Puts back saved intervals. Where two map to the same slot, the newer wins. */
    public synchronized void restore(List<Interval> intervals) {
        for (Interval interval : intervals) {
            int slot = (int) Math.floorMod(interval.index(), (long) capacity);
            if (index[slot] == EMPTY || index[slot] < interval.index()) {
                index[slot] = interval.index();
                downloadBytes[slot] = Math.max(0, interval.downloadBytes());
                uploadBytes[slot] = Math.max(0, interval.uploadBytes());
                coveredMillis[slot] = Math.max(0, interval.coveredMillis());
            }
        }
    }

    /** The slot for interval n, reset first if it still holds an older interval. Caller holds the
     * lock. */
    private int slotFor(long n) {
        int slot = (int) Math.floorMod(n, (long) capacity);
        if (index[slot] != n) {
            index[slot] = n;
            downloadBytes[slot] = 0;
            uploadBytes[slot] = 0;
            coveredMillis[slot] = 0;
        }
        return slot;
    }
}
