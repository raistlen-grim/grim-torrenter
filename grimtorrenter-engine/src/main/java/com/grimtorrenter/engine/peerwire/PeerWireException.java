package com.grimtorrenter.engine.peerwire;

/** A protocol violation in already-received bytes - not an I/O failure (see PeerWireCodec). */
public class PeerWireException extends RuntimeException {

    public PeerWireException(String message) {
        super(message);
    }
}
