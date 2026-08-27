package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.mse.MseHandshake;
import com.grimtorrenter.engine.mse.MseInboundResult;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Accepts inbound peer-wire TCP connections on one shared port - a single instance per
 * TorrentEngine, not one per torrent, since this is the one port advertised to every
 * tracker/DHT regardless of which torrent a remote peer is trying to reach (mirrors
 * DhtNode owning one shared UDP socket rather than one per session). Runs its own accept
 * loop on one dedicated virtual thread, written as ordinary blocking-style I/O per
 * design_docs/0007, same as DhtNode's receive loop and PeerConnection's own read loop.
 *
 * <p>Reads just enough of each accepted connection to learn its info hash - the one piece of
 * information needed to know which torrent, if any, wants it - then hands the still-open
 * socket off to whatever handlerLookup resolves to for that hash. A connection for an info
 * hash nothing recognizes (unknown/removed torrent) is just closed. Each accepted connection's
 * handshake read + handoff runs on its own virtual thread so one slow or hanging remote peer
 * can't stall the accept loop from accepting the next one. See design_docs/0038.
 *
 * <p>Since design_docs/0052, "read just enough to learn the info hash" branches two ways:
 * a plaintext handshake (starts with pstrlen 19, "BitTorrent protocol") is read directly, same
 * as before; anything else is assumed to be the start of an MSE negotiation and is handed to
 * MseHandshake, which recovers the info hash by matching the peer's obfuscated SKEY hash
 * against activeInfoHashes rather than reading it in the clear. encryptionMode is read live on
 * every connection - see EncryptionMode's own Javadoc for why.
 */
public final class PeerServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PeerServer.class.getName());
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    /** The plaintext handshake's first byte - pstrlen for "BitTorrent protocol" (19 ASCII
     * characters). Anything else at this position is assumed to be the start of an MSE
     * negotiation's Diffie-Hellman public key instead. */
    private static final int PLAINTEXT_HANDSHAKE_FIRST_BYTE = 19;
    private static final SecureRandom MSE_RANDOM = new SecureRandom();

    private final ServerSocket serverSocket;
    private final Function<InfoHash, Optional<IncomingConnectionHandler>> handlerLookup;
    private final Supplier<EncryptionMode> encryptionMode;
    private final Supplier<Collection<InfoHash>> activeInfoHashes;
    private volatile boolean closed;

    /** Same as the four-arg constructor below but with encryption disabled - for every
     * caller that predates MSE and doesn't need it (tests, mainly). See design_docs/0052. */
    public PeerServer(int port, Function<InfoHash, Optional<IncomingConnectionHandler>> handlerLookup)
            throws IOException {
        this(port, handlerLookup, () -> EncryptionMode.DISABLED, Set::of);
    }

    public PeerServer(int port, Function<InfoHash, Optional<IncomingConnectionHandler>> handlerLookup,
                       Supplier<EncryptionMode> encryptionMode, Supplier<Collection<InfoHash>> activeInfoHashes)
            throws IOException {
        // Unbound construction + setReuseAddress(true) before bind() - see DhtNode's own
        // matching comment for why (the convenience constructor new ServerSocket(port) binds
        // immediately with no chance to set this first, and without it a quick rebind right
        // after close() - e.g. Quarkus dev mode's live-reload on every backend source
        // change - can fail with "Address already in use" purely from OS-level lingering).
        // See design_docs/0058.
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port));
        this.handlerLookup = handlerLookup;
        this.encryptionMode = encryptionMode;
        this.activeInfoHashes = activeInfoHashes;
        Thread.ofVirtual().name("peer-server-accept").start(this::acceptLoop);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(System.Logger.Level.WARNING, "Peer server accept loop failed", e);
                }
                return;
            }
            Thread.ofVirtual().name("peer-server-handshake").start(() -> handleConnection(socket));
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            EncryptionMode mode = encryptionMode.get();

            BufferedInputStream peekable = new BufferedInputStream(socket.getInputStream());
            peekable.mark(1);
            int firstByte = peekable.read();
            if (firstByte < 0) {
                closeQuietly(socket);
                return;
            }
            peekable.reset();

            InputStream in;
            OutputStream out = socket.getOutputStream();
            Handshake handshake;
            InfoHash infoHash;

            if (firstByte == PLAINTEXT_HANDSHAKE_FIRST_BYTE) {
                if (mode == EncryptionMode.REQUIRED) {
                    closeQuietly(socket);
                    return;
                }
                handshake = PeerWireCodec.readHandshake(peekable);
                in = peekable;
                infoHash = handshake.infoHash();
            } else {
                if (mode == EncryptionMode.DISABLED) {
                    closeQuietly(socket);
                    return;
                }
                MseInboundResult negotiated = MseHandshake.negotiateInbound(peekable, out, activeInfoHashes.get(),
                        mode == EncryptionMode.REQUIRED, MSE_RANDOM);
                in = negotiated.in();
                out = negotiated.out();
                handshake = PeerWireCodec.readHandshake(in);
                if (!handshake.infoHash().equals(negotiated.infoHash())) {
                    closeQuietly(socket);
                    return;
                }
                infoHash = negotiated.infoHash();
            }

            Optional<IncomingConnectionHandler> handler = handlerLookup.apply(infoHash);
            if (handler.isEmpty()) {
                closeQuietly(socket);
                return;
            }
            handler.get().accept(socket, in, out, handshake);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort - we're already handling a failure
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
