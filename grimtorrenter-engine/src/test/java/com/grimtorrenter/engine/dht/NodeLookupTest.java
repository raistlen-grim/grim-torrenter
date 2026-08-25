package com.grimtorrenter.engine.dht;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeLookupTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final List<DhtNode> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        nodes.forEach(DhtNode::close);
    }

    private static NodeId idWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return NodeId.of(bytes);
    }

    private DhtNode createNode(int idByte) {
        DhtNode node = new DhtNode(idWithLastByte(idByte), 0);
        nodes.add(node);
        return node;
    }

    private static NodeInfo contactOf(DhtNode node) {
        return new NodeInfo(node.ourId(), InetAddress.getLoopbackAddress(), node.port());
    }

    @Test
    void iterativeLookupDiscoversNodesBeyondDirectContacts() {
        DhtNode node1 = createNode(1);
        DhtNode node2 = createNode(2);
        DhtNode node3 = createNode(3);
        DhtNode node4 = createNode(4);
        DhtNode node5 = createNode(5);

        // A chain: node1 only knows node2, node2 only knows node3, and so on - node1 can
        // only discover node3/4/5 by iterating, not from its own direct contacts.
        node1.routingTable().insert(contactOf(node2));
        node2.routingTable().insert(contactOf(node3));
        node3.routingTable().insert(contactOf(node4));
        node4.routingTable().insert(contactOf(node5));

        List<NodeInfo> result = NodeLookup.run(node1, idWithLastByte(99), TIMEOUT);
        List<NodeId> discoveredIds = result.stream().map(NodeInfo::id).toList();

        assertTrue(discoveredIds.contains(node2.ourId()));
        assertTrue(discoveredIds.contains(node3.ourId()));
        assertTrue(discoveredIds.contains(node4.ourId()));
        assertTrue(discoveredIds.contains(node5.ourId()));
    }

    @Test
    void lookupFromAnEmptyRoutingTableFindsNothingWithoutHanging() {
        DhtNode lonely = createNode(1);

        List<NodeInfo> result = NodeLookup.run(lonely, idWithLastByte(99), TIMEOUT);

        assertEquals(List.of(), result);
    }

    @Test
    void resultIsSortedByDistanceAndCappedAtBucketSize() {
        DhtNode origin = createNode(0);
        NodeId target = idWithLastByte(0);
        // 10 direct contacts, all distinct distances from target - more than
        // RoutingTable.BUCKET_SIZE (8), so both the cap and the ordering are exercised.
        for (int i = 1; i <= 10; i++) {
            DhtNode contact = createNode(i);
            origin.routingTable().insert(contactOf(contact));
        }

        List<NodeInfo> result = NodeLookup.run(origin, target, TIMEOUT);

        assertEquals(RoutingTable.BUCKET_SIZE, result.size());
        for (int i = 1; i < result.size(); i++) {
            var previousDistance = result.get(i - 1).id().distanceTo(target);
            var currentDistance = result.get(i).id().distanceTo(target);
            assertTrue(previousDistance.compareTo(currentDistance) <= 0);
        }
    }
}
