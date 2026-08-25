package com.grimtorrenter.engine.peerwire;

/**
 * BEP 5 DHT port announcement. Not acted on until Phase 2 (see
 * design_docs/0009), but modeled now so a real peer sending this
 * (extremely common - DHT is near-universal) decodes cleanly in Phase 1
 * instead of being an unknown-message-id protocol error.
 */
public record Port(int listenPort) implements PeerMessage {
}
