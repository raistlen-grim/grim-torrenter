package com.grimtorrenter.engine.mse;

import java.io.IOException;

/**
 * MSE negotiation failed - the peer never sent a recognizable MSE synchronization point (not
 * an MSE-capable peer, or a garbled/hostile stream), no candidate torrent's info hash matched,
 * or no mutually acceptable crypto method existed (e.g. this side requires RC4 but the peer
 * only offered plaintext). Extends IOException deliberately - PeerConnection.connect() and
 * PeerServer.handleConnection() already close the socket on any IOException from their
 * existing handshake code, so this composes into that behavior with no special-casing needed.
 * See design_docs/0052.
 */
public final class MseNegotiationException extends IOException {

    public MseNegotiationException(String message) {
        super(message);
    }
}
