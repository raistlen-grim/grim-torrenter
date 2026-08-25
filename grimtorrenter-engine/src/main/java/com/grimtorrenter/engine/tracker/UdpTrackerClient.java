package com.grimtorrenter.engine.tracker;

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

    private final String announceUrl;
    private final String host;
    private final int port;
    private final int baseTimeoutMs;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();
    /** Identifies this client consistently to the tracker across announces (e.g. through
     * an IP change) - generated once per client instance, not per announce, per BEP 15. */
    private final int key = random.nextInt();

    public UdpTrackerClient(String announceUrl) {
        this(announceUrl, DEFAULT_BASE_TIMEOUT_MS, DEFAULT_MAX_ATTEMPTS);
    }

    /** Package-private: lets tests use much shorter timeouts than the production defaults. */
    UdpTrackerClient(String announceUrl, int baseTimeoutMs, int maxAttempts) {
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

    @Override
    public TrackerResponse announce(TrackerRequest request) {
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
            long connectionId = connect(socket, trackerAddress);
            return sendAnnounce(socket, connectionId, trackerAddress, request);
        } catch (IOException e) {
            throw new TrackerException("UDP tracker request to " + announceUrl + " failed", e);
        } catch (BufferUnderflowException e) {
            throw new TrackerException("UDP tracker " + announceUrl + " sent a malformed (too short) response", e);
        }
    }

    private long connect(DatagramSocket socket, InetSocketAddress trackerAddress) throws IOException {
        int transactionId = random.nextInt();
        ByteBuffer out = ByteBuffer.allocate(16);
        out.putLong(PROTOCOL_ID);
        out.putInt(ACTION_CONNECT);
        out.putInt(transactionId);

        ByteBuffer in = sendWithRetry(socket, trackerAddress, out.array());
        int action = in.getInt();
        int responseTransactionId = in.getInt();
        checkResponse(action, ACTION_CONNECT, transactionId, responseTransactionId, in);
        return in.getLong();
    }

    private TrackerResponse sendAnnounce(DatagramSocket socket, long connectionId,
                                          InetSocketAddress trackerAddress, TrackerRequest request)
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

        ByteBuffer in = sendWithRetry(socket, trackerAddress, out.array());
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

    private ByteBuffer sendWithRetry(DatagramSocket socket, InetSocketAddress trackerAddress, byte[] requestBytes)
            throws IOException {
        DatagramPacket outPacket = new DatagramPacket(requestBytes, requestBytes.length, trackerAddress);
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        DatagramPacket inPacket = new DatagramPacket(buffer, buffer.length);

        IOException lastFailure = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            socket.send(outPacket);
            socket.setSoTimeout(baseTimeoutMs * (1 << attempt));
            try {
                socket.receive(inPacket);
                return ByteBuffer.wrap(inPacket.getData(), 0, inPacket.getLength());
            } catch (SocketTimeoutException e) {
                lastFailure = e;
            }
        }
        throw new TrackerException(
                "UDP tracker " + announceUrl + " did not respond after " + maxAttempts + " attempts", lastFailure);
    }
}
