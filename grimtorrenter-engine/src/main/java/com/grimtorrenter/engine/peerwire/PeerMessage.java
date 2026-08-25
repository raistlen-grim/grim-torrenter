package com.grimtorrenter.engine.peerwire;

public sealed interface PeerMessage permits
        KeepAlive, Choke, Unchoke, Interested, NotInterested,
        Have, Bitfield, Request, Piece, Cancel, Port, Extended {
}
