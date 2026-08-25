package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.metainfo.InfoHash;

/**
 * BEP 5 "announce_peer" query - tells the recipient we're a peer for infoHash, reachable
 * on port (or, if impliedPort is true, on whatever source port this UDP packet was
 * actually sent from - for a peer behind NAT that can't reliably self-report its
 * externally reachable port). token must be one the recipient handed back in an earlier
 * get_peers response to us - proves this announce follows a lookup rather than being
 * unsolicited, and is opaque to us; we only ever echo one back verbatim.
 */
public record AnnouncePeer(
        BString transactionId, NodeId id, InfoHash infoHash, boolean impliedPort, int port, BString token)
        implements KrpcQuery {
}
