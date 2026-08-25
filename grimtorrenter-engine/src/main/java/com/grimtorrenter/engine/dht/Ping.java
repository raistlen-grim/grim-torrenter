package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;

/** BEP 5 "ping" query - the simplest KRPC query, carries only the sender's own node id. */
public record Ping(BString transactionId, NodeId id) implements KrpcQuery {
}
