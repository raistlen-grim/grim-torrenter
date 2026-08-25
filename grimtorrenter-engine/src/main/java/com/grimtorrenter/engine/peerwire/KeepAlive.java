package com.grimtorrenter.engine.peerwire;

/** The zero-length message (no message id, no payload). */
public record KeepAlive() implements PeerMessage {
}
