package com.grimtorrenter.engine.peer;

/**
 * A connection-establishment semantic failure (e.g. handshake info hash
 * mismatch) - distinct from PeerWireException, which is pure wire-format
 * decoding with no knowledge of which torrent/connection it belongs to.
 */
public class PeerConnectionException extends RuntimeException {

    public PeerConnectionException(String message) {
        super(message);
    }
}
