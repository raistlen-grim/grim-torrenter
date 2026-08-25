package com.grimtorrenter.engine.dht;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingTableTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final NodeId OUR_ID = NodeId.of(new byte[20]);

    private static NodeId idWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return NodeId.of(bytes);
    }

    private static NodeInfo nodeWithLastByte(int value, int port) {
        return new NodeInfo(idWithLastByte(value), LOOPBACK, port);
    }

    @Test
    void newTableIsEmpty() {
        RoutingTable table = new RoutingTable(OUR_ID);
        assertEquals(0, table.size());
        assertTrue(table.closestNodes(idWithLastByte(1), 8).isEmpty());
    }

    @Test
    void insertingOwnIdIsIgnored() {
        RoutingTable table = new RoutingTable(OUR_ID);
        assertEquals(Optional.empty(), table.insert(new NodeInfo(OUR_ID, LOOPBACK, 6881)));
        assertEquals(0, table.size());
    }

    @Test
    void insertingNewNodeAddsIt() {
        RoutingTable table = new RoutingTable(OUR_ID);
        NodeInfo node = nodeWithLastByte(1, 6881);

        assertEquals(Optional.empty(), table.insert(node));

        assertEquals(1, table.size());
        assertEquals(List.of(node), table.closestNodes(node.id(), 8));
    }

    @Test
    void reinsertingKnownNodeRefreshesRatherThanDuplicating() {
        RoutingTable table = new RoutingTable(OUR_ID);
        NodeId id = idWithLastByte(1);
        table.insert(new NodeInfo(id, LOOPBACK, 6881));

        NodeInfo refreshed = new NodeInfo(id, LOOPBACK, 6882);
        assertEquals(Optional.empty(), table.insert(refreshed));

        assertEquals(1, table.size());
        assertEquals(List.of(refreshed), table.closestNodes(id, 8));
    }

    @Test
    void fullBucketReturnsLeastRecentlySeenContactInsteadOfAdding() {
        RoutingTable table = new RoutingTable(OUR_ID);
        // Last-byte values 16-23 all share the same highest-set-bit position (bitLength
        // 5), so all 8 land in the same bucket - filling it exactly to BUCKET_SIZE.
        NodeInfo oldest = nodeWithLastByte(16, 6881);
        table.insert(oldest);
        for (int value = 17; value < 24; value++) {
            assertEquals(Optional.empty(), table.insert(nodeWithLastByte(value, 6800 + value)));
        }
        assertEquals(RoutingTable.BUCKET_SIZE, table.size());

        // A 9th node in the same bucket (still within 16-31): the bucket is full, so
        // insert() changes nothing and hands back its oldest entry to ping instead.
        NodeInfo ninth = nodeWithLastByte(24, 6924);
        assertEquals(Optional.of(oldest), table.insert(ninth));
        assertEquals(RoutingTable.BUCKET_SIZE, table.size());
    }

    @Test
    void evictingThenInsertingReplacesTheOldContact() {
        RoutingTable table = new RoutingTable(OUR_ID);
        NodeInfo oldest = nodeWithLastByte(16, 6881);
        table.insert(oldest);
        for (int value = 17; value < 24; value++) {
            table.insert(nodeWithLastByte(value, 6800 + value));
        }
        NodeInfo replacement = nodeWithLastByte(24, 6924);
        assertTrue(table.insert(replacement).isPresent());

        table.evict(oldest.id());
        assertEquals(Optional.empty(), table.insert(replacement));

        assertEquals(RoutingTable.BUCKET_SIZE, table.size());
        List<NodeInfo> closest = table.closestNodes(replacement.id(), RoutingTable.BUCKET_SIZE);
        assertTrue(closest.contains(replacement));
        assertFalse(closest.contains(oldest));
    }

    @Test
    void closestNodesAreOrderedByXorDistanceAndRespectCount() {
        RoutingTable table = new RoutingTable(OUR_ID);
        NodeInfo near = nodeWithLastByte(0x01, 1);
        NodeInfo mid = nodeWithLastByte(0x10, 2);
        NodeInfo far = nodeWithLastByte(0x40, 3);
        table.insert(far);
        table.insert(near);
        table.insert(mid);

        assertEquals(List.of(near, mid), table.closestNodes(OUR_ID, 2));
    }
}
