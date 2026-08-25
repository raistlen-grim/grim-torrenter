package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;

/** A BEP 5 KRPC message - a query, a response, or an error. Every kind carries a
 * transaction id ("t"), used to match a response/error back to the query that caused it. */
public sealed interface KrpcMessage permits KrpcQuery, KrpcResponse, KrpcError {

    BString transactionId();
}
