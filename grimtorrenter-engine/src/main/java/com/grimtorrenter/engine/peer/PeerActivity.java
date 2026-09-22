package com.grimtorrenter.engine.peer;

/**
 * A one-word summary of what a connected peer is doing for us right now - derived by
 * {@link PeerConnection#activity()}, not a protocol concept. Lets any client show a peer's
 * health at a glance without recomputing it from raw choke/interest flags and byte counters.
 * See design_docs/0076.
 */
public enum PeerActivity {
    /** Block data moved in either direction within the last {@link PeerConnection#ACTIVITY_WINDOW_MILLIS}. */
    ACTIVE,
    /** Nothing moved recently, but we want something from this peer (we're interested in
     * them) - choked, or asked and not yet answered. */
    WAITING,
    /** Nothing moving and nothing we want from them - e.g. a peer that only has data we
     * already have. */
    IDLE
}
