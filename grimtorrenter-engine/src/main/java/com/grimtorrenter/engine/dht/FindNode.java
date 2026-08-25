package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;

/** BEP 5 "find_node" query - asks the recipient for the contact info of the nodes in its
 * routing table closest to target. */
public record FindNode(BString transactionId, NodeId id, NodeId target) implements KrpcQuery {
}
