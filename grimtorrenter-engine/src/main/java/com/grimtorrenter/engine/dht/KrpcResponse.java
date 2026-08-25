package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BString;

/**
 * A KRPC response ("y"="r"). Left generically typed (the raw "r" return-values
 * dictionary) rather than one record per query type, unlike KrpcQuery's variants - a
 * response's shape can't always be told apart from its bytes alone (a find_node response
 * and a peerless get_peers response are wire-identical apart from get_peers always adding
 * a "token"); only whichever query is pending for this transaction id knows what shape to
 * expect back, and that context lives with the caller (the future DhtNode), not the codec.
 */
public record KrpcResponse(BString transactionId, BDictionary returnValues) implements KrpcMessage {
}
