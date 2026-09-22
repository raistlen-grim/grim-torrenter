package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.proxy.ProxyProvider;
import com.grimtorrenter.engine.proxy.ProxySettings;
import com.grimtorrenter.engine.proxy.Socks5;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * BEP 15 UDP tracker client. Retry/timeout policy is a deliberately
 * simplified, tighter-bounded version of BEP 15's suggested 8-attempt
 * exponential backoff (which totals over an hour) - see design_docs/0023.
 */
public final class UdpTrackerClient implements TrackerClient {

    private static final long PROTOCOL_ID = 0x41727101980L;
    private static final int ACTION_CONNECT = 0;
    private static final int ACTION_ANNOUNCE = 1;
    private static final int ACTION_ERROR = 3;

    private static final int DEFAULT_MAX_ATTEMPTS = 2;
    private static final int DEFAULT_BASE_TIMEOUT_MS = 3_000;
    private static final int RECEIVE_BUFFER_SIZE = 2048;

    private static final int PROXY_CONNECT_TIMEOUT_MS = 10_000;

    private final String announceUrl;
    private final String host;
    private final int port;
    private final int baseTimeoutMs;
    private final int maxAttempts;
    private final ProxyProvider proxyProvider;
    private final SecureRandom random = new SecureRandom();
    /** Identifies this client consistently to the tracker across announces (e.g. through
     * an IP change) - generated once per client instance, not per announce, per BEP 15. */
    private final int key = random.nextInt();

    public UdpTrackerClient(String announceUrl) {
        this(announceUrl, DEFAULT_BASE_TIMEOUT_MS, DEFAULT_MAX_ATTEMPTS, ProxyProvider.NONE);
    }

    /** proxyProvider is read on every announce: while it names a proxy, the datagrams go through
     * that SOCKS5 proxy's UDP relay (and the tracker's hostname is resolved by the proxy, not
     * locally); otherwise straight out as before. A proxy that can't be reached, or won't relay
     * UDP, fails the announce - never a silent direct one. See design_docs/0079. */
    public UdpTrackerClient(String announceUrl, ProxyProvider proxyProvider) {
        this(announceUrl, DEFAULT_BASE_TIMEOUT_MS, DEFAULT_MAX_ATTEMPTS, proxyProvider);
    }

    /** Package-private: lets tests use much shorter timeouts than the production defaults. */
    UdpTrackerClient(String announceUrl, int baseTimeoutMs, int maxAttempts) {
        this(announceUrl, baseTimeoutMs, maxAttempts, ProxyProvider.NONE);
    }

    UdpTrackerClient(String announceUrl, int baseTimeoutMs, int maxAttempts, ProxyProvider proxyProvider) {
        this.proxyProvider = proxyProvider;
        URI uri = URI.create(announceUrl);
        if (!"udp".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not a UDP tracker URL: " + announceUrl);
        }
        if (uri.getHost() == null || uri.getPort() < 0) {
            throw new IllegalArgumentException("UDP tracker URL missing host/port: " + announceUrl);
        }
        this.announceUrl = announceUrl;
        this.host = uri.getHost();
        this.port = uri.getPort();
        this.baseTimeoutMs = baseTimeoutMs;
        this.maxAttempts = maxAttempts;
    }

    /** One datagram out, one datagram back - the only two things BEP 15's request/response
     * exchange needs from a transport, so the same protocol code runs over a plain UDP socket or
     * over a SOCKS5 relay. receive() throws SocketTimeoutException when nothing arrives in time. */
    private interface Datagrams {
        void send(byte[] data) throws IOException;

        byte[] receive(int timeoutMs) throws IOException;
    }

    @Override
    public TrackerResponse announce(TrackerRequest request) {
        Optional<ProxySettings> proxy = proxyProvider.current();
        if (proxy.isPresent()) {
            return announceViaProxy(proxy.get(), request);
        }
        InetSocketAddress trackerAddress;
        try {
            // Resolved fresh on every call, not cached from construction - a transient DNS
            // failure (or a tracker's dynamic-DNS IP changing) shouldn't permanently break
            // this client for the rest of its lifetime, which spans the whole TorrentSession.
            trackerAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (UnknownHostException e) {
            throw new TrackerException("Could not resolve UDP tracker host '" + host + "'", e);
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            Datagrams datagrams = new Datagrams() {
                private final byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];

                @Override
                public void send(byte[] data) throws IOException {
                    socket.send(new DatagramPacket(data, data.length, trackerAddress));
                }

                @Override
                public byte[] receive(int timeoutMs) throws IOException {
                    socket.setSoTimeout(timeoutMs);
                    DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                    socket.receive(in);
                    return java.util.Arrays.copyOf(in.getData(), in.getLength());
                }
            };
            long connectionId = connect(datagrams);
            return sendAnnounce(datagrams, connectionId, request);
        } catch (IOException e) {
            throw new TrackerException("UDP tracker request to " + announceUrl + " failed", e);
        } catch (BufferUnderflowException e) {
            throw new TrackerException("UDP tracker " + announceUrl + " sent a malformed (too short) response", e);
        }
    }

