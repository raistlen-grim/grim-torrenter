package com.grimtorrenter.engine.peer;

/**
 * How this engine originally learned of a peer's address - orthogonal to connection direction
 * (see PeerConnection.incoming()), not combined with it: this answers "how did we find them,"
 * which only ever applies to an address *we* discovered and connected out to. An incoming
 * connection's source is always UNKNOWN - there's no way to know how a peer that connected to
 * *us* found *our* address, and it isn't the same question anyway.
 *
 * <p>UNKNOWN is also the default for every connect() overload that doesn't take an explicit
 * source (tests, mainly) - see design_docs/0066.
 */
public enum PeerSource {
    TRACKER, DHT, PEX, LSD, UNKNOWN
}
