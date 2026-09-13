package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.utp.UtpSocket;

import java.io.IOException;
import java.net.InetAddress;

/** Delegates to UtpSocket's own slice-2 methods (design_docs/0074) - see PeerTransport's own
 * Javadoc for why this adapter lives here rather than UtpSocket implementing PeerTransport
 * directly. */
final class UtpPeerTransport implements PeerTransport {

    private final UtpSocket socket;

    UtpPeerTransport(UtpSocket socket) {
        this.socket = socket;
    }

    @Override
    public InetAddress getInetAddress() {
        return socket.remoteAddress().getAddress();
    }

    @Override
    public int getPort() {
        return socket.remoteAddress().getPort();
    }

    @Override
    public void setSoTimeout(int millis) {
        socket.setReceiveTimeoutMillis(millis);
    }

    @Override
    public void close() {
        socket.close();
    }
}
