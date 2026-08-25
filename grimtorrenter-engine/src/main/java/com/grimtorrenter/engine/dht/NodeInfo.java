package com.grimtorrenter.engine.dht;

import java.net.InetAddress;

/** A DHT node's contact info - its id plus where to reach it. The same shape BEP 5's
 * compact node info carries on the wire, though encoding/decoding that wire format is
 * deferred to whichever later slice first needs to read or write it. */
public record NodeInfo(NodeId id, InetAddress address, int port) {
}
