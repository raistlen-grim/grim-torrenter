package com.grimtorrenter.engine.blocklist;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * An immutable set of IPv4 ranges: sorted, with overlapping and adjacent ranges merged, stored
 * as two parallel {@code long[]} (start/end, each a 32-bit unsigned address). {@code contains()}
 * is a binary search - O(log n), allocation-free - so it's safe on the connection hot path even
 * for a list of hundreds of thousands of ranges. Built once by {@link Builder} and never
 * mutated, so a reader holding a reference never sees a half-built list. See design_docs/0078.
 */
public final class IpRangeSet {

    public static final IpRangeSet EMPTY = new IpRangeSet(new long[0], new long[0]);

    private final long[] starts;
    private final long[] ends;

    private IpRangeSet(long[] starts, long[] ends) {
        this.starts = starts;
        this.ends = ends;
    }

    /** Number of merged ranges - not the number of lines the list was built from. */
    public int size() {
        return starts.length;
    }

    public boolean isEmpty() {
        return starts.length == 0;
    }

    public boolean contains(long ip) {
        int low = 0;
        int high = starts.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] > ip) {
                high = mid - 1;
            } else if (ends[mid] < ip) {
                low = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** IPv4 only - an IPv6 address is never in the set (this engine's peer discovery is IPv4-only,
     * so it can't occur today). */
    public boolean contains(InetAddress address) {
        if (starts.length == 0 || address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        return contains(toLong(bytes));
    }

    public static long toLong(byte[] ipv4) {
        return ((ipv4[0] & 0xFFL) << 24) | ((ipv4[1] & 0xFFL) << 16) | ((ipv4[2] & 0xFFL) << 8) | (ipv4[3] & 0xFFL);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects ranges in any order, then sorts and merges them once in {@link #build()}. Each
     * range is packed into a single {@code long} (start in the high 32 bits, end in the low 32)
     * so the sort is one primitive {@code Arrays.sort} rather than a comparator over objects. */
    public static final class Builder {

        private long[] packed = new long[1024];
        private int count;

        public int count() {
            return count;
        }

        /** start and end are inclusive unsigned 32-bit addresses; a reversed pair is swapped. */
        public void add(long start, long end) {
            if (start > end) {
                long swap = start;
                start = end;
                end = swap;
            }
            if (count == packed.length) {
                packed = Arrays.copyOf(packed, packed.length * 2);
            }
            // Sign bit flipped so the signed long sort below orders addresses >= 128.0.0.0 (whose
            // packed value would otherwise be negative) after the lower ones, not before.
            packed[count++] = ((start << 32) | end) ^ Long.MIN_VALUE;
        }

        public IpRangeSet build() {
            if (count == 0) {
                return EMPTY;
            }
            long[] sorted = Arrays.copyOf(packed, count);
            Arrays.sort(sorted);
            long[] mergedStarts = new long[sorted.length];
            long[] mergedEnds = new long[sorted.length];
            int merged = 0;
            long first = sorted[0] ^ Long.MIN_VALUE;
            long currentStart = first >>> 32;
            long currentEnd = first & 0xFFFFFFFFL;
            for (int i = 1; i < sorted.length; i++) {
                long value = sorted[i] ^ Long.MIN_VALUE;
                long start = value >>> 32;
                long end = value & 0xFFFFFFFFL;
                if (start <= currentEnd + 1) {
                    currentEnd = Math.max(currentEnd, end);
                } else {
                    mergedStarts[merged] = currentStart;
                    mergedEnds[merged] = currentEnd;
                    merged++;
                    currentStart = start;
                    currentEnd = end;
                }
            }
            mergedStarts[merged] = currentStart;
            mergedEnds[merged] = currentEnd;
            merged++;
            return new IpRangeSet(Arrays.copyOf(mergedStarts, merged), Arrays.copyOf(mergedEnds, merged));
        }
    }
}
