package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.utp.UtpSocket;

import java.io.IOException;

/**
 * The µTP counterpart to {@link IncomingConnectionHandler} (design_docs/0074's slice 3) - handed
 * a µTP connection that has already completed its own wire-level handshake and the remote's
 * already-read BitTorrent handshake, by {@link UtpPeerAcceptor}, once it's found something
 * willing to own a connection for that handshake's info hash. No separate stream pair - unlike a
 * plain {@code Socket}, {@link UtpSocket} always exposes the same single-instance stream pair
 * itself, and there's no MSE branch to choose between (see {@code UtpPeerAcceptor}'s own Javadoc
 * for why). Implementations take full ownership of utpSocket from here, same as
 * {@code IncomingConnectionHandler}.
 *
 * <p>Deliberately doesn't reference TorrentSession/TorrentEngine, for the same reason
 * {@code IncomingConnectionHandler} doesn't - see design_docs/0006.
 */
@FunctionalInterface
public interface UtpIncomingConnectionHandler {

    void accept(UtpSocket utpSocket, Handshake handshake) throws IOException;
}
