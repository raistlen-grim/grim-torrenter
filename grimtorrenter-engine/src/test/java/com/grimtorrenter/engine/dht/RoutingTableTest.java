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

    /** Every generated id must land in exactly the requested bucket - checked against the
     * same distanceTo/bitLength computation RoutingTable's own private bucketIndex() uses
     * internally, since that's the one thing randomIdInBucket() promises. Several bucket
     * indices (including both ends, 0 and 159) and several draws each, since the low bits
     * are randomized and a boundary-bit-position bug would only show up for some indices. */
    @Test
    void randomIdInBucketLandsInTheRequestedBucket() {
        RoutingTable table = new RoutingTable(OUR_ID);
        for (int bucketIndex : new int[] {0, 1, 7, 8, 79, 152, 158, 159}) {
            for (int attempt = 0; attempt < 20; attempt++) {
                NodeId id = table.randomIdInBucket(bucketIndex);
                int actual = OUR_ID.distanceTo(id).bitLength() - 1;
                assertEquals(bucketIndex, actual,
                        "bucketIndex " + bucketIndex + " produced id in bucket " + actual);
            }
        }
    }

    /** A never-touched bucket (still its initial Instant.EPOCH) is always more overdue than
     * one markRefreshed() has touched, however recently. */
    @Test
    void mostOverdueBucketPrefersANeverTouchedBucketOverARecentlyTouchedOne() {
        RoutingTable table = new RoutingTable(OUR_ID);
        table.markRefreshed(0);
        table.markRefreshed(1);

        assertEquals(2, table.mostOverdueBucket());
    }

    /** insert() touches its own bucket's refresh timestamp as a side effect - real activity
     * is itself evidence that bucket doesn't need a separate background refresh soon. Value
     * 16 lands in bucket 4 - see fullBucketReturnsLeastRecentlySeenContactInsteadOfAdding's
     * own comment on why last-byte values 16-23 share a bucket. */
    @Test
    void insertTouchesItsOwnBucketsRefreshTimestamp() {
        RoutingTable table = new RoutingTable(OUR_ID);
        table.insert(nodeWithLastByte(16, 6881));

        // Bucket 0 is still untouched (Instant.EPOCH), so it's more overdue than bucket 4,
        // which insert() above just refreshed.
        assertEquals(0, table.mostOverdueBucket());
    }

    @Test
    void allNodesReturnsEveryInsertedContactAcrossEveryBucket() {
        RoutingTable table = new RoutingTable(OUR_ID);
        NodeInfo near = nodeWithLastByte(0x01, 1);
        NodeInfo mid = nodeWithLastByte(0x10, 2);
        NodeInfo far = nodeWithLastByte(0x40, 3);
        table.insert(near);
        table.insert(mid);
        table.insert(far);

        assertEquals(List.of(near, mid, far).size(), table.allNodes().size());
        assertTrue(table.allNodes().containsAll(List.of(near, mid, far)));
    }
}
