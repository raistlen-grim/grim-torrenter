package com.grimtorrenter.engine.dht;

/** The four BEP 5 query types. Every query carries the querying node's own id, regardless
 * of what else it asks for - useful to callers that just want to record "who sent this"
 * without switching over which specific query it was. */
public sealed interface KrpcQuery extends KrpcMessage permits Ping, FindNode, GetPeers, AnnouncePeer {

    NodeId id();
}
