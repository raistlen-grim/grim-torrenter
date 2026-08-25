package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;

/** A KRPC error ("y"="e") - e.g. a malformed query, or an announce_peer with a bad token. */
public record KrpcError(BString transactionId, long code, String message) implements KrpcMessage {
}
