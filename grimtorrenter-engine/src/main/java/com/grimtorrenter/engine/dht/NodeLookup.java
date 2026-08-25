package com.grimtorrenter.engine.dht;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BEP 5 / Kademlia's iterative find_node lookup: starting from the routing table's own
 * closest known contacts, repeatedly queries the closest not-yet-queried candidates within
 * the current best-K window (ALPHA at a time, concurrently - virtual threads make this no
 * more code than doing it sequentially, per design_docs/0007) for closer ones, until every
 * candidate within that window has been queried. Also how a node's routing table grows
 * beyond its direct contacts in the first place: every query this makes has its sender
 * inserted by DhtNode itself as a side effect (see DhtNode's own Javadoc) - nodes merely
 * *mentioned* in a response only ever become lookup candidates here, not routing-table
 * entries, until (if ever) they're queried directly.
 *
 * <p>Package-private - used by Bootstrap (this slice) and expected to be reused by a
 * future routing-table refresh and by get_peers lookups (slice 5), all within this package.
 */
final class NodeLookup {

    private static final int ALPHA = 3;
    /** Hard cap on rounds, purely as a safety net against a pathological/adversarial
     * network that keeps surfacing "new" closer candidates forever - real lookups converge
     * in a handful of rounds long before this. */
    private static final int MAX_ROUNDS = 20;

    private final DhtNode dhtNode;
    private final NodeId target;
    private final Duration perQueryTimeout;

    private final Set<NodeId> queried = new HashSet<>();
    private final Map<NodeId, NodeInfo> shortlist = new HashMap<>();

    private NodeLookup(DhtNode dhtNode, NodeId target, Duration perQueryTimeout) {
        this.dhtNode = dhtNode;
        this.target = target;
        this.perQueryTimeout = perQueryTimeout;
    }

    /** Runs a full iterative find_node lookup for target against dhtNode, returning up to
     * RoutingTable.BUCKET_SIZE of the closest nodes found (by XOR distance to target). */
    static List<NodeInfo> run(DhtNode dhtNode, NodeId target, Duration perQueryTimeout) {
        return new NodeLookup(dhtNode, target, perQueryTimeout).run();
    }

    private List<NodeInfo> run() {
        merge(dhtNode.routingTable().closestNodes(target, RoutingTable.BUCKET_SIZE));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<NodeInfo> candidates = closestUnqueried(RoutingTable.BUCKET_SIZE, ALPHA);
                if (candidates.isEmpty()) {
                    break;
                }
                queryRound(executor, candidates);
            }
        }
        return closestFromShortlist(RoutingTable.BUCKET_SIZE);
    }

    /** The closest limit not-yet-queried entries, considered only among the closest window
     * currently in the shortlist - bounds exploration to roughly the best-K window instead
     * of eventually querying every node ever mentioned by anyone. */
    private List<NodeInfo> closestUnqueried(int window, int limit) {
        return shortlist.values().stream()
                .sorted(Comparator.comparing(node -> node.id().distanceTo(target)))
                .limit(window)
                .filter(node -> !queried.contains(node.id()))
                .limit(limit)
                .toList();
    }

    private void queryRound(ExecutorService executor, List<NodeInfo> candidates) {
        List<Future<List<NodeInfo>>> futures = new ArrayList<>();
        for (NodeInfo candidate : candidates) {
            queried.add(candidate.id());
            futures.add(executor.submit(() -> queryOne(candidate)));
        }
        for (Future<List<NodeInfo>> future : futures) {
            try {
                merge(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // queryOne never throws (see its own catch) - unreachable in practice, but
                // treat it the same as "this candidate produced nothing" if it ever did.
            }
        }
    }

    private List<NodeInfo> queryOne(NodeInfo candidate) {
        try {
            InetSocketAddress address = new InetSocketAddress(candidate.address(), candidate.port());
            return dhtNode.findNode(address, target, perQueryTimeout);
        } catch (DhtException e) {
            return List.of();
        }
    }

    private void merge(List<NodeInfo> nodes) {
        for (NodeInfo node : nodes) {
            if (!node.id().equals(dhtNode.ourId())) {
                shortlist.put(node.id(), node);
            }
        }
    }

    private List<NodeInfo> closestFromShortlist(int limit) {
        return shortlist.values().stream()
                .sorted(Comparator.comparing(node -> node.id().distanceTo(target)))
                .limit(limit)
                .toList();
    }
}
