package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.proxy.ProxyProvider;
import com.grimtorrenter.engine.proxy.Socks5;
import com.grimtorrenter.engine.mse.MseHandshake;
import com.grimtorrenter.engine.mse.MseOutboundResult;
import com.grimtorrenter.engine.peerwire.Bitfield;
import com.grimtorrenter.engine.peerwire.Cancel;
import com.grimtorrenter.engine.peerwire.Choke;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.Have;
import com.grimtorrenter.engine.peerwire.Interested;
import com.grimtorrenter.engine.peerwire.KeepAlive;
import com.grimtorrenter.engine.peerwire.NotInterested;
import com.grimtorrenter.engine.peerwire.PeerMessage;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.peerwire.Piece;
import com.grimtorrenter.engine.peerwire.Port;
import com.grimtorrenter.engine.peerwire.Request;
import com.grimtorrenter.engine.peerwire.Unchoke;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import com.grimtorrenter.engine.utp.UtpSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One TCP connection to a remote peer. Reads run on a dedicated virtual
 * thread (design_docs/0007) using blocking-style I/O against
 * PeerWireCodec; writes are serialized under writeLock so concurrent
 * senders can't interleave bytes on the wire.
 *
 * <p>State is bidirectional from Phase 1 (design_docs/0008) even though
 * nothing yet drives am_choking to false or calls sendPiece for real -
 * that's Phase 2's choking algorithm, not this class's job.
 */
