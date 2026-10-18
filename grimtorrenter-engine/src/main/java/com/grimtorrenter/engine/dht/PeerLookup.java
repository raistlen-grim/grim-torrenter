package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerAddress;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BEP 5's iterative get_peers lookup (see IterativeLookup). Collects three things per response:
 * any peers found, a token per node queried (needed to announce to it), and the closer nodes
 * that keep the search moving when a node knows no peers yet.
 *
 * <p>Also announces us as a peer to the closest nodes that answered, once the search completes:
 * the standard second half of a DHT client's "look for peers", done here because the tokens
 * only exist while this lookup holds them.
 */
final class PeerLookup {

    private final DhtNode dhtNode;
    private final InfoHash infoHash;
    private final Duration perQueryTimeout;
    private final Map<NodeId, BString> tokens = new HashMap<>();
    private final Set<PeerAddress> peers = new LinkedHashSet<>();
    private final IterativeLookup<GetPeersResult> lookup;

    private PeerLookup(DhtNode dhtNode, InfoHash infoHash, Duration perQueryTimeout) {
        this.dhtNode = dhtNode;
        this.infoHash = infoHash;
        this.perQueryTimeout = perQueryTimeout;
        this.lookup = new IterativeLookup<>(dhtNode, NodeId.of(infoHash.bytes()),
                address -> dhtNode.getPeers(address, infoHash, perQueryTimeout),
                this::apply);
    }

    /**
     * Runs an iterative get_peers lookup for infoHash and returns the deduplicated peers
     * found, then announces us as a peer for infoHash (on announcePort, or the packet's
     * own source port if announceImpliedPort is true) to the closest nodes that answered.
     */
    static List<PeerAddress> findPeers(DhtNode dhtNode, InfoHash infoHash, int announcePort,
            boolean announceImpliedPort, Duration perQueryTimeout) {
        PeerLookup peerLookup = new PeerLookup(dhtNode, infoHash, perQueryTimeout);
        peerLookup.lookup.run();
        peerLookup.announceToClosest(announcePort, announceImpliedPort);
        return List.copyOf(peerLookup.peers);
    }

    private List<NodeInfo> apply(NodeId from, GetPeersResult result) {
        tokens.put(from, result.token());
        peers.addAll(result.peers());
        return result.nodes();
    }

    /** Only ever announces to a node we actually have a token for - i.e. one we
     * successfully queried ourselves, never one merely mentioned in another's response. */
    private void announceToClosest(int port, boolean impliedPort) {
        lookup.closest(node -> tokens.containsKey(node.id()), RoutingTable.BUCKET_SIZE)
                .forEach(node -> announceTo(node, port, impliedPort));
    }

    /** Best-effort - one node not accepting our announce doesn't affect the others, and
     * the peers already found are unaffected either way. */
    private void announceTo(NodeInfo node, int port, boolean impliedPort) {
        try {
            InetSocketAddress address = new InetSocketAddress(node.address(), node.port());
            dhtNode.announcePeer(address, infoHash, port, impliedPort, tokens.get(node.id()), perQueryTimeout);
        } catch (DhtException e) {
            // See method Javadoc.
        }
    }
}
