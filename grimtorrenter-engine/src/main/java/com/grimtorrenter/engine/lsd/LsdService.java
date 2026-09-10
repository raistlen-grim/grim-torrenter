package com.grimtorrenter.engine.lsd;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * BEP 14 Local Service Discovery - announces active, non-private torrents to the LAN via IPv4
 * multicast ({@code 239.192.152.143:6771}) and listens for the same from other clients, finding
 * peers already on the same network without waiting on a tracker/DHT round-trip. One instance
 * per TorrentEngine, not one per torrent - mirrors DhtNode/PeerServer owning one shared socket
 * rather than one per session (see design_docs/0062).
 *
 * <p>Runs its own receive loop on one dedicated virtual thread, written as ordinary
 * blocking-style I/O per design_docs/0007, same as DhtNode's/PeerServer's own loops. A
 * malformed announcement (from some other, possibly non-conforming implementation) is caught
 * and dropped without killing the loop.
 *
 * <p><b>Self-suppression via a random per-instance cookie</b> (BEP 14's own {@code cookie}
 * header), included in every outgoing announce and checked against every received one, rather
 * than relying on {@code IP_MULTICAST_LOOP}/loopback-mode socket options - those vary by
 * platform/interface and this works uniformly regardless.
 *
 * <p><b>Interface handling</b>: joins the multicast group for receiving on every interface that
 * is up, supports multicast, and isn't loopback (the auto-detect public constructor), or on a
 * caller-supplied list (the package-private constructor tests use, to pin to the loopback
 * interface). Outbound sends go via a single interface, fixed once at construction
 * (interfaces.get(0)) rather than re-selected per send - MulticastSocket's outgoing interface is
 * process-wide mutable socket state, and this class's own periodic announce can already overlap
 * itself if one tick runs long (TorrentEngine dispatches each tick onto its own virtual thread);
 * fixing it once avoids a real race rather than adding synchronization for a genuinely minor,
 * LAN-only feature. A host with multiple LAN-facing interfaces is still fully able to *receive*
 * LSD traffic on every one of them - only proactive announcing is limited to the primary one.
 */
public final class LsdService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LsdService.class.getName());
    private static final int RECEIVE_BUFFER_SIZE = 1024;
    private static final SecureRandom COOKIE_RANDOM = new SecureRandom();

    private final MulticastSocket socket;
    private final InetAddress groupAddress;
    private final List<NetworkInterface> interfaces;
    private final int torrentListenPort;
    private final BiConsumer<InfoHash, PeerAddress> onPeerFound;
    private final String cookie;
    private volatile boolean closed;

    public LsdService(int torrentListenPort, BiConsumer<InfoHash, PeerAddress> onPeerFound) throws IOException {
        this(torrentListenPort, onPeerFound, suitableInterfaces());
    }

    /** Pins to a caller-supplied set of interfaces instead of auto-detecting the host's real
     * LAN interfaces - lets a test (or an advanced caller) target the loopback interface
     * specifically, which the kernel always delivers on locally regardless of any firewall or
     * switch in between, unlike a real physical interface (see TorrentEngine's own
     * lsdInterfacesForTesting-accepting constructor, design_docs/0062's own addendum). Public,
     * not package-private, so TorrentEngine (a different package) can use it for exactly that
     * purpose; production code always goes through the two-arg auto-detecting constructor
     * above. */
    public LsdService(int torrentListenPort, BiConsumer<InfoHash, PeerAddress> onPeerFound,
                       List<NetworkInterface> interfaces) throws IOException {
        if (interfaces.isEmpty()) {
            throw new IOException("No multicast-capable network interface available for LSD");
        }
        this.torrentListenPort = torrentListenPort;
        this.onPeerFound = onPeerFound;
        this.interfaces = List.copyOf(interfaces);
        this.groupAddress = InetAddress.getByName(LsdCodec.MULTICAST_GROUP);
        this.cookie = generateCookie();

        // Unbound construction + setReuseAddress(true) before bind() - same rationale as
        // DhtNode/PeerServer's own matching comment (a quick rebind right after close(), e.g.
        // Quarkus dev mode's live-reload, can otherwise fail with "Address already in use").
        // SO_REUSEADDR also lets two instances on the same host (e.g. this test suite) share
        // the multicast port, which is normal/expected for multicast unlike TCP. See
        // design_docs/0058/0062.
        this.socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(LsdCodec.MULTICAST_PORT));
        socket.setNetworkInterface(this.interfaces.get(0));
        for (NetworkInterface networkInterface : this.interfaces) {
            try {
                socket.joinGroup(new InetSocketAddress(groupAddress, LsdCodec.MULTICAST_PORT), networkInterface);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Could not join LSD multicast group on " + networkInterface.getName(), e);
            }
        }
        Thread.ofVirtual().name("lsd-receive").start(this::receiveLoop);
    }

    private static String generateCookie() {
        byte[] bytes = new byte[8];
        COOKIE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static List<NetworkInterface> suitableInterfaces() throws IOException {
        List<NetworkInterface> result = new ArrayList<>();
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all.hasMoreElements()) {
            NetworkInterface networkInterface = all.nextElement();
            if (networkInterface.isUp() && networkInterface.supportsMulticast() && !networkInterface.isLoopback()) {
                result.add(networkInterface);
            }
        }
        return result;
    }

    /** Sends one BT-SEARCH announcement per info hash. Safe to call from any thread - the
     * outgoing interface is fixed at construction (see this class's own Javadoc), so concurrent
     * calls only race on the underlying socket's send buffer, which java.net.DatagramSocket
     * already handles safely for concurrent sends. */
    public void announce(Collection<InfoHash> infoHashes) {
        for (InfoHash infoHash : infoHashes) {
            byte[] payload = LsdCodec.encode(new LsdMessage(infoHash, torrentListenPort, cookie));
            DatagramPacket packet =
                    new DatagramPacket(payload, payload.length, groupAddress, LsdCodec.MULTICAST_PORT);
            try {
                socket.send(packet);
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(System.Logger.Level.DEBUG, "LSD announce failed for " + infoHash, e);
                }
            }
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(System.Logger.Level.WARNING, "LSD receive loop failed", e);
                }
                return;
            }
            try {
                byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                        packet.getOffset() + packet.getLength());
                LsdMessage message = LsdCodec.decode(data);
                if (message.cookie().equals(cookie)) {
                    continue;
                }
                onPeerFound.accept(message.infoHash(), new PeerAddress(packet.getAddress(), message.port()));
            } catch (RuntimeException e) {
                // Malformed announcement from some other implementation - drop and keep
                // listening, same tolerance PeerServer/MetadataFetcher already apply to
                // malformed peer-supplied input elsewhere in this codebase.
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
