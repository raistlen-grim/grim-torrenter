package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.tracker.PeerAddress;

import java.util.List;

/** The parsed result of a single get_peers query to one node: always a token (needed for
 * a follow-up announce_peer to that same node), and either some peers it already knows
 * about or (if it knows none) the nodes closest to the info hash it was asked about - see
 * KrpcResponse's own Javadoc for why this shape isn't decided by the codec itself. */
record GetPeersResult(BString token, List<PeerAddress> peers, List<NodeInfo> nodes) {
}
