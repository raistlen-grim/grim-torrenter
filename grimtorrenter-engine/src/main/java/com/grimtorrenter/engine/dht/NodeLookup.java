package com.grimtorrenter.engine.dht;

import java.time.Duration;
import java.util.List;

/**
 * BEP 5's iterative find_node lookup (see IterativeLookup). Also how a node's routing table
 * grows beyond its direct contacts: every node this queries that answers is inserted by DhtNode
 * as a side effect. Used by Bootstrap and the periodic bucket refresh.
 */
final class NodeLookup {

    private NodeLookup() {
    }

    /** Runs a full iterative find_node lookup for target against dhtNode, returning up to
     * RoutingTable.BUCKET_SIZE of the closest nodes found (by XOR distance to target). */
    static List<NodeInfo> run(DhtNode dhtNode, NodeId target, Duration perQueryTimeout) {
        IterativeLookup<List<NodeInfo>> lookup = new IterativeLookup<>(dhtNode, target,
                address -> dhtNode.findNode(address, target, perQueryTimeout),
                (from, closer) -> closer);
        lookup.run();
        return lookup.closest(node -> true, RoutingTable.BUCKET_SIZE);
    }
}
