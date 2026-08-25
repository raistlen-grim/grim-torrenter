package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.metainfo.InfoHash;

/** BEP 5 "get_peers" query - asks the recipient for peers it knows about for infoHash, or
 * (if it knows none) the contact info of the nodes in its routing table closest to it. */
public record GetPeers(BString transactionId, NodeId id, InfoHash infoHash) implements KrpcQuery {
}
