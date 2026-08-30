package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhtNodeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

    private static final NodeId NODE_A_ID = idWithLastByte(1);
    private static final NodeId NODE_B_ID = idWithLastByte(2);

    private static NodeId idWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return NodeId.of(bytes);
    }

    private static InetSocketAddress addressOf(DhtNode node) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), node.port());
    }

    private DhtNode nodeA;
    private DhtNode nodeB;

    @AfterEach
    void tearDown() {
        if (nodeA != null) {
            nodeA.close();
        }
        if (nodeB != null) {
            nodeB.close();
        }
    }

    @Test
    void pingSucceedsAndPopulatesBothRoutingTables() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        nodeB = new DhtNode(NODE_B_ID, 0);

        nodeA.ping(addressOf(nodeB), TIMEOUT);

        assertEquals(
                List.of(new NodeInfo(NODE_A_ID, InetAddress.getLoopbackAddress(), nodeA.port())),
                nodeB.routingTable().closestNodes(NODE_A_ID, 1));
        assertEquals(
                List.of(new NodeInfo(NODE_B_ID, InetAddress.getLoopbackAddress(), nodeB.port())),
                nodeA.routingTable().closestNodes(NODE_B_ID, 1));
    }

    @Test
    void findNodeReturnsTheRespondersClosestKnownContacts() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        nodeB = new DhtNode(NODE_B_ID, 0);
        NodeInfo known = new NodeInfo(idWithLastByte(3), InetAddress.getLoopbackAddress(), 9999);
        nodeB.routingTable().insert(known);

        List<NodeInfo> result = nodeA.findNode(addressOf(nodeB), idWithLastByte(3), TIMEOUT);

        assertTrue(result.contains(known));
    }

    @Test
    void queryToUnreachableAddressTimesOut() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        // Nothing bound on this port on loopback.
        InetSocketAddress unreachable = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);

        assertThrows(DhtException.class, () -> nodeA.ping(unreachable, SHORT_TIMEOUT));
    }

    @Test
    void malformedPacketIsIgnoredAndReceiveLoopKeepsRunning() throws IOException {
        nodeA = new DhtNode(NODE_A_ID, 0);
        nodeB = new DhtNode(NODE_B_ID, 0);

        try (DatagramSocket rogue = new DatagramSocket()) {
            byte[] garbage = "not bencode".getBytes();
            rogue.send(new DatagramPacket(garbage, garbage.length, addressOf(nodeA)));
        }

        // If the garbage packet had killed nodeA's receive loop, this would time out.
        nodeB.ping(addressOf(nodeA), TIMEOUT);
        assertEquals(1, nodeA.routingTable().size());
    }

    private static InfoHash infoHashWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return InfoHash.of(bytes);
    }

    /** Sends a hand-built KrpcQuery straight over a plain socket, bypassing DhtNode's own
     * client API entirely - this slice only adds server-side get_peers/announce_peer
     * handling (see design_docs/0028), so a raw socket stands in for "some other DHT node"
     * until slice 5 gives DhtNode itself a client-side get_peers/announce_peer. */
    private static KrpcMessage sendRawQuery(DatagramSocket raw, InetSocketAddress to, KrpcQuery query)
            throws IOException {
        byte[] out = KrpcCodec.encode(query);
        raw.send(new DatagramPacket(out, out.length, to));
        byte[] buffer = new byte[2048];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        raw.setSoTimeout((int) TIMEOUT.toMillis());
        raw.receive(response);
        return KrpcCodec.decode(Arrays.copyOf(response.getData(), response.getLength()));
    }

    private static BString getToken(DatagramSocket raw, InetSocketAddress to, InfoHash infoHash) throws IOException {
        GetPeers query = new GetPeers(BString.of(new byte[]{0x00}), idWithLastByte(9), infoHash);
        KrpcMessage response = sendRawQuery(raw, to, query);
        return (BString) ((KrpcResponse) response).returnValues().get("token");
    }

    @Test
    void getPeersWithNoKnownPeersFallsBackToNodesAndIssuesAToken() throws IOException {
        nodeA = new DhtNode(NODE_A_ID, 0);
        NodeInfo known = new NodeInfo(idWithLastByte(3), InetAddress.getLoopbackAddress(), 9999);
        nodeA.routingTable().insert(known);

        try (DatagramSocket raw = new DatagramSocket()) {
            GetPeers query = new GetPeers(BString.of(new byte[]{0x01}), idWithLastByte(9), infoHashWithLastByte(1));
            KrpcMessage message = sendRawQuery(raw, addressOf(nodeA), query);

            assertTrue(message instanceof KrpcResponse);
            BDictionary values = ((KrpcResponse) message).returnValues();
            assertTrue(values.get("token") instanceof BString);
            assertTrue(values.get("nodes") instanceof BString);
            assertNull(values.get("values"));
        }
    }

    @Test
    void announcePeerWithValidTokenIsRecordedAndSurfacedByGetPeers() throws IOException {
        nodeA = new DhtNode(NODE_A_ID, 0);
        InfoHash infoHash = infoHashWithLastByte(2);

        try (DatagramSocket raw = new DatagramSocket()) {
            InetSocketAddress to = addressOf(nodeA);
            BString token = getToken(raw, to, infoHash);

            AnnouncePeer announce = new AnnouncePeer(
                    BString.of(new byte[]{0x02}), idWithLastByte(9), infoHash, false, 51413, token);
            KrpcMessage announceResponse = sendRawQuery(raw, to, announce);
            assertTrue(announceResponse instanceof KrpcResponse);

            GetPeers followUp = new GetPeers(BString.of(new byte[]{0x03}), idWithLastByte(9), infoHash);
            BDictionary values = ((KrpcResponse) sendRawQuery(raw, to, followUp)).returnValues();

            assertTrue(values.get("values") instanceof BList);
            byte[] expectedPeerBytes = {127, 0, 0, 1, (byte) (51413 >> 8), (byte) 51413};
            assertTrue(((BList) values.get("values")).values().contains(BString.of(expectedPeerBytes)));
        }
    }

    @Test
    void announcePeerWithImpliedPortUsesTheActualSourcePortNotTheDeclaredOne() throws IOException {
        nodeA = new DhtNode(NODE_A_ID, 0);
        InfoHash infoHash = infoHashWithLastByte(3);

        try (DatagramSocket raw = new DatagramSocket()) {
            InetSocketAddress to = addressOf(nodeA);
            BString token = getToken(raw, to, infoHash);
            int actualSourcePort = raw.getLocalPort();

            // Declared port is deliberately wrong - impliedPort=true means it must be
            // ignored in favor of the UDP packet's actual source port.
            AnnouncePeer announce = new AnnouncePeer(
                    BString.of(new byte[]{0x04}), idWithLastByte(9), infoHash, true, 1, token);
            sendRawQuery(raw, to, announce);

            GetPeers followUp = new GetPeers(BString.of(new byte[]{0x05}), idWithLastByte(9), infoHash);
            BDictionary values = ((KrpcResponse) sendRawQuery(raw, to, followUp)).returnValues();

            byte[] expectedPeerBytes = {127, 0, 0, 1, (byte) (actualSourcePort >> 8), (byte) actualSourcePort};
            assertTrue(((BList) values.get("values")).values().contains(BString.of(expectedPeerBytes)));
        }
    }

    @Test
    void announcePeerWithBadTokenReturnsError() throws IOException {
        nodeA = new DhtNode(NODE_A_ID, 0);
        InfoHash infoHash = infoHashWithLastByte(4);

        try (DatagramSocket raw = new DatagramSocket()) {
            BString bogusToken = BString.of(new byte[]{9, 9, 9, 9});
            AnnouncePeer announce = new AnnouncePeer(
                    BString.of(new byte[]{0x06}), idWithLastByte(9), infoHash, false, 51413, bogusToken);
            KrpcMessage response = sendRawQuery(raw, addressOf(nodeA), announce);

            assertTrue(response instanceof KrpcError);
            assertEquals(203, ((KrpcError) response).code());
        }
    }

    @Test
    void getPeersAndAnnouncePeerRoundTripThroughRealClientMethods() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        nodeB = new DhtNode(NODE_B_ID, 0);
        InfoHash infoHash = infoHashWithLastByte(7);

        GetPeersResult before = nodeA.getPeers(addressOf(nodeB), infoHash, TIMEOUT);
        assertTrue(before.peers().isEmpty());

        nodeA.announcePeer(addressOf(nodeB), infoHash, 12345, false, before.token(), TIMEOUT);

        GetPeersResult after = nodeA.getPeers(addressOf(nodeB), infoHash, TIMEOUT);
        assertTrue(after.peers().contains(new PeerAddress(InetAddress.getLoopbackAddress(), 12345)));
    }

    @Test
    void announcePeerWithClientMethodAndBadTokenThrows() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        nodeB = new DhtNode(NODE_B_ID, 0);
        InfoHash infoHash = infoHashWithLastByte(8);
        BString bogusToken = BString.of(new byte[]{1, 2, 3, 4});

        assertThrows(DhtException.class,
                () -> nodeA.announcePeer(addressOf(nodeB), infoHash, 12345, false, bogusToken, TIMEOUT));
    }

    /** Polls up to 7s (comfortably past DhtNode's own 5s REPLACEMENT_PING_TIMEOUT) - the
     * ping-then-evict-or-refresh replacement policy runs on its own virtual thread, never
     * inline within seen(), so its effect on the routing table isn't visible immediately
     * after the query that triggered it returns. */
    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 7000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /** design_docs/0028's own 2026-08-30 addendum: a full bucket's least-recently-seen
     * contact gets pinged before eviction, not discarded outright. Fills one of nodeA's
     * buckets with 8 contacts nobody's listening on (loopback port 1, same "nothing bound
     * here" convention queryToUnreachableAddressTimesOut above uses), then has a real live
     * node (nodeB, same bucket - see RoutingTableTest's own comment on last-byte values
     * 16-23 sharing a bucket) ping nodeA, triggering seen() with that bucket already full.
     * The stale contact's ping should time out, so it gets evicted and nodeB takes its
     * place. */
    @Test
    void fullBucketEvictsAnUnreachableStaleContactForARealCandidate() throws InterruptedException {
        nodeA = new DhtNode(NODE_A_ID, 0);
        for (int value = 16; value < 24; value++) {
            nodeA.routingTable().insert(new NodeInfo(idWithLastByte(value), InetAddress.getLoopbackAddress(), 1));
        }

        NodeId candidateId = idWithLastByte(24);
        nodeB = new DhtNode(candidateId, 0);
        nodeB.ping(addressOf(nodeA), TIMEOUT);

        awaitTrue(() -> nodeA.routingTable().closestNodes(candidateId, RoutingTable.BUCKET_SIZE).stream()
                .anyMatch(node -> node.id().equals(candidateId)));

        List<NodeInfo> bucketContents = nodeA.routingTable().closestNodes(candidateId, RoutingTable.BUCKET_SIZE);
        assertEquals(RoutingTable.BUCKET_SIZE, bucketContents.size());
        assertTrue(bucketContents.stream().anyMatch(node -> node.id().equals(candidateId)));
        assertTrue(bucketContents.stream().noneMatch(node -> node.id().equals(idWithLastByte(16))));
    }

    /** Symmetric case: the stale contact answers the replacement ping, so it's refreshed
     * (kept) and the new candidate is simply not added this round - matching BEP 5. */
    @Test
    void fullBucketKeepsAStaleContactThatAnswersTheReplacementPing() throws InterruptedException {
        nodeA = new DhtNode(NODE_A_ID, 0);
        DhtNode staleButLive = new DhtNode(idWithLastByte(16), 0);
        try {
            nodeA.routingTable().insert(
                    new NodeInfo(staleButLive.ourId(), InetAddress.getLoopbackAddress(), staleButLive.port()));
            for (int value = 17; value < 24; value++) {
                nodeA.routingTable().insert(
                        new NodeInfo(idWithLastByte(value), InetAddress.getLoopbackAddress(), 1));
            }

            NodeId candidateId = idWithLastByte(24);
            nodeB = new DhtNode(candidateId, 0);
            nodeB.ping(addressOf(nodeA), TIMEOUT);

            // The stale contact is genuinely reachable, so its replacement ping resolves
            // quickly - no need to wait anywhere near REPLACEMENT_PING_TIMEOUT.
            Thread.sleep(500);

            List<NodeInfo> bucketContents =
                    nodeA.routingTable().closestNodes(idWithLastByte(16), RoutingTable.BUCKET_SIZE);
            assertTrue(bucketContents.stream().anyMatch(node -> node.id().equals(idWithLastByte(16))));
            assertTrue(bucketContents.stream().noneMatch(node -> node.id().equals(candidateId)));
        } finally {
            staleButLive.close();
        }
    }

    /** design_docs/0028's own 2026-08-30 addendum: refreshRoutingTable() reaches a node
     * that's only known indirectly, through a bridge - the same multi-hop shape a real
     * bucket refresh needs to actually fill in sparse parts of the id space. On a fresh
     * table, mostOverdueBucket() is deterministically bucket 0 (see RoutingTableTest), and
     * randomIdInBucket(0) is deterministic too (bucket 0 differs from ourId only in the
     * least significant bit, with no lower bits left to randomize) - so with
     * NODE_A_ID = idWithLastByte(1), the refresh target is always exactly idWithLastByte(0),
     * no randomness to account for. bridgeNode knows target directly (inserted, bypassing
     * the network - same technique findNodeReturnsTheRespondersClosestKnownContacts above
     * uses); nodeA only knows bridgeNode. A single find_node hop through bridgeNode should
     * surface target as a candidate, nodeA queries it directly, and its response's own
     * sender reaches nodeA's routing table via seen() - the standard "sender only" hygiene
     * rule (design_docs/0028), same as every other DHT test in this package relies on.
     *
     * <p>Padded with 7 more directly-inserted contacts in an unrelated bucket (last-byte
     * values 64-70, bitLength 7 -> bucket 6, distinct from target's bucket 0 and bridgeNode's
     * bucket 2) purely to clear MIN_HEALTHY_NODE_COUNT (8) - otherwise refreshRoutingTable()
     * would take the sparse-table bootstrap-retry branch below instead of the per-bucket
     * refresh path this test means to exercise. They're never pinged (their own bucket is
     * nowhere near full), so they don't affect the assertions below. */
    @Test
    void refreshRoutingTableDiscoversARealNodeThroughAKnownBridge() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        NodeId target = idWithLastByte(0);

        DhtNode targetNode = new DhtNode(target, 0);
        DhtNode bridgeNode = new DhtNode(idWithLastByte(5), 0);
        try {
            bridgeNode.routingTable().insert(
                    new NodeInfo(targetNode.ourId(), InetAddress.getLoopbackAddress(), targetNode.port()));
            nodeA.routingTable().insert(
                    new NodeInfo(bridgeNode.ourId(), InetAddress.getLoopbackAddress(), bridgeNode.port()));
            for (int value = 64; value < 71; value++) {
                nodeA.routingTable().insert(new NodeInfo(idWithLastByte(value), InetAddress.getLoopbackAddress(), 1));
            }

            nodeA.refreshRoutingTable();

            List<NodeInfo> closest = nodeA.routingTable().closestNodes(target, 1);
            assertEquals(1, closest.size());
            assertEquals(target, closest.get(0).id());
        } finally {
            targetNode.close();
            bridgeNode.close();
        }
    }

    /** design_docs/0028's own 2026-08-30 addendum: a real, observed failure mode - if
     * bootstrap only ever partially succeeds (a network's flaky reachability to the
     * well-known hosts, not a bug), the table can be left with too few contacts for a
     * targeted single-bucket refresh to ever meaningfully recover from (NodeLookup always
     * seeds itself from what's already known). Below MIN_HEALTHY_NODE_COUNT,
     * refreshRoutingTable() re-runs full bootstrap instead - this only smoke-tests that the
     * sparse-table branch is reachable and doesn't throw, same "tolerated, real internet
     * access" acceptance TorrentEngineMagnetTest's own DHT-enabled tests already document,
     * since real bootstrap success/failure here is inherently network-dependent. */
    @Test
    void refreshRoutingTableReRunsBootstrapWhenTheTableIsStillSparse() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        assertTrue(nodeA.routingTable().size() < RoutingTable.BUCKET_SIZE);

        assertDoesNotThrow(nodeA::refreshRoutingTable);
    }

    /** design_docs/0028's own 2026-08-30 addendum: the warm-start overload used to seed from
     * a previously-persisted routing table - a real, reachable contact is pinged and, once it
     * answers, reaches nodeA's routing table via seen() exactly like any other directly-heard-
     * from node (no special-casing for "this came from a persisted list" - the verification
     * ping is what actually establishes trust). */
    @Test
    void bootstrapWithAdditionalContactsPingsAndAddsAReachableOne() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        nodeB = new DhtNode(NODE_B_ID, 0);

        nodeA.bootstrap(List.of(addressOf(nodeB)));

        assertTrue(nodeA.routingTable().closestNodes(NODE_B_ID, 1)
                .contains(new NodeInfo(NODE_B_ID, InetAddress.getLoopbackAddress(), nodeB.port())));
    }

    /** Symmetric case: an unreachable persisted contact (same "nothing bound here" convention
     * used elsewhere in this file) is tolerated - no exception, and it never reaches the
     * routing table itself, same as any bootstrap host that doesn't answer. Doesn't assert the
     * whole table stays empty - this overload always falls through to the real bootstrap()
     * afterward (same "tolerated real internet access" acceptance already documented
     * elsewhere), which could legitimately add real, unrelated contacts of its own; a loopback
     * address on port 1 could never coincide with one of those, so checking for that specific
     * (address, port) is still a precise, non-flaky check. */
    @Test
    void bootstrapWithAdditionalContactsToleratesAnUnreachableOne() {
        nodeA = new DhtNode(NODE_A_ID, 0);
        InetSocketAddress unreachable = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);

        assertDoesNotThrow(() -> nodeA.bootstrap(List.of(unreachable)));

        assertTrue(nodeA.routingTable().allNodes().stream()
                .noneMatch(node -> node.address().equals(InetAddress.getLoopbackAddress()) && node.port() == 1));
    }
}
