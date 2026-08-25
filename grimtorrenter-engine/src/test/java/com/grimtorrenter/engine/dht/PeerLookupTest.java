package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerLookupTest {

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

    private static InfoHash infoHashWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return InfoHash.of(bytes);
    }

    private DhtNode createNode(int idByte) {
        DhtNode node = new DhtNode(idWithLastByte(idByte), 0);
        nodes.add(node);
        return node;
    }

    private static NodeInfo contactOf(DhtNode node) {
        return new NodeInfo(node.ourId(), InetAddress.getLoopbackAddress(), node.port());
    }

    private static InetSocketAddress addressOf(DhtNode node) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), node.port());
    }

    @Test
    void findsAPeerAnnouncedMultipleHopsAway() {
        DhtNode querier = createNode(1);
        DhtNode middle = createNode(2);
        DhtNode far = createNode(3);
        DhtNode seeder = createNode(4);
        InfoHash infoHash = infoHashWithLastByte(9);

        // querier only knows middle; middle only knows far - the announced peer is only
        // reachable by iterating two hops, not from querier's own direct contact.
        querier.routingTable().insert(contactOf(middle));
        middle.routingTable().insert(contactOf(far));

        // seeder announces itself (as if serving infoHash on port 51413) to far, via the
        // real get_peers-then-announce_peer sequence.
        BString token = seeder.getPeers(addressOf(far), infoHash, TIMEOUT).token();
        seeder.announcePeer(addressOf(far), infoHash, 51413, false, token, TIMEOUT);

        List<PeerAddress> found = PeerLookup.findPeers(querier, infoHash, 6969, false, TIMEOUT);

        assertTrue(found.contains(new PeerAddress(InetAddress.getLoopbackAddress(), 51413)));
    }

    @Test
    void lookupWithNoKnownContactsReturnsEmptyList() {
        DhtNode querier = createNode(1);

        List<PeerAddress> found = PeerLookup.findPeers(querier, infoHashWithLastByte(1), 6969, false, TIMEOUT);

        assertEquals(List.of(), found);
    }

    @Test
    void findPeersAlsoAnnouncesUsToTheRespondingNodes() {
        DhtNode querier = createNode(1);
        DhtNode responder = createNode(2);
        DhtNode checker = createNode(3);
        InfoHash infoHash = infoHashWithLastByte(5);
        querier.routingTable().insert(contactOf(responder));

        PeerLookup.findPeers(querier, infoHash, 7777, false, TIMEOUT);

        // Confirms the lookup's own side effect: a fresh get_peers from an unrelated node
        // now finds querier listed as a peer of responder's.
        GetPeersResult confirmation = checker.getPeers(addressOf(responder), infoHash, TIMEOUT);
        assertTrue(confirmation.peers().contains(new PeerAddress(InetAddress.getLoopbackAddress(), 7777)));
    }
}
