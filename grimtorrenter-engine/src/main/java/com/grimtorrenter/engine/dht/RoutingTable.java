package com.grimtorrenter.engine.dht;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A Kademlia-style k-bucket routing table (BEP 5). Uses a fixed array of 160 buckets - one
 * per possible XOR-distance magnitude (the position of the highest set bit in the distance
 * to our own id, via {@link NodeId#distanceTo}) - rather than the splitting-tree
 * refinement some implementations use to keep only the buckets near our own id
 * fine-grained. See design_docs/0028: with an 8-contacts-per-bucket cap, 160 buckets is at
 * most 1280 contacts total, trivial to hold as plain (mostly-empty) lists, so the
 * splitting tree's extra complexity buys nothing at this project's scale.
 *
 * <p>Pure in-memory state - no networking. BEP 5's "ping the bucket's least-recently-seen
 * contact before evicting it" policy needs a live socket to carry out, so it isn't
 * implemented here: {@link #insert} instead reports back the contact a caller should ping,
 * leaving the actual ping - and the resulting {@link #evict}-then-insert, or doing nothing
 * - to whichever later layer owns the network ({@link DhtNode#seen}, since design_docs/0028's
 * own 2026-08-30 addendum turned this policy on).
 *
 * <p>Also tracks, per bucket, when it was last refreshed - either by a successful
 * {@link #insert} into it, or by an explicit {@link #markRefreshed} after a refresh lookup
 * found nothing new - so {@link #mostOverdueBucket()} can drive a periodic background
 * refresh into parts of the id space a single startup bootstrap lookup never reaches (it
 * only explores the neighborhood near our own id). See {@link #randomIdInBucket} for how a
 * refresh picks a lookup target inside a specific bucket's range.
 */
public final class RoutingTable {

    /** BEP 5's "k" - the maximum number of contacts held per bucket. */
    public static final int BUCKET_SIZE = 8;

    private static final int BUCKET_COUNT = NodeId.LENGTH_BYTES * 8;

    private final NodeId ourId;
    private final List<Map<NodeId, NodeInfo>> buckets;
    private final Instant[] lastRefreshed;

    public RoutingTable(NodeId ourId) {
        this.ourId = ourId;
        List<Map<NodeId, NodeInfo>> initial = new ArrayList<>(BUCKET_COUNT);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            initial.add(new LinkedHashMap<>());
        }
        this.buckets = initial;
        this.lastRefreshed = new Instant[BUCKET_COUNT];
        Arrays.fill(this.lastRefreshed, Instant.EPOCH);
    }

    /**
     * Records that we've seen node - refreshing it (moving it to most-recently-seen) if
     * already known, or adding it if its bucket has room. Our own id is always ignored.
     * Touches that bucket's {@link #mostOverdueBucket() refresh timestamp} on success -
     * real activity is itself evidence the bucket doesn't need a separate refresh soon.
     *
     * <p>If the bucket is full and node is new, nothing changes here; instead this returns
     * that bucket's least-recently-seen contact, for the caller to ping - if it's still
     * alive, call insert() with it again to refresh it (and drop node); if not, evict() it
     * and call insert(node) again to actually add the new one.
     */
    public synchronized Optional<NodeInfo> insert(NodeInfo node) {
        if (node.id().equals(ourId)) {
            return Optional.empty();
        }
        int bucketIndex = bucketIndex(node.id());
        Map<NodeId, NodeInfo> bucket = buckets.get(bucketIndex);
        boolean alreadyKnown = bucket.remove(node.id()) != null;
        if (alreadyKnown || bucket.size() < BUCKET_SIZE) {
            bucket.put(node.id(), node);
            lastRefreshed[bucketIndex] = Instant.now();
            return Optional.empty();
        }
        return Optional.of(bucket.values().iterator().next());
    }

    /** Removes id from its bucket, if present. A no-op for our own id or an unknown id. */
    public synchronized void evict(NodeId id) {
        if (id.equals(ourId)) {
            return;
        }
        buckets.get(bucketIndex(id)).remove(id);
    }

    /** The up to count known contacts closest (by XOR distance) to target, across every
     * bucket. */
    public synchronized List<NodeInfo> closestNodes(NodeId target, int count) {
        return buckets.stream()
                .flatMap(bucket -> bucket.values().stream())
                .sorted(Comparator.comparing(node -> node.id().distanceTo(target)))
                .limit(count)
                .toList();
    }

    public synchronized int size() {
        return buckets.stream().mapToInt(Map::size).sum();
    }

    /** Every known contact, across every bucket - unsorted, unlike closestNodes(), since
     * nothing here needs distance ordering (currently just DhtNode.knownContacts(), for
     * persisting the table across restarts - see design_docs/0028's own 2026-08-30
     * addendum). */
    public synchronized List<NodeInfo> allNodes() {
        return buckets.stream()
                .flatMap(bucket -> bucket.values().stream())
                .toList();
    }

    /** Marks bucketIndex as refreshed right now, without an accompanying insert() - for a
     * refresh lookup that completed but found nothing new to add (an insert() already
     * touches its own bucket's timestamp on its own, so this is only needed for the
     * "found nothing" case). */
    synchronized void markRefreshed(int bucketIndex) {
        lastRefreshed[bucketIndex] = Instant.now();
    }

    /** The bucket index that's gone longest without activity (a successful insert, or an
     * explicit markRefreshed) - a never-touched bucket (still Instant.EPOCH) always sorts
     * first. The target for the next periodic refresh tick - see design_docs/0028's own
     * 2026-08-30 addendum. */
    synchronized int mostOverdueBucket() {
        int oldestIndex = 0;
        for (int i = 1; i < BUCKET_COUNT; i++) {
            if (lastRefreshed[i].isBefore(lastRefreshed[oldestIndex])) {
                oldestIndex = i;
            }
        }
        return oldestIndex;
    }

    /** A random NodeId whose XOR distance to ourId falls exactly within bucketIndex's range
     * - the standard Kademlia "pick a lookup target inside this bucket" technique, used to
     * refresh a bucket a plain self-lookup would never reach (self-lookup only ever visits
     * the neighborhood near our own id). Built bit-for-bit rather than by trial and error:
     * copies ourId, flips the one bit whose position determines bucketIndex
     * ({@code distanceTo(id).bitLength() - 1}, i.e. counting from the least significant bit),
     * then randomizes every bit below that position (bits above it must stay equal to
     * ourId's, or the distance would land in a different bucket entirely). */
    NodeId randomIdInBucket(int bucketIndex) {
        byte[] target = ourId.bytes();
        int boundaryByteIndex = target.length - 1 - bucketIndex / 8;
        int boundaryBitInByte = bucketIndex % 8;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int byteIndex = target.length - 1; byteIndex > boundaryByteIndex; byteIndex--) {
            target[byteIndex] = (byte) random.nextInt(256);
        }

        int flipMask = 1 << boundaryBitInByte;
        int lowBitsMask = flipMask - 1;
        int flipped = (target[boundaryByteIndex] & 0xFF) ^ flipMask;
        int withRandomLowBits = (flipped & ~lowBitsMask) | random.nextInt(flipMask);
        target[boundaryByteIndex] = (byte) withRandomLowBits;

        return NodeId.of(target);
    }

    private int bucketIndex(NodeId id) {
        return ourId.distanceTo(id).bitLength() - 1;
    }
}
