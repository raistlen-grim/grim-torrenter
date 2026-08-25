package com.grimtorrenter.engine.dht;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * - to whichever later layer owns the network.
 */
public final class RoutingTable {

    /** BEP 5's "k" - the maximum number of contacts held per bucket. */
    public static final int BUCKET_SIZE = 8;

    private static final int BUCKET_COUNT = NodeId.LENGTH_BYTES * 8;

    private final NodeId ourId;
    private final List<Map<NodeId, NodeInfo>> buckets;

    public RoutingTable(NodeId ourId) {
        this.ourId = ourId;
        List<Map<NodeId, NodeInfo>> initial = new ArrayList<>(BUCKET_COUNT);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            initial.add(new LinkedHashMap<>());
        }
        this.buckets = initial;
    }

    /**
     * Records that we've seen node - refreshing it (moving it to most-recently-seen) if
     * already known, or adding it if its bucket has room. Our own id is always ignored.
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
        Map<NodeId, NodeInfo> bucket = buckets.get(bucketIndex(node.id()));
        boolean alreadyKnown = bucket.remove(node.id()) != null;
        if (alreadyKnown || bucket.size() < BUCKET_SIZE) {
            bucket.put(node.id(), node);
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

    private int bucketIndex(NodeId id) {
        return ourId.distanceTo(id).bitLength() - 1;
    }
}
