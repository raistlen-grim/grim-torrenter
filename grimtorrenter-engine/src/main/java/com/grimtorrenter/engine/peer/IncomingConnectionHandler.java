package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.peerwire.Handshake;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Handed a still-open socket, the stream pair to actually use for it (the socket's own raw
 * streams for a plaintext connection, or an MSE-negotiated connection's - possibly RC4-wrapped
 * - resulting ones - see design_docs/0052), and the remote's already-read handshake by
 * PeerServer, once it's found something willing to own a connection for that handshake's info
 * hash - see design_docs/0038. Implementations take full ownership of socket from here
 * (completing the handshake, or closing it themselves if they don't want the connection after
 * all - e.g. already at a connection cap).
 *
 * <p>Deliberately doesn't reference TorrentSession/TorrentEngine - keeps PeerServer (in
 * this, a lower layer) from depending on those higher ones. See design_docs/0006.
 */
@FunctionalInterface
public interface IncomingConnectionHandler {

    void accept(Socket socket, InputStream in, OutputStream out, Handshake handshake) throws IOException;
}
