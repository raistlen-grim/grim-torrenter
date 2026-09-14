package com.grimtorrenter.engine.peer;

/**
 * Which transport a connection actually uses - orthogonal to {@link PeerSource} (how the
 * address was discovered) and to {@code incoming}/{@code outgoing} direction, the same "several
 * independent facts about one connection" shape design_docs/0066 already established. See
 * {@link PeerConnection#transportType()}.
 */
public enum PeerTransportType {
    TCP, UTP
}
