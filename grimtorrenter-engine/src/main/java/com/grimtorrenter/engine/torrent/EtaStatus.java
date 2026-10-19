package com.grimtorrenter.engine.torrent;

/**
 * Why a torrent has the ETA it has - or why it has none. Checked in declaration order; the first
 * that applies wins. See design_docs/0102.
 */
public enum EtaStatus {
    /** Nothing wanted remains. */
    COMPLETE,
    /** Paused, stopped, errored, verifying or starting - not downloading at all. */
    NOT_RUNNING,
    /** Running, but connected to nobody. */
    NO_PEERS,
    /** Some wanted pieces aren't held by any connected peer: it can't finish with this swarm. Wins
     * over a moving rate on purpose - a time estimate would be a promise the swarm can't keep. */
    UNAVAILABLE,
    /** Peers have what we need, but every one is choking us. */
    CHOKED,
    /** None of the above, and nothing has arrived in the last minute. */
    STALLED,
    /** A real estimate: Eta.seconds is set. */
    ESTIMATE
}
