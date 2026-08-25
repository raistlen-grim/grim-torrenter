package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.peerwire.PeerMessage;

public interface PeerConnectionListener {

    void onMessage(PeerConnection connection, PeerMessage message);

    /** cause is null for an intentional local close, non-null for a failure (I/O error or protocol violation). */
    void onDisconnected(PeerConnection connection, Throwable cause);
}
