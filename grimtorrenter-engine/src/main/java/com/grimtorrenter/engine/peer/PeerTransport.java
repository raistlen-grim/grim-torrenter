package com.grimtorrenter.engine.peer;

import java.io.IOException;
import java.net.InetAddress;

/**
 * The four operations PeerConnection actually needs from its underlying transport beyond the
 * InputStream/OutputStream pair it already treats independently (see this class's own
 * constructor Javadoc, and design_docs/0052's MSE precedent for that independence). Lets a real
 * java.net.Socket (SocketPeerTransport) and design_docs/0074's UtpSocket (UtpPeerTransport) both
 * satisfy PeerConnection's needs without it knowing which transport it's actually running over.
 *
 * <p>Deliberately lives in the peer package, not utp - the dependency runs one way (peer depends
 * on utp for UtpPeerTransport's own implementation), the same direction peer already depends on
 * mse/peerwire. utp itself never references this interface. See design_docs/0074's slice 2.
 */
public interface PeerTransport {

    InetAddress getInetAddress();

    int getPort();

    void setSoTimeout(int millis) throws IOException;

    void close() throws IOException;
}
