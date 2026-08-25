package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerAddress;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BEP 5's iterative get_peers lookup - the same shape as NodeLookup (closest-unqueried,
 * ALPHA at a time, bounded to the current best-K window, virtual threads per round), but
 * querying for peers of an info hash instead of nodes closest to a target id, and
 * collecting three things per response instead of one: any peers found, a token per node
 * queried (needed to announce to it), and (when a node knows no peers yet) its "nodes"
 * fallback to keep the search moving. Kept as its own class rather than sharing a base
 * with NodeLookup - the two only really share the round/window control flow, and with
 * just these two call sites duplicating that (see design_docs/0028) reads more directly
 * than factoring out a generic skeleton for it would.
 *
 * <p>Also announces us as a peer to the closest nodes that responded, once the search
 * completes - the standard second half of a real DHT client's "look for peers" operation,
 * done here rather than left to a separate caller step, since every one of those nodes'
 * tokens only exist as long as this lookup already has them in hand.
 */
final class PeerLookup {

    private static final int ALPHA = 3;
    private static final int MAX_ROUNDS = 20;

    private final DhtNode dhtNode;
    private final InfoHash infoHash;
    private final NodeId target;
    private final Duration perQueryTimeout;

    private final Set<NodeId> queried = new HashSet<>();
    private final Map<NodeId, NodeInfo> shortlist = new HashMap<>();
    private final Map<NodeId, BString> tokens = new HashMap<>();
    private final Set<PeerAddress> peers = new LinkedHashSet<>();

    private PeerLookup(DhtNode dhtNode, InfoHash infoHash, Duration perQueryTimeout) {
        this.dhtNode = dhtNode;
        this.infoHash = infoHash;
        this.target = NodeId.of(infoHash.bytes());
        this.perQueryTimeout = perQueryTimeout;
    }

    /**
     * Runs an iterative get_peers lookup for infoHash and returns the deduplicated peers
     * found, then announces us as a peer for infoHash (on announcePort, or the packet's
     * own source port if announceImpliedPort is true) to the closest nodes that answered.
     */
    static List<PeerAddress> findPeers(DhtNode dhtNode, InfoHash infoHash, int announcePort,
            boolean announceImpliedPort, Duration perQueryTimeout) {
        PeerLookup lookup = new PeerLookup(dhtNode, infoHash, perQueryTimeout);
        lookup.run();
        lookup.announceToClosest(announcePort, announceImpliedPort);
        return List.copyOf(lookup.peers);
    }

    private void run() {
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
    }

    private List<NodeInfo> closestUnqueried(int window, int limit) {
        return shortlist.values().stream()
                .sorted(Comparator.comparing(node -> node.id().distanceTo(target)))
                .limit(window)
                .filter(node -> !queried.contains(node.id()))
                .limit(limit)
                .toList();
    }

    private void queryRound(ExecutorService executor, List<NodeInfo> candidates) {
        List<Future<Optional<GetPeersResult>>> futures = new ArrayList<>();
        for (NodeInfo candidate : candidates) {
            queried.add(candidate.id());
            futures.add(executor.submit(() -> queryOne(candidate)));
        }
        for (int i = 0; i < candidates.size(); i++) {
            NodeId candidateId = candidates.get(i).id();
            try {
                futures.get(i).get().ifPresent(result -> apply(candidateId, result));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // queryOne never throws (see its own catch) - unreachable in practice, but
                // treat it the same as "this candidate produced nothing" if it ever did.
            }
        }
    }

    private void apply(NodeId candidateId, GetPeersResult result) {
        tokens.put(candidateId, result.token());
        peers.addAll(result.peers());
        merge(result.nodes());
    }

    private Optional<GetPeersResult> queryOne(NodeInfo candidate) {
        try {
            InetSocketAddress address = new InetSocketAddress(candidate.address(), candidate.port());
            return Optional.of(dhtNode.getPeers(address, infoHash, perQueryTimeout));
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

    /** Only ever announces to a node we actually have a token for - i.e. one we
     * successfully queried ourselves, never one merely mentioned in another's response. */
    private void announceToClosest(int port, boolean impliedPort) {
        shortlist.values().stream()
                .filter(node -> tokens.containsKey(node.id()))
                .sorted(Comparator.comparing(node -> node.id().distanceTo(target)))
                .limit(RoutingTable.BUCKET_SIZE)
                .forEach(node -> announceTo(node, port, impliedPort));
    }

    /** Best-effort - one node not accepting our announce doesn't affect the others, and
     * the peers already found by run() are unaffected either way. */
    private void announceTo(NodeInfo node, int port, boolean impliedPort) {
        try {
            InetSocketAddress address = new InetSocketAddress(node.address(), node.port());
            dhtNode.announcePeer(address, infoHash, port, impliedPort, tokens.get(node.id()), perQueryTimeout);
        } catch (DhtException e) {
            // See method Javadoc.
        }
    }
}
