package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.utp.UtpSocket;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;

/**
 * The µTP counterpart to what {@link PeerServer#handleConnection} does once a TCP connection is
 * accepted (design_docs/0074's slice 3): read the remote's BitTorrent handshake, route it to
 * whatever handlerLookup resolves to for its info hash, or close it if nothing does. Given an
 * already uTP-handshake-completed {@link UtpSocket} by {@code utp.UtpAcceptor} (already running
 * on its own dedicated virtual thread there, so blocking here to read a handshake is fine - this
 * class spawns no threads of its own).
 *
 * <p><b>Deliberately skips PeerServer's plaintext-vs-MSE branch entirely</b> - µTP traffic
 * already looks like ordinary UDP to DPI (the whole point of MSE is defeating naive TCP
 * traffic-shaping), so real clients (e.g. libtorrent) send a plain BitTorrent handshake over µTP
 * with no MSE negotiation. This does the same: a stated simplification, not an oversight.
 */
public final class UtpPeerAcceptor {

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000; // matches PeerServer's own constant

    private final Function<InfoHash, Optional<UtpIncomingConnectionHandler>> handlerLookup;

    public UtpPeerAcceptor(Function<InfoHash, Optional<UtpIncomingConnectionHandler>> handlerLookup) {
        this.handlerLookup = handlerLookup;
    }

    public void accept(UtpSocket utpSocket) {
        try {
            utpSocket.setReceiveTimeoutMillis(HANDSHAKE_TIMEOUT_MS);
            Handshake handshake = PeerWireCodec.readHandshake(utpSocket.getInputStream());
            Optional<UtpIncomingConnectionHandler> handler = handlerLookup.apply(handshake.infoHash());
            if (handler.isEmpty()) {
                utpSocket.close();
                return;
            }
            // Handshake done - back to blocking forever, the same policy PeerConnection's own
            // IDLE_READ_TIMEOUT_MS/HANDSHAKE_TIMEOUT_MS logic takes over from here.
            utpSocket.setReceiveTimeoutMillis(0);
            handler.get().accept(utpSocket, handshake);
        } catch (IOException | RuntimeException e) {
            utpSocket.close();
        }
    }
}
