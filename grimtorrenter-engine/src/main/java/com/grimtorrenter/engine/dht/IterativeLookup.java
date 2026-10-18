package com.grimtorrenter.engine.dht;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Kademlia's iterative lookup (BEP 5), shared by find_node (NodeLookup) and get_peers
 * (PeerLookup). Starting from the routing table's closest known contacts, it repeatedly queries
 * the closest not-yet-queried candidates within the current best-K window, ALPHA at a time and
 * concurrently on virtual threads, until every candidate in that window has been asked. Each
 * response can name closer nodes, which join the shortlist.
 *
 * <p>Nodes merely mentioned in a response only become lookup candidates here, never routing-
 * table entries; DhtNode inserts a node into the routing table only when it answers us
 * directly. Bounded: the window limits how many nodes are ever queried, and MAX_ROUNDS stops a
 * hostile network that keeps surfacing "new" closer candidates.
 *
 * @param <R> what one query answers with
 */
final class IterativeLookup<R> {

    /** One query to one node. A DhtException means that node produced nothing. */
    @FunctionalInterface
    interface Query<R> {
        R ask(InetSocketAddress address) throws DhtException;
    }

    /** Takes in one node's answer and returns the nodes it named, to join the shortlist. Called
     * only on the thread running the lookup, so it needs no locking. */
    @FunctionalInterface
    interface ResponseHandler<R> {
        List<NodeInfo> accept(NodeId from, R response);
    }

    private static final int ALPHA = 3;
    /** Real lookups converge in a handful of rounds, long before this. */
    private static final int MAX_ROUNDS = 20;

    private final DhtNode dhtNode;
    private final NodeId target;
    private final Query<R> query;
    private final ResponseHandler<R> handler;

    private final Set<NodeId> queried = new HashSet<>();
    private final Map<NodeId, NodeInfo> shortlist = new HashMap<>();

    IterativeLookup(DhtNode dhtNode, NodeId target, Query<R> query, ResponseHandler<R> handler) {
        this.dhtNode = dhtNode;
        this.target = target;
        this.query = query;
        this.handler = handler;
    }

    /** Runs the lookup to completion. */
    void run() {
        merge(dhtNode.routingTable().closestNodes(target, RoutingTable.BUCKET_SIZE));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<NodeInfo> candidates = closestUnqueried();
                if (candidates.isEmpty()) {
                    break;
                }
                queryRound(executor, candidates);
            }
        }
    }

    /** The closest limit shortlist entries (by XOR distance to the target) that match filter. */
    List<NodeInfo> closest(Predicate<NodeInfo> filter, int limit) {
        return shortlist.values().stream()
                .filter(filter)
                .sorted(Comparator.comparing(node -> node.id().distanceTo(target)))
                .limit(limit)
                .toList();
    }

    /** Up to ALPHA not-yet-queried entries, considered only within the closest window of the
     * shortlist - so exploration stays near the best K instead of eventually querying every
     * node anyone ever mentioned. */
    private List<NodeInfo> closestUnqueried() {
        return closest(node -> true, RoutingTable.BUCKET_SIZE).stream()
                .filter(node -> !queried.contains(node.id()))
                .limit(ALPHA)
                .toList();
    }

    private void queryRound(ExecutorService executor, List<NodeInfo> candidates) {
        List<Future<Optional<R>>> futures = new ArrayList<>();
        for (NodeInfo candidate : candidates) {
            queried.add(candidate.id());
            futures.add(executor.submit(() -> queryOne(candidate)));
        }
        for (int i = 0; i < candidates.size(); i++) {
            NodeId from = candidates.get(i).id();
            try {
                futures.get(i).get().ifPresent(response -> merge(handler.accept(from, response)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // queryOne doesn't throw; treat it like a node that produced nothing if it ever did.
            }
        }
    }

    private Optional<R> queryOne(NodeInfo candidate) {
        try {
            return Optional.of(query.ask(new InetSocketAddress(candidate.address(), candidate.port())));
        } catch (DhtException e) {
            return Optional.empty();
        }
    }

    private void merge(List<NodeInfo> nodes) {
        for (NodeInfo node : nodes) {
            if (!node.id().equals(dhtNode.ourId())) {
                shortlist.put(node.id(), node);
            }
        }
    }
}
