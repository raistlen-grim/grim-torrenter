package com.grimtorrenter.engine.peer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/** Trivial delegating wrapper - PeerConnection's original, only-ever transport before
 * design_docs/0074. See PeerTransport's own Javadoc. */
final class SocketPeerTransport implements PeerTransport {

    private final Socket socket;

    SocketPeerTransport(Socket socket) {
        this.socket = socket;
    }

    @Override
    public InetAddress getInetAddress() {
        return socket.getInetAddress();
    }

    @Override
    public int getPort() {
        return socket.getPort();
    }

    @Override
    public void setSoTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
