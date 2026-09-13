package com.grimtorrenter.engine.utp;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Demuxes many µTP connections sharing one externally-owned {@link DatagramSocket} by connection
 * id (design_docs/0074's slice 3) - the shared-socket counterpart to what {@code receiveLoop()} +
 * {@code accept(DatagramSocket)} do together for a single self-contained {@link UtpSocket}.
 * Deliberately BitTorrent-agnostic, same spirit as {@code UtpSocket} itself: knows nothing about
 * {@code PeerConnection}/{@code TorrentSession}, only ever deals in {@code UtpSocket}/
 * {@code UtpPacket}. Never reads from socket itself - whoever owns that socket's single reader
 * (DhtNode) hands every already-identified-as-µTP datagram to {@link #handlePacket} instead.
 */
public final class UtpAcceptor {

    private final DatagramSocket socket;
    private final Consumer<UtpSocket> onAccepted;
    private final Map<ConnectionKey, UtpSocket> connections = new ConcurrentHashMap<>();

    public UtpAcceptor(DatagramSocket socket, Consumer<UtpSocket> onAccepted) {
        this.socket = socket;
        this.onAccepted = onAccepted;
    }

    /** Never lets an exception escape - a single malformed or otherwise unhandleable datagram
     * must not disrupt whichever caller's own receive loop this is invoked from, same tolerance
     * DhtNode already applies to its own KRPC decode. */
    public void handlePacket(byte[] data, InetSocketAddress from) {
        UtpPacket packet;
        try {
            packet = UtpPacketCodec.decode(data);
        } catch (UtpException e) {
            return; // Not a well-formed uTP packet after all - drop it.
        }
        ConnectionKey key = new ConnectionKey(from, packet.connectionId());
        UtpSocket existing = connections.get(key);
        if (existing != null) {
            existing.deliverIncoming(packet);
            return;
        }
        if (packet.type() != UtpPacketType.SYN) {
            return; // A stray packet for a connection we don't (or no longer) know about.
        }
        UtpSocket accepted = UtpSocket.acceptShared(socket, from, packet);
        connections.put(key, accepted);
        accepted.setOnClosed(() -> connections.remove(key));
        // One virtual thread per accepted connection, same "never block the shared reader on a
        // slow/hanging handshake" reasoning PeerServer.acceptLoop() already applies to TCP.
        Thread.ofVirtual().name("utp-accept-" + from).start(() -> onAccepted.accept(accepted));
    }

    /** Package-private, for UtpAcceptorTest's own no-leak assertion after a connection closes -
     * not otherwise needed once a real caller (DhtNode) only cares that packets get routed. */
    int connectionCount() {
        return connections.size();
    }

    private record ConnectionKey(InetSocketAddress remoteAddress, int connectionId) {
    }
}