    /** The tracker's hostname is handed to the proxy as-is - the proxy resolves it, so no DNS
     * lookup for it ever leaves this machine. */
    private TrackerResponse announceViaProxy(ProxySettings proxy, TrackerRequest request) {
        try (Socks5.UdpRelay relay = Socks5.openUdpRelay(proxy, PROXY_CONNECT_TIMEOUT_MS)) {
            Datagrams datagrams = new Datagrams() {
                @Override
                public void send(byte[] data) throws IOException {
                    relay.send(host, port, data);
                }

                @Override
                public byte[] receive(int timeoutMs) throws IOException {
                    return relay.receive(timeoutMs);
                }
            };
            long connectionId = connect(datagrams);
            return sendAnnounce(datagrams, connectionId, request);
        } catch (IOException e) {
            throw new TrackerException("UDP tracker request to " + announceUrl + " through the proxy failed: "
                    + e.getMessage(), e);
        } catch (BufferUnderflowException e) {
            throw new TrackerException("UDP tracker " + announceUrl + " sent a malformed (too short) response", e);
        }
    }

    private long connect(Datagrams datagrams) throws IOException {
        int transactionId = random.nextInt();
        ByteBuffer out = ByteBuffer.allocate(16);
        out.putLong(PROTOCOL_ID);
        out.putInt(ACTION_CONNECT);
        out.putInt(transactionId);

        ByteBuffer in = sendWithRetry(datagrams, out.array());
        int action = in.getInt();
        int responseTransactionId = in.getInt();
        checkResponse(action, ACTION_CONNECT, transactionId, responseTransactionId, in);
        return in.getLong();
    }

    private TrackerResponse sendAnnounce(Datagrams datagrams, long connectionId, TrackerRequest request)
            throws IOException {
        int transactionId = random.nextInt();
        ByteBuffer out = ByteBuffer.allocate(98);
        out.putLong(connectionId);
        out.putInt(ACTION_ANNOUNCE);
        out.putInt(transactionId);
        out.put(request.infoHash().bytes());
        out.put(request.peerId().bytes());
        out.putLong(request.downloaded());
        out.putLong(request.left());
        out.putLong(request.uploaded());
        out.putInt(eventCode(request.event()));
        out.putInt(0); // ip - 0 means "use the sender's address"
        out.putInt(key);
        out.putInt(request.numWant());
        out.putShort((short) request.port());

        ByteBuffer in = sendWithRetry(datagrams, out.array());
        int action = in.getInt();
        int responseTransactionId = in.getInt();
        checkResponse(action, ACTION_ANNOUNCE, transactionId, responseTransactionId, in);

        long interval = Integer.toUnsignedLong(in.getInt());
        int leechers = in.getInt();
        int seeders = in.getInt();
        List<PeerAddress> peers = readPeers(in);

        return new TrackerResponse(interval, null, seeders, leechers, peers, null, null);
    }

    private static int eventCode(TrackerEvent event) {
        if (event == null) {
            return 0;
        }
        return switch (event) {
            case COMPLETED -> 1;
            case STARTED -> 2;
            case STOPPED -> 3;
        };
    }

    private List<PeerAddress> readPeers(ByteBuffer in) {
        List<PeerAddress> peers = new ArrayList<>();
        while (in.remaining() >= 6) {
            byte[] addressBytes = new byte[4];
            in.get(addressBytes);
            int port = Short.toUnsignedInt(in.getShort());
            try {
                peers.add(new PeerAddress(InetAddress.getByAddress(addressBytes), port));
            } catch (UnknownHostException e) {
                throw new TrackerException("Invalid peer address in UDP tracker response", e);
            }
        }
        return List.copyOf(peers);
    }

    /** Common checks for every response: transaction id must match what we sent, and the
     * tracker may signal failure via ACTION_ERROR with a trailing UTF-8 message instead of
     * the expected action. */
    private void checkResponse(int action, int expectedAction, int sentTransactionId,
                                int responseTransactionId, ByteBuffer remaining) {
        if (responseTransactionId != sentTransactionId) {
            throw new TrackerException("UDP tracker " + announceUrl + " returned a mismatched transaction id");
        }
        if (action == ACTION_ERROR) {
            byte[] messageBytes = new byte[remaining.remaining()];
            remaining.get(messageBytes);
            throw new TrackerException(
                    "UDP tracker " + announceUrl + " returned error: " + new String(messageBytes, StandardCharsets.UTF_8));
        }
        if (action != expectedAction) {
            throw new TrackerException("UDP tracker " + announceUrl + " returned unexpected action " + action);
        }
    }

    private ByteBuffer sendWithRetry(Datagrams datagrams, byte[] requestBytes) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            datagrams.send(requestBytes);
            try {
                return ByteBuffer.wrap(datagrams.receive(baseTimeoutMs * (1 << attempt)));
            } catch (SocketTimeoutException e) {
                lastFailure = e;
            }
        }
        throw new TrackerException(
                "UDP tracker " + announceUrl + " did not respond after " + maxAttempts + " attempts", lastFailure);
    }
}
