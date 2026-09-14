package com.grimtorrenter.engine.utp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One BEP 29 (µTP) connection's reliable-delivery state machine - design_docs/0074's slice 1.
 * Deliberately standalone: this class knows nothing about PeerConnection/TorrentSession/DhtNode
 * (that wiring is slices 2-4). Two virtual threads per connection (receive loop - when this
 * instance owns its socket, see the Scope note below - and retransmit loop), matching
 * DhtNode.receiveLoop/PeerConnection.readLoop's existing "ordinary blocking I/O on a virtual
 * thread" convention - see design_docs/0007-concurrency-model.
 *
 * <p><b>Scope, stated explicitly</b> (design_docs/0074): connect()/accept() each produce an
 * instance that owns and exclusively reads its own DatagramSocket for its whole lifetime -
 * still true, and still what every slice 1/2 test exercises. Slice 3 added a second mode,
 * acceptShared(), for a connection that shares DhtNode's single UDP socket with other traffic
 * (KRPC, and other µTP connections) instead: that instance runs no receive loop of its own -
 * the external demuxer (utp.UtpAcceptor) that owns the shared socket's one reader feeds it
 * packets via deliverIncoming(), and its close() leaves the shared socket itself open.
 *
 * <p>Congestion control (design_docs/0074's slice 5) is real LEDBAT (RFC 6817) - see
 * {@link LedbatCongestionControl} for the delay-based control law itself; this class only feeds
 * it delay/ack/loss signals and asks it for the current send-window cap. Still no selective-ack
 * support (deliberately deferred, same doc): a packet that arrives out of order is never
 * buffered for later delivery, only ever dropped and recovered via the sender's own
 * retransmission timeout (this connection's one loss signal, also what triggers LEDBAT's own
 * backoff) - a real, accepted inefficiency under loss, not a bug.
 *
 * <p>getInputStream()/getOutputStream()/remoteAddress()/setReceiveTimeoutMillis() (slice 2) exist
 * purely so peer.PeerConnection (a different module-internal package) can sit on top of a
 * UtpSocket via peer.UtpPeerTransport, the same way it already sits on a plain java.net.Socket -
 * this class itself still has no dependency on that package or anything BitTorrent-specific.
 */
public final class UtpSocket implements AutoCloseable {

    private static final int VERSION_UNUSED_ACK = 0;
    private static final int MAX_PAYLOAD_LENGTH = 1400;
    /** This side's own advertised receive window (BEP 29's wnd_size field) - fixed and generous,
     * since this class has no bounded receive buffer to advertise a real backpressure signal
     * for. Decoupled from the send-side congestion window (design_docs/0074's slice 5,
     * LedbatCongestionControl) - those are two different BEP 29 concepts this class used to
     * conflate by reusing one constant for both. */
    private static final int ADVERTISED_RECEIVE_WINDOW_BYTES = 64 * 1024;
    private static final int RECEIVE_BUFFER_SIZE = 4096;

    private static final long INITIAL_RTO_MILLIS = 1000;
    private static final long MIN_RTO_MILLIS = 500;
    private static final long MAX_RTO_MILLIS = 60_000;
    private static final int MAX_RETRIES = 7;
    private static final long IDLE_CHECK_INTERVAL_MILLIS = 500;
    private static final long HANDSHAKE_RETRY_INTERVAL_MILLIS = 1000;
    private static final int HANDSHAKE_MAX_RETRIES = 5;

    /** Sentinel pushed onto the incoming-data queue to unblock a pending receive() on close/FIN
     * - a zero-length array is otherwise never produced by chunking real data (see send()), so
     * it's a safe, distinguishable marker without needing a wrapper type. */
    private static final byte[] END_OF_STREAM = new byte[0];

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatagramSocket socket;
    private final InetSocketAddress remoteAddress;
    private final int sendConnectionId;
    private final int receiveConnectionId;

    private final AtomicInteger localSeqNr = new AtomicInteger();
    /** The last in-order sequence number accepted from the remote side - what this side reports
     * as ackNr on every outbound packet. Bootstrapped from the remote's own SYN seqNr (see
     * connect()/accept()), not incremented for STATE-only packets (see this class's own
     * "STATE never advances remote sequence" reasoning in handleIncoming()). */
    private volatile int remoteAckNr;

    private final Object outstandingLock = new Object();
    private final Map<Integer, Outstanding> outstanding = new LinkedHashMap<>();
    private final Object windowLock = new Object();
    private int bytesInFlight;
    private final LedbatCongestionControl congestionControl = new LedbatCongestionControl(MAX_PAYLOAD_LENGTH);
    /** The peer's own last-advertised receive window (BEP 29's wnd_size, decoded on every
     * inbound packet) - design_docs/0074's slice 5, a small correctness addition beyond the
     * interim window's own behavior: a well-behaved sender's effective window is
     * min(cwnd, peer's advertised window), not the congestion window alone. Long.MAX_VALUE
     * (effectively unconstrained by this alone) until the first real packet arrives - the
     * handshake's own SYN/STATE exchange happens outside handleIncoming() and never updates
     * this, same as it never fed the RTT estimator either. */
    private volatile long remoteWindowBytes = Long.MAX_VALUE;

    private volatile double srttMillis = -1;
    private volatile double rttVarMillis;
    private volatile long lastObservedDelayMicros;

    private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    /** 0 (the default) means receive() blocks forever, identical to every slice 1 behavior/test
     * - only PeerConnection (via UtpPeerTransport.setSoTimeout(), design_docs/0074's slice 2)
     * ever sets this to something else. */
    private volatile long receiveTimeoutMillis;
    private volatile boolean closed;
    /** Whether this instance exclusively owns socket for its whole lifetime (connect()/accept()
     * above), vs. sharing it with other traffic under an external demuxer's single reader
     * (acceptShared(), design_docs/0074's slice 3, UtpAcceptor). Governs both whether
     * receiveLoopThread is spawned at all and whether close() may call socket.close() - a shared
     * socket must never be torn down just because one of many connections on it closed. */
    private final boolean ownsSocket;
    /** Null when ownsSocket is false - nothing of this instance's own ever reads socket in that
     * case; the external demuxer feeds packets in via deliverIncoming() instead. */
    private final Thread receiveLoopThread;
    private final Thread retransmitLoopThread;
    private final InputStream inputStream = new UtpInputStream(this);
    private final OutputStream outputStream = new UtpOutputStream(this);
    /** Set only for a shared-socket connection (acceptShared()) - lets UtpAcceptor remove this
     * connection from its registry the moment it closes, rather than polling. Never set (and
     * never invoked) for a self-contained connect()/accept() connection. */
    private volatile Runnable onClosed;

    private UtpSocket(DatagramSocket socket, InetSocketAddress remoteAddress, int sendConnectionId,
                       int receiveConnectionId, int initialLocalSeqNr, int initialRemoteAckNr,
                       boolean ownsSocket) {
        this.socket = socket;
        this.remoteAddress = remoteAddress;
        this.sendConnectionId = sendConnectionId;
        this.receiveConnectionId = receiveConnectionId;
        this.localSeqNr.set(initialLocalSeqNr);
        this.remoteAckNr = initialRemoteAckNr;
        this.ownsSocket = ownsSocket;
        this.receiveLoopThread = ownsSocket
                ? Thread.ofVirtual().name("utp-recv-" + remoteAddress).start(this::receiveLoop)
                : null;
        this.retransmitLoopThread = Thread.ofVirtual().name("utp-retransmit-" + remoteAddress).start(this::retransmitLoop);
    }

    /** For PeerTransport implementations (design_docs/0074's slice 2, peer.UtpPeerTransport) -
     * getInetAddress()/getPort() aren't exposed directly since a single InetSocketAddress is the
     * more natural type for anything actually in this package/module to want. */
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    /** Same single-instance-per-connection contract as Socket.getInputStream()/
     * getOutputStream() - see this field's own Javadoc on why that matters here specifically. */
    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    /** 0 means block forever (the default - see receiveTimeoutMillis's own Javadoc). What
     * UtpPeerTransport.setSoTimeout() delegates to, so PeerConnection's existing
     * HANDSHAKE_TIMEOUT_MS/IDLE_READ_TIMEOUT_MS logic keeps working unchanged over a
     * uTP-backed connection - without this, a silently-dead peer would hang receive() forever,
     * since uTP has no equivalent of a TCP socket's own "peer is gone" read failure. */
    public void setReceiveTimeoutMillis(long millis) {
        this.receiveTimeoutMillis = millis;
    }

    /** Initiates a connection - picks a random connection ID pair (design_docs/0074's own
     * asymmetric-IDs note: this side sends with recvId, expects replies addressed recvId + 1)
     * and a random initial sequence number, sends ST_SYN, and retries with a fixed interval
     * until an ST_STATE reply arrives or HANDSHAKE_MAX_RETRIES is exceeded. socket is not bound
     * or closed by this method - the caller owns its lifecycle (see this class's own Scope
     * note). */
    public static UtpSocket connect(DatagramSocket socket, InetSocketAddress remoteAddress) {
        return connect(socket, remoteAddress, randomUint16(), HANDSHAKE_MAX_RETRIES);
    }

    /** design_docs/0074's slice 4 - TorrentSession's own µTP-first/TCP-fallback attempt needs a
     * short, dedicated budget (Settings.utpConnectTimeoutSeconds) rather than this class's own
     * general-purpose HANDSHAKE_MAX_RETRIES: a peer that never answers µTP (most peers today)
     * should fall back to TCP quickly, not after several seconds. Retry *count* is derived from
     * timeout at the existing fixed HANDSHAKE_RETRY_INTERVAL_MILLIS cadence - a shorter timeout
     * means fewer retries, not a shorter interval between them (this class has only ever had one
     * retry cadence; there's no need for a second independent timing knob). */
    public static UtpSocket connect(DatagramSocket socket, InetSocketAddress remoteAddress, Duration timeout) {
        int maxRetries = Math.max(0, (int) (timeout.toMillis() / HANDSHAKE_RETRY_INTERVAL_MILLIS) - 1);
        return connect(socket, remoteAddress, randomUint16(), maxRetries);
    }

    /** Package-private seam for UtpSocketTest to exercise sequence-number-wraparound behavior
     * deterministically - the public connect() always picks a random initial sequence number,
     * which can't reliably be steered near the 2^16 wrap boundary from outside the package. */
    static UtpSocket connect(DatagramSocket socket, InetSocketAddress remoteAddress, int initialSeqNr) {
        return connect(socket, remoteAddress, initialSeqNr, HANDSHAKE_MAX_RETRIES);
    }

    private static UtpSocket connect(DatagramSocket socket, InetSocketAddress remoteAddress, int initialSeqNr,
                                      int maxRetries) {
        int recvId = randomUint16();
        int sendId = wrap16(recvId + 1);

        UtpPacket syn = new UtpPacket(UtpPacketType.SYN, recvId, nowMicros(), 0, ADVERTISED_RECEIVE_WINDOW_BYTES, initialSeqNr,
                VERSION_UNUSED_ACK, new byte[0]);

        try {
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                sendRaw(socket, remoteAddress, syn);
                try {
                    socket.setSoTimeout((int) HANDSHAKE_RETRY_INTERVAL_MILLIS);
                    byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
                    DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagram);
                    UtpPacket reply = UtpPacketCodec.decode(Arrays.copyOf(datagram.getData(), datagram.getLength()));
                    if (reply.type() == UtpPacketType.STATE && reply.connectionId() == sendId) {
                        // The initiator's own future packets (DATA/ACK/FIN) keep using recvId -
                        // the same id the SYN itself used, not sendId - see this method's own
                        // "sends every packet with connection_id = recv_id" doc above. Passing
                        // these two swapped here was a real bug (design_docs/0074's asymmetric-
                        // IDs note flagged this as "the single easiest detail to get backwards"
                        // - it was): every post-handshake packet this side sent then carried the
                        // *acceptor's* id, which the acceptor's own receiveConnectionId check
                        // silently rejected, hanging every data transfer.
                        return new UtpSocket(socket, remoteAddress, recvId, sendId, wrap16(initialSeqNr + 1),
                                reply.seqNr(), true);
                    }
                    // Not the reply we're waiting for (stray/unrelated packet on this socket) -
                    // ignore it and keep waiting out this attempt's remaining retry budget.
                } catch (IOException e) {
                    // Timed out or a transient read error - fall through and retry the SYN.
                }
            }
        } finally {
            resetSoTimeoutQuietly(socket);
        }
        throw new UtpException("uTP handshake to " + remoteAddress + " timed out after " + maxRetries
                + " retries");
    }

    /** Accepts a connection - blocks reading socket until a valid ST_SYN arrives from any
     * sender, then replies ST_STATE and returns. socket is not bound or closed by this method. */
    public static UtpSocket accept(DatagramSocket socket) {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (true) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (IOException e) {
                throw new UtpException("Failed reading from socket while waiting for a uTP SYN", e);
            }
            UtpPacket packet;
            try {
                packet = UtpPacketCodec.decode(Arrays.copyOf(datagram.getData(), datagram.getLength()));
            } catch (UtpException e) {
                continue; // Not a well-formed uTP packet at all - ignore and keep listening.
            }
            if (packet.type() != UtpPacketType.SYN) {
                continue;
            }
            InetSocketAddress remoteAddress = (InetSocketAddress) datagram.getSocketAddress();
            int receiveConnectionId = packet.connectionId();
            int sendConnectionId = wrap16(receiveConnectionId + 1);
            int initialLocalSeqNr = randomUint16();

            UtpPacket state = new UtpPacket(UtpPacketType.STATE, sendConnectionId, nowMicros(),
                    delaySinceMicros(packet.timestampMicros()), ADVERTISED_RECEIVE_WINDOW_BYTES, initialLocalSeqNr, packet.seqNr(),
                    new byte[0]);
            sendRaw(socket, remoteAddress, state);
            return new UtpSocket(socket, remoteAddress, sendConnectionId, receiveConnectionId,
                    wrap16(initialLocalSeqNr + 1), packet.seqNr(), true);
        }
    }

    /** Completes the uTP-level handshake for a connection whose ST_SYN has already been read and
     * decoded by an external demuxer sharing socket with other traffic (design_docs/0074's slice
     * 3, utp.UtpAcceptor) - the same connection-id-assignment/STATE-reply logic accept(
     * DatagramSocket) above uses, just fed a pre-read syn instead of reading one itself. Unlike
     * accept(DatagramSocket), this neither reads from nor exclusively owns socket afterward: the
     * caller keeps the single-reader role for socket's whole lifetime, and must route every
     * future packet matching this connection's (remoteAddress, connectionId) to
     * deliverIncoming() - this instance runs no receive loop of its own. */
    public static UtpSocket acceptShared(DatagramSocket socket, InetSocketAddress remoteAddress, UtpPacket syn) {
        int receiveConnectionId = syn.connectionId();
        int sendConnectionId = wrap16(receiveConnectionId + 1);
        int initialLocalSeqNr = randomUint16();

        UtpPacket state = new UtpPacket(UtpPacketType.STATE, sendConnectionId, nowMicros(),
                delaySinceMicros(syn.timestampMicros()), ADVERTISED_RECEIVE_WINDOW_BYTES, initialLocalSeqNr, syn.seqNr(),
                new byte[0]);
        sendRaw(socket, remoteAddress, state);
        return new UtpSocket(socket, remoteAddress, sendConnectionId, receiveConnectionId,
                wrap16(initialLocalSeqNr + 1), syn.seqNr(), false);
    }

    /** Feeds one already-decoded, already-connection-matched incoming packet to this
     * connection's state machine - the shared-socket counterpart to what receiveLoop() does per
     * packet for a self-contained connection. Only ever called by the external demuxer that owns
     * the shared socket's single read loop (utp.UtpAcceptor) - matching by (remoteAddress,
     * connectionId) is the demuxer's own job, not re-checked here. */
    public void deliverIncoming(UtpPacket packet) {
        handleIncoming(packet);
    }

    /** Lets a shared-socket connection's owner (utp.UtpAcceptor) learn the moment this connection
     * closes, so it can drop this instance from its registry rather than leaking it forever.
     * Never needed (and never invoked) for a self-contained connect()/accept() connection - its
     * owner already knows its own lifecycle without a callback. */
    public void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    /** Package-private, for UtpSocketTest's own connection-ID-asymmetry assertions - not
     * otherwise needed once slice 2+ callers only care that data moves correctly. */
    int sendConnectionId() {
        return sendConnectionId;
    }

    int receiveConnectionId() {
        return receiveConnectionId;
    }

    /** Reliable, in-order send - chunks data into MAX_PAYLOAD_LENGTH-sized DATA packets, each
     * queued for delivery. Blocks only on window backpressure (see acquireWindowSlot()), not
     * until actually acknowledged - the same "copy into the send buffer and return" contract a
     * blocking TCP write() gives. */
    public void send(byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(MAX_PAYLOAD_LENGTH, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + length);
            sendChunk(chunk);
            offset += length;
        }
    }

    private void sendChunk(byte[] chunk) {
        acquireWindowSlot(chunk.length);
        int seqNr = localSeqNr.getAndUpdate(v -> wrap16(v + 1));
        synchronized (outstandingLock) {
            outstanding.put(seqNr, new Outstanding(seqNr, chunk));
        }
        transmit(UtpPacketType.DATA, seqNr, chunk);
    }

    private void acquireWindowSlot(int length) {
        synchronized (windowLock) {
            while (bytesInFlight + length > Math.min(congestionControl.cwndBytes(), remoteWindowBytes) && !closed) {
                try {
                    windowLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UtpException("Interrupted waiting for uTP send window", e);
                }
            }
            bytesInFlight += length;
        }
    }

    private void releaseWindowSlot(int length) {
        synchronized (windowLock) {
            bytesInFlight = Math.max(0, bytesInFlight - length);
            windowLock.notifyAll();
        }
    }

    /** Blocks for the next in-order chunk of payload, or returns null once the remote side has
     * sent ST_FIN (or this connection has been closed locally) and every already-queued chunk
     * has been drained. Blocks forever unless setReceiveTimeoutMillis() was called with a
     * positive value, in which case a genuine timeout throws UtpException - deliberately
     * distinct from the null/EOF return, the same distinction Socket.setSoTimeout() draws
     * between a real end-of-stream and a read timing out. */
    public byte[] receive() {
        try {
            long timeout = receiveTimeoutMillis;
            byte[] chunk = timeout > 0 ? incoming.poll(timeout, TimeUnit.MILLISECONDS) : incoming.take();
            if (chunk == null) {
                throw new UtpException("uTP receive timed out after " + timeout + "ms");
            }
            if (chunk == END_OF_STREAM) {
                incoming.offer(END_OF_STREAM); // let a second concurrent receive() also see EOF
                return null;
            }
            return chunk;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UtpException("Interrupted waiting for uTP data", e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            transmit(UtpPacketType.FIN, localSeqNr.getAndUpdate(v -> wrap16(v + 1)), new byte[0]);
        } catch (RuntimeException ignored) {
            // Best-effort - the remote may already be gone, or the socket may already be
            // unusable; either way this side is closing regardless.
        }
        synchronized (windowLock) {
            windowLock.notifyAll();
        }
        incoming.offer(END_OF_STREAM);
        if (ownsSocket) {
            socket.close();
            receiveLoopThread.interrupt();
        }
        retransmitLoopThread.interrupt();
        if (onClosed != null) {
            onClosed.run();
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (!closed) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (SocketException e) {
                return; // Socket closed (by close() above) - normal shutdown, not an error.
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                continue;
            }
            UtpPacket packet;
            try {
                packet = UtpPacketCodec.decode(Arrays.copyOf(datagram.getData(), datagram.getLength()));
            } catch (UtpException e) {
                continue; // Malformed/garbage datagram - drop and keep listening, same
                // tolerance DhtNode.handlePacket already applies to its own KRPC decode.
            }
            if (packet.connectionId() != receiveConnectionId) {
                continue; // A stray packet - receiveLoop() only ever runs for an ownsSocket=true
                // connection (see this method's own caller, the constructor), which never
                // shares its socket with anything else, so this should be rare in practice.
            }
            handleIncoming(packet);
        }
    }

    private void handleIncoming(UtpPacket packet) {
        long delayMicros = delaySinceMicros(packet.timestampMicros());
        lastObservedDelayMicros = delayMicros;
        // Must happen before acknowledgeOutstanding()/onBytesAcked() below - see
        // LedbatCongestionControl.onDelaySample()'s own Javadoc on why order matters here.
        congestionControl.onDelaySample(delayMicros, System.currentTimeMillis());
        remoteWindowBytes = packet.windowSize();
        int bytesAcked = acknowledgeOutstanding(packet.ackNr());
        if (bytesAcked > 0) {
            congestionControl.onBytesAcked(bytesAcked, delayMicros);
        }

        if (packet.type() == UtpPacketType.STATE) {
            return; // Pure ack - never advances remote sequence tracking, never triggers a reply
            // (replying to a STATE with another STATE would ping-pong forever).
        }
        if (packet.type() == UtpPacketType.RESET) {
            close();
            return;
        }

        int expectedNext = wrap16(remoteAckNr + 1);
        if (packet.seqNr() == expectedNext) {
            remoteAckNr = packet.seqNr();
            if (packet.type() == UtpPacketType.FIN) {
                incoming.offer(END_OF_STREAM);
            } else if (packet.payload().length > 0) {
                incoming.offer(packet.payload());
            }
        }
        // Old/duplicate or out-of-order-ahead packets: dropped, not buffered (design_docs/0074's
        // own "no selective ack yet" note) - still worth an immediate ack either way, so a
        // sender waiting on a lost packet's retransmission at least sees this side is alive.
        sendAck();
    }

    /** ackNr is cumulative (BEP 29): every outstanding packet whose seqNr is not "after" ackNr
     * in the wraparound sequence space is now acknowledged. Each one releases its reserved
     * window bytes and, unless it was ever retransmitted (Karn's algorithm - a retransmitted
     * packet's timing can't tell us which attempt the ack is actually for), contributes an RTT
     * sample. */
    private int acknowledgeOutstanding(int ackNr) {
        List<Outstanding> acked = new ArrayList<>();
        synchronized (outstandingLock) {
            Iterator<Outstanding> iterator = outstanding.values().iterator();
            while (iterator.hasNext()) {
                Outstanding candidate = iterator.next();
                if (!isAfter(candidate.seqNr, ackNr)) {
                    acked.add(candidate);
                    iterator.remove();
                }
            }
        }
        long now = System.nanoTime();
        int bytesAcked = 0;
        for (Outstanding o : acked) {
            releaseWindowSlot(o.payload.length);
            bytesAcked += o.payload.length;
            if (o.retryCount == 0) {
                sampleRtt(TimeUnit.NANOSECONDS.toMillis(now - o.sentAtNanos));
            }
        }
        return bytesAcked;
    }

    private void sendAck() {
        transmit(UtpPacketType.STATE, localSeqNr.get(), new byte[0]);
    }

    private void transmit(UtpPacketType type, int seqNr, byte[] payload) {
        UtpPacket packet = new UtpPacket(type, sendConnectionId, nowMicros(), lastObservedDelayMicros,
                ADVERTISED_RECEIVE_WINDOW_BYTES, seqNr, remoteAckNr, payload);
        sendRaw(socket, remoteAddress, packet);
    }

    private void retransmitLoop() {
        while (!closed) {
            Outstanding earliest = null;
            long now = System.nanoTime();
            long minDeadlineNanos = Long.MAX_VALUE;
            long rtoNanos = TimeUnit.MILLISECONDS.toNanos(rtoMillis());
            synchronized (outstandingLock) {
                for (Outstanding candidate : outstanding.values()) {
                    long deadline = candidate.sentAtNanos + rtoNanos;
                    if (deadline < minDeadlineNanos) {
                        minDeadlineNanos = deadline;
                        earliest = candidate;
                    }
                }
            }
            if (earliest == null) {
                sleepQuietly(IDLE_CHECK_INTERVAL_MILLIS);
                continue;
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(minDeadlineNanos - now);
            if (remainingMillis > 0) {
                sleepQuietly(Math.min(remainingMillis, IDLE_CHECK_INTERVAL_MILLIS));
                continue;
            }
            retransmit(earliest);
        }
    }

    private void retransmit(Outstanding outstanding) {
        outstanding.retryCount++;
        outstanding.sentAtNanos = System.nanoTime();
        // This connection's one loss signal (design_docs/0074's slice 5) - no selective
        // ack/fast retransmit yet, so an RTO firing is the only way loss is ever detected.
        congestionControl.onLoss();
        if (outstanding.retryCount > MAX_RETRIES) {
            close();
            return;
        }
        transmit(UtpPacketType.DATA, outstanding.seqNr, outstanding.payload);
    }

    private long rtoMillis() {
        if (srttMillis < 0) {
            return INITIAL_RTO_MILLIS;
        }
        double rto = srttMillis + Math.max(100, 4 * rttVarMillis);
        return (long) Math.min(MAX_RTO_MILLIS, Math.max(MIN_RTO_MILLIS, rto));
    }

    private void sampleRtt(long sampleMillis) {
        if (srttMillis < 0) {
            srttMillis = sampleMillis;
            rttVarMillis = sampleMillis / 2.0;
        } else {
            rttVarMillis = 0.75 * rttVarMillis + 0.25 * Math.abs(srttMillis - sampleMillis);
            srttMillis = 0.875 * srttMillis + 0.125 * sampleMillis;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Back to blocking (no deadline) - the handshake's own short retry timeout must not leak
     * into the socket's later use, whether that's this same socket handed to the new UtpSocket's
     * own receive loop on success, or handed back to the caller unchanged on failure. */
    private static void resetSoTimeoutQuietly(DatagramSocket socket) {
        try {
            socket.setSoTimeout(0);
        } catch (SocketException ignored) {
            // Best-effort - if the socket is already unusable, there's nothing left to reset.
        }
    }

    private static void sendRaw(DatagramSocket socket, InetSocketAddress remoteAddress, UtpPacket packet) {
        byte[] wireBytes = UtpPacketCodec.encode(packet);
        try {
            socket.send(new DatagramPacket(wireBytes, wireBytes.length, remoteAddress));
        } catch (IOException e) {
            throw new UtpException("Failed sending uTP packet to " + remoteAddress, e);
        }
    }

    /** True if a is "after" b in the wraparound 16-bit sequence space - TCP's own signed-
     * difference comparison trick (design_docs/0074's own wraparound note), safe across the
     * 2^16 wrap rather than comparing the raw unwrapped values. */
    private static boolean isAfter(int a, int b) {
        return (short) (a - b) > 0;
    }

    private static int wrap16(int value) {
        return value & 0xFFFF;
    }

    private static int randomUint16() {
        return RANDOM.nextInt(0x10000);
    }

    private static long nowMicros() {
        return (System.nanoTime() / 1000) & 0xFFFFFFFFL;
    }

    /** The one-way-delay estimate this side has of the remote, computed from the last packet
     * actually received - see design_docs/0074's note on timestamp_difference_microseconds
     * needing no clock synchronization since only the difference is ever used. */
    private static long delaySinceMicros(long remoteSentMicros) {
        long delay = nowMicros() - remoteSentMicros;
        return delay & 0xFFFFFFFFL;
    }

    private static final class Outstanding {
        final int seqNr;
        final byte[] payload;
        volatile long sentAtNanos;
        volatile int retryCount;

        Outstanding(int seqNr, byte[] payload) {
            this.seqNr = seqNr;
            this.payload = payload;
            this.sentAtNanos = System.nanoTime();
        }
    }
}
