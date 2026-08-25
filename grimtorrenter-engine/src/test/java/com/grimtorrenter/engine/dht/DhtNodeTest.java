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
}