public final class PeerConnection implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    /** Matches BEP's recommended keep-alive cadence - a connection silent longer than this is treated as dead. */
    private static final int IDLE_READ_TIMEOUT_MS = 120_000;
    /** For MSE's DH key generation and padding (design_docs/0052) - one shared instance
     * rather than a fresh SecureRandom per connection attempt, matching the seeded-once,
     * reused-many-times pattern SecureRandom is meant for. */
    private static final SecureRandom MSE_RANDOM = new SecureRandom();

    private final PeerTransport transport;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();

    private final PeerAddress remoteAddress;
    private final PeerId remotePeerId;
    private final PeerConnectionListener listener;
    private final RateLimiters rateLimiters;
    /** True for a connection PeerServer accepted (the remote initiated it), false for one this
     * side initiated via connect() - always known unconditionally at each factory family's own
     * call sites, never caller-supplied. See design_docs/0066. */
    private final boolean incoming;
    /** How this side learned of remoteAddress before ever connecting to it - UNKNOWN for an
     * incoming connection (see this field's own class-level Javadoc on PeerSource) and for
     * every connect() overload that doesn't take one explicitly. See design_docs/0066. */
    private final PeerSource source;

    private final BitSet peerPieces = new BitSet();
    private final Set<Request> pendingRequests = ConcurrentHashMap.newKeySet();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong uploadedBytes = new AtomicLong();
    /** Wall-clock millis of the most recent block moved in either direction; 0 = never. Feeds
     * activity() only. See design_docs/0076. */
    private volatile long lastTransferMillis;

    private static final long RATE_WINDOW_MILLIS = 1_000;
    private volatile double downloadRateBytesPerSec;
    private long rateWindowStartMillis;
    private long rateWindowBytes;

    /** How recently a block must have moved for activity() to call this connection ACTIVE -
     * comfortably longer than the 3s detail-view poll, since blocks arrive in bursts (choke
     * rounds, rate limiting) and a shorter window would flicker. */
    public static final long ACTIVITY_WINDOW_MILLIS = 10_000;

    /** The peer's extended handshake, decoded - empty until/unless it arrives. Bundled into
     * one record (rather than two separate volatile fields) so a reader never sees the
     * extensions map from one handshake paired with the metadata size from another, or one
     * populated before the other. See design_docs/0028. */
    private volatile ExtendedHandshakeInfo peerHandshakeInfo = ExtendedHandshakeInfo.EMPTY;

    private record ExtendedHandshakeInfo(Map<String, Integer> extensions, OptionalInt metadataSize) {
        static final ExtendedHandshakeInfo EMPTY = new ExtendedHandshakeInfo(Map.of(), OptionalInt.empty());
    }

    private volatile boolean amChoking = true;
    private volatile boolean amInterested = false;
    private volatile boolean peerChoking = true;
    private volatile boolean peerInterested = false;
    private volatile boolean closed = false;

    /** in/out are passed in explicitly rather than derived from transport here, so that a
     * caller which already completed MSE negotiation (design_docs/0052) can hand over the
     * resulting - possibly RC4-wrapped - stream pair instead of this constructor silently
     * grabbing the transport's raw ones. transport itself is PeerTransport, not java.net.Socket
     * directly, since design_docs/0074's slice 2 - see that interface's own Javadoc. */
    private PeerConnection(PeerTransport transport, InputStream in, OutputStream out, PeerAddress remoteAddress,
                            PeerId remotePeerId, PeerConnectionListener listener, RateLimiters rateLimiters,
                            boolean incoming, PeerSource source) {
        this.transport = transport;
        this.in = in;
        this.out = out;
        this.remoteAddress = remoteAddress;
        this.remotePeerId = remotePeerId;
        this.listener = listener;
        this.rateLimiters = rateLimiters;
        this.incoming = incoming;
        this.source = source;
    }

    public boolean incoming() {
        return incoming;
    }

    public PeerSource source() {
        return source;
    }

    /** Which transport this connection actually uses - orthogonal to source()/incoming() above,
     * derived from the concrete PeerTransport already held rather than a separately-stored
     * field, since there's nothing to keep in sync. See design_docs/0066's own addendum. */
    public PeerTransportType transportType() {
        return transport instanceof UtpPeerTransport ? PeerTransportType.UTP : PeerTransportType.TCP;
    }

    /** Same as the five-arg overload below but with no rate limiting - for every caller
     * that predates rate limiting and doesn't need it (tests, the one-shot magnet metadata
     * fetcher, which never transfers real piece data). See design_docs/0042. */
    public static PeerConnection connect(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                          PeerConnectionListener listener,
                                          Map<String, Integer> extensionsToAdvertise) throws IOException {
        return connect(address, infoHash, ourPeerId, listener, extensionsToAdvertise, RateLimiters.unlimited());
    }

    /** Same as the seven-arg overload below but with encryption disabled - for every caller
     * that predates MSE and doesn't need it (tests, mainly). See design_docs/0052. */
    public static PeerConnection connect(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                          PeerConnectionListener listener,
                                          Map<String, Integer> extensionsToAdvertise,
                                          RateLimiters rateLimiters) throws IOException {
        return connect(address, infoHash, ourPeerId, listener, extensionsToAdvertise, rateLimiters,
                EncryptionMode.DISABLED);
    }

    /** extensionsToAdvertise is our own BEP 10 "m" dictionary - extension name to the
     * local id we want the peer to use when sending us that extension. Empty for a normal
     * torrent download (nothing in TorrentSession needs one yet); the magnet-link metadata
     * fetcher passes {"ut_metadata": <id>}. See design_docs/0028.
     *
     * <p>encryptionMode's PREFERRED case attempts MSE negotiation first (see
     * connectEncrypted()); on any failure there (peer doesn't respond as an MSE peer, sync
     * point never found, negotiation error) it closes that attempt and opens a fresh second
     * connection attempting a plain handshake instead - MSE negotiation state can't be
     * "rewound" on the same socket once bytes have gone out, so a retry needs a clean
     * connection. REQUIRED does not fall back - a peer that can't do MSE simply fails to
     * connect. See design_docs/0052. */
    public static PeerConnection connect(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                          PeerConnectionListener listener,
                                          Map<String, Integer> extensionsToAdvertise,
                                          RateLimiters rateLimiters,
                                          EncryptionMode encryptionMode) throws IOException {
        return connect(address, infoHash, ourPeerId, listener, extensionsToAdvertise, rateLimiters, encryptionMode,
                PeerSource.UNKNOWN);
    }

    /** source is how this side learned of address before calling this - UNKNOWN for every
     * caller that doesn't have (or care about) one; TorrentSession.attemptConnect() is the one
     * real caller with an actual source to supply. See design_docs/0066. */
    public static PeerConnection connect(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                          PeerConnectionListener listener,
                                          Map<String, Integer> extensionsToAdvertise,
                                          RateLimiters rateLimiters,
                                          EncryptionMode encryptionMode,
                                          PeerSource source) throws IOException {
        return connect(address, infoHash, ourPeerId, listener, extensionsToAdvertise, rateLimiters, encryptionMode,
                source, ProxyProvider.NONE);
    }

    /** proxy is read once per connection attempt: when it names a proxy, every socket this opens
     * (including the plaintext retry after a failed MSE attempt) is a SOCKS5 tunnel to the peer,
     * and a proxy that can't be reached fails the connection - it never falls back to a direct
     * one. See design_docs/0079. */
    public static PeerConnection connect(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                          PeerConnectionListener listener,
                                          Map<String, Integer> extensionsToAdvertise,
                                          RateLimiters rateLimiters,
                                          EncryptionMode encryptionMode,
                                          PeerSource source,
                                          ProxyProvider proxy) throws IOException {
        if (encryptionMode == EncryptionMode.DISABLED) {
            return connectPlaintext(address, infoHash, ourPeerId, listener, extensionsToAdvertise, rateLimiters,
                    source, proxy);
        }
        try {
            return connectEncrypted(address, infoHash, ourPeerId, listener, extensionsToAdvertise, rateLimiters,
                    encryptionMode == EncryptionMode.REQUIRED, source, proxy);
        } catch (TransportUnreachableException e) {
            // The TCP connect itself failed (timeout, refused, proxy down) - a plaintext retry
            // would just hit the same wall and double the time a dead peer holds a connection slot.
            throw e.original();
        } catch (IOException e) {
            if (encryptionMode == EncryptionMode.REQUIRED) {
                throw e;
            }
            return connectPlaintext(address, infoHash, ourPeerId, listener, extensionsToAdvertise, rateLimiters,
                    source, proxy);
        }
    }

    private static PeerConnection connectPlaintext(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                                     PeerConnectionListener listener,
                                                     Map<String, Integer> extensionsToAdvertise,
                                                     RateLimiters rateLimiters, PeerSource source,
                                                     ProxyProvider proxy) throws IOException {
        Socket socket = Socks5.connect(proxy, new InetSocketAddress(address.address(), address.port()),
                CONNECT_TIMEOUT_MS);
        InputStream in;
        OutputStream out;
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
        return completeOutboundHandshake(new SocketPeerTransport(socket), in, out, address, infoHash, ourPeerId,
                listener, extensionsToAdvertise, rateLimiters, source);
    }

    /** connectEncrypted() couldn't even establish the transport - distinguishes that from an MSE
     * negotiation failure, the only case connect()'s plaintext fallback can actually help. */
    private static final class TransportUnreachableException extends IOException {
        private final IOException original;

        TransportUnreachableException(IOException original) {
            super(original);
            this.original = original;
        }

        IOException original() {
            return original;
        }
    }

    /** MSE negotiation happens before the ordinary BT handshake, over the same socket -
     * everything past negotiation (handshake, read loop, extended handshake) is identical to
     * connectPlaintext(), just against MseHandshake's resulting stream pair instead of the
     * socket's raw ones. See design_docs/0052. */
    private static PeerConnection connectEncrypted(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                                     PeerConnectionListener listener,
                                                     Map<String, Integer> extensionsToAdvertise,
                                                     RateLimiters rateLimiters,
                                                     boolean requireEncryption, PeerSource source,
                                                     ProxyProvider proxy) throws IOException {
        Socket socket;
        try {
            socket = Socks5.connect(proxy, new InetSocketAddress(address.address(), address.port()),
                    CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            throw new TransportUnreachableException(e);
        }
        MseOutboundResult negotiated;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            negotiated = MseHandshake.negotiateOutbound(
                    socket.getInputStream(), socket.getOutputStream(), infoHash, requireEncryption, MSE_RANDOM);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
        return completeOutboundHandshake(new SocketPeerTransport(socket), negotiated.in(), negotiated.out(), address,
                infoHash, ourPeerId, listener, extensionsToAdvertise, rateLimiters, source);
    }

    /** Public since design_docs/0074's slice 4 - TorrentSession.connectViaUtp() (a different
     * package) is now this method's real production caller, the same way acceptViaUtp() became
     * public for slice 3's inbound path. */
    public static PeerConnection connectViaUtp(UtpSocket utpSocket, PeerAddress address, InfoHash infoHash,
                                         PeerId ourPeerId, PeerConnectionListener listener,
                                         Map<String, Integer> extensionsToAdvertise,
                                         RateLimiters rateLimiters, PeerSource source) throws IOException {
        return completeOutboundHandshake(new UtpPeerTransport(utpSocket), utpSocket.getInputStream(),
                utpSocket.getOutputStream(), address, infoHash, ourPeerId, listener, extensionsToAdvertise,
                rateLimiters, source);
    }

    /** The shared outbound tail every connect() path (plaintext, MSE-negotiated, or - slice 2 -
     * uTP) funnels through once it has an established transport and stream pair: write our
     * handshake, read theirs, verify the info hash, construct and start the connection. See
     * design_docs/0074. */
    private static PeerConnection completeOutboundHandshake(PeerTransport transport, InputStream in,
                                                              OutputStream out, PeerAddress address,
                                                              InfoHash infoHash, PeerId ourPeerId,
                                                              PeerConnectionListener listener,
                                                              Map<String, Integer> extensionsToAdvertise,
                                                              RateLimiters rateLimiters,
                                                              PeerSource source) throws IOException {
        try {
            transport.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            PeerWireCodec.writeHandshake(out, Handshake.withExtensionProtocol(infoHash, ourPeerId));
            Handshake remoteHandshake = PeerWireCodec.readHandshake(in);
            if (!remoteHandshake.infoHash().equals(infoHash)) {
                throw new PeerConnectionException(
                        "Peer " + address + " handshake info hash mismatch: expected " + infoHash
                                + ", got " + remoteHandshake.infoHash());
            }

            transport.setSoTimeout(IDLE_READ_TIMEOUT_MS);
            PeerConnection connection = new PeerConnection(transport, in, out, address, remoteHandshake.peerId(),
                    listener, rateLimiters, false, source);
            connection.startReadLoop();
            connection.sendExtendedHandshakeIfSupported(remoteHandshake, extensionsToAdvertise);
            return connection;
        } catch (IOException | RuntimeException e) {
            closeQuietly(transport);
            throw e;
        }
    }

    /** Same as the five-arg overload below but with no rate limiting - see the connect()
     * overload above for why this exists. See design_docs/0042. */
    public static PeerConnection accept(Socket socket, Handshake remoteHandshake, PeerId ourPeerId,
                                         PeerConnectionListener listener,
                                         Map<String, Integer> extensionsToAdvertise) throws IOException {
        return accept(socket, remoteHandshake, ourPeerId, listener, extensionsToAdvertise, RateLimiters.unlimited());
    }

    /**
     * Completes a handshake for a connection the remote peer initiated - the mirror image
     * of connect(): the caller (PeerServer) has already accepted the socket and read the
     * remote's handshake (it had to, to learn which torrent this connection is even for -
     * see design_docs/0038), so this only needs to write ours back rather than write-then-
     * read like connect() does. Everything past that point (read loop, extended handshake)
     * is identical. Derives in/out from the socket itself - see the eight-arg overload below
     * for callers (PeerServer, once MSE support was added - design_docs/0052) that have
     * already established a different stream pair to use instead (e.g. RC4-wrapped ones).
     */
    public static PeerConnection accept(Socket socket, Handshake remoteHandshake, PeerId ourPeerId,
                                         PeerConnectionListener listener,
                                         Map<String, Integer> extensionsToAdvertise,
                                         RateLimiters rateLimiters) throws IOException {
        return accept(socket, socket.getInputStream(), socket.getOutputStream(), remoteHandshake, ourPeerId,
                listener, extensionsToAdvertise, rateLimiters);
    }

    /** Same as above but with an explicit in/out stream pair, for a connection PeerServer has
     * already established the right streams for (a plaintext connection's raw socket streams,
     * or an MSE-negotiated connection's - possibly RC4-wrapped - resulting ones). See
     * design_docs/0052. */
    public static PeerConnection accept(Socket socket, InputStream in, OutputStream out, Handshake remoteHandshake,
                                         PeerId ourPeerId, PeerConnectionListener listener,
                                         Map<String, Integer> extensionsToAdvertise,
                                         RateLimiters rateLimiters) throws IOException {
        return completeInboundHandshake(new SocketPeerTransport(socket), in, out, remoteHandshake, ourPeerId,
                listener, extensionsToAdvertise, rateLimiters);
    }

    /** Public since design_docs/0074's slice 3 - TorrentSession.acceptIncomingUtpConnection()
     * (a different package) is now this method's real production caller, the same way it already
     * calls the Socket-based accept() overloads above. remoteHandshake is already-read by the
     * caller, exactly like those - UtpPeerAcceptor's own role for a real inbound connection, or
     * PeerConnectionUtpTransportTest's own stand-in for it. */
    public static PeerConnection acceptViaUtp(UtpSocket utpSocket, Handshake remoteHandshake, PeerId ourPeerId,
                                        PeerConnectionListener listener, Map<String, Integer> extensionsToAdvertise,
                                        RateLimiters rateLimiters) throws IOException {
        return completeInboundHandshake(new UtpPeerTransport(utpSocket), utpSocket.getInputStream(),
                utpSocket.getOutputStream(), remoteHandshake, ourPeerId, listener, extensionsToAdvertise,
                rateLimiters);
    }

    /** The shared inbound tail every accept() path funnels through - mirror image of
     * completeOutboundHandshake(): no read (the caller already read the remote's handshake), so
     * only writes ours back. See design_docs/0074. */
    private static PeerConnection completeInboundHandshake(PeerTransport transport, InputStream in, OutputStream out,
                                                             Handshake remoteHandshake, PeerId ourPeerId,
                                                             PeerConnectionListener listener,
                                                             Map<String, Integer> extensionsToAdvertise,
                                                             RateLimiters rateLimiters) throws IOException {
        try {
            transport.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            PeerWireCodec.writeHandshake(out, Handshake.withExtensionProtocol(remoteHandshake.infoHash(), ourPeerId));

            transport.setSoTimeout(IDLE_READ_TIMEOUT_MS);
            PeerAddress remoteAddress = new PeerAddress(transport.getInetAddress(), transport.getPort());
            PeerConnection connection = new PeerConnection(transport, in, out, remoteAddress,
                    remoteHandshake.peerId(), listener, rateLimiters, true, PeerSource.UNKNOWN);
            connection.startReadLoop();
            connection.sendExtendedHandshakeIfSupported(remoteHandshake, extensionsToAdvertise);
            return connection;
        } catch (IOException | RuntimeException e) {
            closeQuietly(transport);
            throw e;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort - we're already handling a failure
        }
    }

    private static void closeQuietly(PeerTransport transport) {
        try {
            transport.close();
        } catch (IOException ignored) {
            // best effort - we're already handling a failure
        }
    }

    private void startReadLoop() {
        Thread.ofVirtual().name("peer-" + remoteAddress).start(this::readLoop);
    }

    private void readLoop() {
        try {
            while (!closed) {
                PeerMessage message = PeerWireCodec.readMessage(in);
                applyIncoming(message);
                listener.onMessage(this, message);
            }
        } catch (IOException | RuntimeException e) {
            disconnect(e);
        }
    }

    private void applyIncoming(PeerMessage message) {
        switch (message) {
            case Choke ignored -> {
                peerChoking = true;
                // BEP 3: a choke discards every request the peer hadn't answered yet. Left in
                // pendingRequests they'd count against the pipeline forever after the next
                // unchoke and the peer would never be asked for anything again.
                pendingRequests.clear();
            }
            case Unchoke ignored -> peerChoking = false;
            case Interested ignored -> peerInterested = true;
            case NotInterested ignored -> peerInterested = false;
            case Have h -> {
                synchronized (peerPieces) {
                    peerPieces.set(h.pieceIndex());
                }
            }
            case Bitfield b -> {
                synchronized (peerPieces) {
                    peerPieces.clear();
                    for (int i = 0; i < b.bits().length * 8; i++) {
                        if (b.hasPiece(i)) {
                            peerPieces.set(i);
                        }
                    }
                }
            }
            case Piece p -> {
                downloadedBytes.addAndGet(p.block().length);
                lastTransferMillis = System.currentTimeMillis();
                pendingRequests.removeIf(r -> r.index() == p.index() && r.begin() == p.begin());
                recordDownloadForRate(p.block().length);
                // Blocks this connection's own read loop, not the caller of applyIncoming -
                // throttles how fast we go back to reading further wire data from this
                // peer, which is what actually slows the incoming byte rate down (TCP flow
                // control backs the remote sender off once we stop reading). See
                // design_docs/0042.
                rateLimiters.download().acquire(p.block().length);
            }
            case KeepAlive ignored -> {
            }
            case Request ignored -> {
            }
            case Cancel ignored -> {
            }
            case Port ignored -> {
            }
            case Extended e -> {
                if (e.extendedMessageId() == 0) {
                    applyExtendedHandshake(e.payload());
                }
                // Any other extended message id is a specific negotiated extension (e.g.
                // BEP 9's ut_metadata) - this class only owns the generic envelope and
                // handshake bookkeeping, not any individual extension's semantics.
            }
        }
    }

    /** A malformed extended handshake is logged nowhere and just leaves peerHandshakeInfo
     * as it was (as if the peer hadn't sent one at all) - not worth failing the whole
     * connection over one badly-formed bencoded dict. */
    private void applyExtendedHandshake(byte[] payload) {
        try {
            if (!(BencodeDecoder.decode(payload) instanceof BDictionary dict)) {
                return;
            }
            Map<String, Integer> extensions = Map.of();
            if (dict.get("m") instanceof BDictionary supportedExtensions) {
                Map<String, Integer> parsed = new HashMap<>();
                for (Map.Entry<BString, BValue> entry : supportedExtensions.entries().entrySet()) {
                    if (entry.getValue() instanceof BInteger id) {
                        parsed.put(entry.getKey().utf8(), (int) id.value());
                    }
                }
                extensions = Map.copyOf(parsed);
            }
            OptionalInt metadataSize = dict.get("metadata_size") instanceof BInteger size
                    ? OptionalInt.of((int) size.value())
                    : OptionalInt.empty();
            peerHandshakeInfo = new ExtendedHandshakeInfo(extensions, metadataSize);
        } catch (RuntimeException ignored) {
            // Malformed payload - leave peerHandshakeInfo as it was.
        }
    }

    /** Only sent if the peer's own handshake advertised BEP 10 support - sending an
     * extension-protocol message to a peer that never said it understood one would just
     * be a protocol violation from their point of view. */
    private void sendExtendedHandshakeIfSupported(Handshake remoteHandshake, Map<String, Integer> extensionsToAdvertise) {
        if (!remoteHandshake.supportsExtensionProtocol()) {
            return;
        }
        Map<BString, BValue> m = new HashMap<>();
        for (Map.Entry<String, Integer> entry : extensionsToAdvertise.entrySet()) {
            m.put(BString.of(entry.getKey()), new BInteger(entry.getValue()));
        }
        BDictionary handshakeDict = new BDictionary(Map.of(BString.of("m"), new BDictionary(m)));
        send(new Extended(0, BencodeEncoder.encode(handshakeDict)));
    }

    private synchronized void disconnect(Throwable cause) {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(transport);
        listener.onDisconnected(this, cause);
    }

    @Override
    public void close() {
        disconnect(null);
    }

    private void send(PeerMessage message) {
        synchronized (writeLock) {
            try {
                PeerWireCodec.writeMessage(out, message);
            } catch (IOException e) {
                disconnect(e);
            }
        }
    }

    public void sendChoke() {
        amChoking = true;
        send(new Choke());
    }

    public void sendUnchoke() {
        amChoking = false;
        send(new Unchoke());
    }

    public void sendInterested() {
        amInterested = true;
        send(new Interested());
    }

    public void sendNotInterested() {
        amInterested = false;
        send(new NotInterested());
    }

    public void sendHave(int pieceIndex) {
        send(new Have(pieceIndex));
    }

    public void sendBitfield(Bitfield bitfield) {
        send(bitfield);
    }

    public void sendKeepAlive() {
        send(new KeepAlive());
    }

    public void sendPort(int listenPort) {
        send(new Port(listenPort));
    }

    public void sendRequest(int index, int begin, int length) {
        Request request = new Request(index, begin, length);
        pendingRequests.add(request);
        send(request);
    }

    public void sendCancel(int index, int begin, int length) {
        pendingRequests.remove(new Request(index, begin, length));
        send(new Cancel(index, begin, length));
    }

    /** Blocks (on the shared upload RateLimiter, if one is actually limited - see
     * design_docs/0042) before writing, not after - we simply don't attempt the write
     * until the budget allows it, the precise enforcement point for something we fully
     * control the timing of. */
    public void sendPiece(int index, int begin, byte[] block) {
        rateLimiters.upload().acquire(block.length);
        send(new Piece(index, begin, block));
        uploadedBytes.addAndGet(block.length);
        lastTransferMillis = System.currentTimeMillis();
    }

    public PeerAddress remoteAddress() {
        return remoteAddress;
    }

    public PeerId remotePeerId() {
        return remotePeerId;
    }

    public boolean amChoking() {
        return amChoking;
    }

    public boolean amInterested() {
        return amInterested;
    }

    public boolean peerChoking() {
        return peerChoking;
    }

    public boolean peerInterested() {
        return peerInterested;
    }

    public boolean peerHasPiece(int index) {
        synchronized (peerPieces) {
            return peerPieces.get(index);
        }
    }

    /** The extended-message id to use when sending the peer a given named extension
     * (BEP 10's "m" dictionary, from their extended handshake) - empty if they never
     * sent one, or don't support that extension. See design_docs/0028. */
    public OptionalInt remoteExtensionId(String extensionName) {
        Integer id = peerHandshakeInfo.extensions().get(extensionName);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    /** BEP 9's optional top-level "metadata_size" field on the extended handshake - only
     * meaningful once the peer's own extended handshake has arrived. See design_docs/0028. */
    public OptionalInt remoteMetadataSize() {
        return peerHandshakeInfo.metadataSize();
    }

    /** For a specific negotiated extension (see remoteExtensionId) - PeerConnection itself
     * doesn't know or care what's inside payload, that's the extension's own codec's job.
     * See design_docs/0028. */
    public void sendExtended(int extendedMessageId, byte[] payload) {
        send(new Extended(extendedMessageId, payload));
    }

    /** Smoothed download rate from this peer, bytes/sec - 0 until a full sampling window has
     * elapsed. Sized against by TorrentSession's adaptive request pipeline. Written only from
     * this connection's own read loop (single writer), read from anywhere. */
    public double downloadRateBytesPerSec() {
        return downloadRateBytesPerSec;
    }

    private void recordDownloadForRate(int bytes) {
        long now = System.currentTimeMillis();
        if (rateWindowStartMillis == 0) {
            rateWindowStartMillis = now;
        }
        rateWindowBytes += bytes;
        long elapsed = now - rateWindowStartMillis;
        if (elapsed >= RATE_WINDOW_MILLIS) {
            double sample = rateWindowBytes * 1000.0 / elapsed;
            downloadRateBytesPerSec = downloadRateBytesPerSec == 0
                    ? sample : (downloadRateBytesPerSec + sample) / 2;
            rateWindowStartMillis = now;
            rateWindowBytes = 0;
        }
    }

    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    /** Snapshot for the caller to requeue elsewhere after a disconnect. */
    public Set<Request> pendingRequestsSnapshot() {
        return Set.copyOf(pendingRequests);
    }

    public long downloadedBytes() {
        return downloadedBytes.get();
    }

    public long uploadedBytes() {
        return uploadedBytes.get();
    }

    public PeerActivity activity() {
        return activity(System.currentTimeMillis());
    }

    /** nowMillis is a parameter (rather than read inside) purely so a test can assert the
     * window boundary deterministically. See design_docs/0076. */
    public PeerActivity activity(long nowMillis) {
        long last = lastTransferMillis;
        if (last != 0 && nowMillis - last <= ACTIVITY_WINDOW_MILLIS) {
            return PeerActivity.ACTIVE;
        }
        return amInterested() ? PeerActivity.WAITING : PeerActivity.IDLE;
    }

    public boolean isClosed() {
        return closed;
    }
}
