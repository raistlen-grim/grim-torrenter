package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * A BEP 5 Mainline DHT node: owns the single UDP socket used both to query other nodes and
 * to answer queries from them, matching a response back to whichever of our own outgoing
 * queries it belongs to by transaction id. Runs its receive loop on one dedicated virtual
 * thread, written as ordinary blocking-style I/O per design_docs/0007, same as
 * PeerConnection - unlike PeerConnection though, there's exactly one DhtNode per
 * TorrentEngine (the routing table, and the socket itself, are shared across every
 * torrent, not per-session).
 *
 * <p>Every message received - a query from another node, or a response to one of ours -
 * feeds the routing table with its immediate sender. Nodes merely mentioned inside a
 * find_node/get_peers response aren't inserted here: standard Kademlia hygiene only trusts
 * a node once directly heard from, and treats third-party-reported ones as lookup
 * candidates to contact, not routing-table-worthy on their own.
 *
 * <p>Also answers "get_peers" and "announce_peer" queries from other nodes: a peer store
 * (which peers have announced for which info hash, expired after PEER_EXPIRY) backs
 * get_peers, and a rotating-secret token scheme (BEP 5's own recommended approach) guards
 * announce_peer against an announce that didn't follow a real get_peers lookup first.
 *
 * <p>{@code getPeers}/{@code announcePeer} are this node's own client-side queries (one
 * specific address at a time, same shape as ping/findNode) - the iterative, many-node
 * peer discovery built on top of them lives in PeerLookup, not here.
 */
public final class DhtNode implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DhtNode.class.getName());

    private static final int RECEIVE_BUFFER_SIZE = 2048;
    private static final int TRANSACTION_ID_LENGTH = 2;
    private static final String ID = "id";
    private static final String NODES = "nodes";
    private static final String VALUES = "values";
    private static final String TOKEN = "token";

    private static final long ERROR_BAD_TOKEN = 203;
    private static final Duration PEER_EXPIRY = Duration.ofMinutes(30);
    private static final Duration SECRET_ROTATION_INTERVAL = Duration.ofMinutes(5);

    private final NodeId ourId;
    private final DatagramSocket socket;
    private final RoutingTable routingTable;
    private final SecureRandom random = new SecureRandom();
    private final Map<BString, CompletableFuture<KrpcMessage>> pendingQueries = new ConcurrentHashMap<>();
    private final Map<InfoHash, Map<PeerAddress, Instant>> peerStore = new ConcurrentHashMap<>();
    private final Thread receiveLoopThread;
    private volatile boolean closed;

    // Only ever read/rotated from the single receive-loop thread (issueToken/isValidToken
    // are only reached via handleQuery), so these need no synchronization of their own.
    private byte[] currentSecret = randomSecret();
    private byte[] previousSecret = randomSecret();
    private Instant secretRotatedAt = Instant.now();

    public DhtNode(NodeId ourId, int port) {
        this.ourId = ourId;
        try {
            // Unbound construction + setReuseAddress(true) before bind() - the convenience
            // constructor new DatagramSocket(port) binds immediately with no chance to set
            // this first. Without it, quickly re-binding the same port right after close()
            // (e.g. Quarkus dev mode's live-reload tearing down and recreating the engine on
            // every backend source change) can fail with "Address already in use" for a
            // window after the old socket closes, purely from OS-level lingering, even though
            // nothing else is actually still using the port. See design_docs/0058.
            this.socket = new DatagramSocket(null);
            this.socket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress(port));
        } catch (SocketException e) {
            throw new DhtException("Could not bind DHT socket to port " + port, e);
        }
        this.routingTable = new RoutingTable(ourId);
        this.receiveLoopThread = Thread.ofVirtual().start(this::receiveLoop);
    }

    public NodeId ourId() {
        return ourId;
    }

    public int port() {
        return socket.getLocalPort();
    }

    public RoutingTable routingTable() {
        return routingTable;
    }

    /** Populates our routing table from cold start (BEP 5's well-known bootstrap nodes,
     * then an iterative find_node lookup for our own id - see Bootstrap/NodeLookup). Safe
     * to call with no network access: a bootstrap node that doesn't respond is skipped,
     * and if none of them do, this just leaves the routing table empty rather than
     * throwing. */
    public void bootstrap() {
        Bootstrap.run(this);
    }

    /**
     * Iteratively finds peers for infoHash via BEP 5 get_peers, announcing us as a peer
     * for it (on announcePort, or the query's own source port if announceImpliedPort is
     * true) to the closest nodes queried along the way - see PeerLookup, the sole reason
     * this exists as a thin public wrapper (PeerLookup itself, like the rest of this
     * package's internals, stays package-private).
     */
    public List<PeerAddress> findPeers(
            InfoHash infoHash, int announcePort, boolean announceImpliedPort, Duration perQueryTimeout) {
        return PeerLookup.findPeers(this, infoHash, announcePort, announceImpliedPort, perQueryTimeout);
    }

    /** BEP 5 "ping" - just confirms address is a live DHT node; throws DhtException on
     * timeout or an error reply. */
    public void ping(InetSocketAddress address, Duration timeout) {
        KrpcMessage response = sendAndAwait(address, transactionId -> new Ping(transactionId, ourId), timeout);
        requireNotError(response, address);
    }

    /** BEP 5 "find_node" - asks address for the contact info of the nodes closest to
     * target it knows about. */
    public List<NodeInfo> findNode(InetSocketAddress address, NodeId target, Duration timeout) {
        KrpcMessage response =
                sendAndAwait(address, transactionId -> new FindNode(transactionId, ourId, target), timeout);
        requireNotError(response, address);
        BDictionary returnValues = ((KrpcResponse) response).returnValues();
        if (!(returnValues.get(NODES) instanceof BString nodes)) {
            throw new DhtException(address + "'s find_node response is missing 'nodes'");
        }
        return CompactNodes.decode(nodes);
    }

    /** BEP 5 "get_peers" - asks address for peers it knows about for infoHash, or (if it
     * knows none) the nodes closest to it, plus a token to use in a follow-up
     * announce_peer to this same address. */
    public GetPeersResult getPeers(InetSocketAddress address, InfoHash infoHash, Duration timeout) {
        KrpcMessage response =
                sendAndAwait(address, transactionId -> new GetPeers(transactionId, ourId, infoHash), timeout);
        requireNotError(response, address);
        BDictionary returnValues = ((KrpcResponse) response).returnValues();
        if (!(returnValues.get(TOKEN) instanceof BString token)) {
            throw new DhtException(address + "'s get_peers response is missing 'token'");
        }
        List<PeerAddress> peers =
                returnValues.get(VALUES) instanceof BList values ? CompactPeers.decode(values) : List.of();
        List<NodeInfo> nodes =
                returnValues.get(NODES) instanceof BString compactNodes ? CompactNodes.decode(compactNodes) : List.of();
        return new GetPeersResult(token, peers, nodes);
    }

    /** BEP 5 "announce_peer" - tells address we're a peer for infoHash, reachable on port
     * (or, if impliedPort is true, on whatever port this query is actually sent from).
     * token must be one address itself handed back from an earlier getPeers call to it. */
    public void announcePeer(
            InetSocketAddress address, InfoHash infoHash, int port, boolean impliedPort, BString token,
            Duration timeout) {
        KrpcMessage response = sendAndAwait(address, transactionId ->
                new AnnouncePeer(transactionId, ourId, infoHash, impliedPort, port, token), timeout);
        requireNotError(response, address);
    }

    private void requireNotError(KrpcMessage response, InetSocketAddress address) {
        if (response instanceof KrpcError error) {
            throw new DhtException(address + " returned KRPC error " + error.code() + ": " + error.message());
        }
    }

    private KrpcMessage sendAndAwait(
            InetSocketAddress address, Function<BString, KrpcQuery> queryBuilder, Duration timeout) {
        CompletableFuture<KrpcMessage> future = new CompletableFuture<>();
        BString transactionId = reserveTransactionId(future);
        try {
            send(address, queryBuilder.apply(transactionId));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            throw new DhtException("Could not send to " + address, e);
        } catch (TimeoutException e) {
            throw new DhtException("No response from " + address + " within " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DhtException("Interrupted while waiting for a response from " + address);
        } catch (ExecutionException e) {
            throw new DhtException("Failed while waiting for a response from " + address, e.getCause());
        } finally {
            pendingQueries.remove(transactionId, future);
        }
    }

    private BString reserveTransactionId(CompletableFuture<KrpcMessage> future) {
        byte[] bytes = new byte[TRANSACTION_ID_LENGTH];
        while (true) {
            random.nextBytes(bytes);
            BString candidate = BString.of(bytes);
            if (pendingQueries.putIfAbsent(candidate, future) == null) {
                return candidate;
            }
        }
    }

    private void send(InetSocketAddress address, KrpcMessage message) throws IOException {
        byte[] bytes = KrpcCodec.encode(message);
        socket.send(new DatagramPacket(bytes, bytes.length, address));
    }

    private void trySend(InetSocketAddress address, KrpcMessage message) {
        try {
            send(address, message);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not send DHT response to " + address, e);
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "DHT socket receive failed", e);
                continue;
            }
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            handlePacket(data, (InetSocketAddress) packet.getSocketAddress());
        }
    }

    /** Never lets an exception escape - a single malformed, unsupported, or otherwise
     * unhandleable packet must not kill the receive loop for every other torrent relying
     * on this shared node. */
    private void handlePacket(byte[] data, InetSocketAddress from) {
        try {
            KrpcMessage message = KrpcCodec.decode(data);
            switch (message) {
                case KrpcQuery query -> handleQuery(query, from);
                case KrpcResponse response -> handleResponse(response, from);
                case KrpcError error -> handleError(error, from);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Ignoring unhandleable DHT packet from " + from, e);
        }
    }

    private void handleQuery(KrpcQuery query, InetSocketAddress from) {
        seen(query.id(), from);
        trySend(from, buildResponse(query, from));
    }

    private KrpcMessage buildResponse(KrpcQuery query, InetSocketAddress from) {
        return switch (query) {
            case Ping p -> idOnlyResponse(p.transactionId());
            case FindNode f -> findNodeResponse(f.transactionId(), f.target());
            case GetPeers g -> getPeersResponse(g, from);
            case AnnouncePeer a -> announcePeerResponse(a, from);
        };
    }

    private KrpcResponse idOnlyResponse(BString transactionId) {
        return new KrpcResponse(transactionId, new BDictionary(Map.of(BString.of(ID), idValue())));
    }

    private KrpcResponse findNodeResponse(BString transactionId, NodeId target) {
        List<NodeInfo> closest = routingTable.closestNodes(target, RoutingTable.BUCKET_SIZE);
        Map<BString, BValue> values = new HashMap<>();
        values.put(BString.of(ID), idValue());
        values.put(BString.of(NODES), CompactNodes.encode(closest));
        return new KrpcResponse(transactionId, new BDictionary(values));
    }

    /** Returns whichever peers we know about for infoHash (and a token, for a follow-up
     * announce_peer to prove it came after this lookup) - or, if we know none, the same
     * "nodes" closest-contacts fallback find_node uses, treating the info hash as a point
     * in the same 160-bit id space node ids occupy (standard Kademlia/BEP 5 convention). */
    private KrpcResponse getPeersResponse(GetPeers query, InetSocketAddress from) {
        List<PeerAddress> peers = livePeers(query.infoHash());
        Map<BString, BValue> values = new HashMap<>();
        values.put(BString.of(ID), idValue());
        values.put(BString.of(TOKEN), issueToken(from.getAddress()));
        if (peers.isEmpty()) {
            NodeId infoHashAsTarget = NodeId.of(query.infoHash().bytes());
            List<NodeInfo> closest = routingTable.closestNodes(infoHashAsTarget, RoutingTable.BUCKET_SIZE);
            values.put(BString.of(NODES), CompactNodes.encode(closest));
        } else {
            values.put(BString.of(VALUES), CompactPeers.encode(peers));
        }
        return new KrpcResponse(query.transactionId(), new BDictionary(values));
    }

    /** A bad/missing token (didn't follow a get_peers to us, or one issued more than
     * SECRET_ROTATION_INTERVAL*2 ago) is rejected with BEP 5's own "bad token" error code
     * rather than silently ignored - lets a well-behaved remote client understand why and
     * retry with a fresh get_peers, rather than wondering why announcing never seems to
     * take effect. */
    private KrpcMessage announcePeerResponse(AnnouncePeer query, InetSocketAddress from) {
        if (!isValidToken(from.getAddress(), query.token())) {
            return new KrpcError(query.transactionId(), ERROR_BAD_TOKEN, "Bad token");
        }
        int announcedPort = query.impliedPort() ? from.getPort() : query.port();
        recordAnnounce(query.infoHash(), new PeerAddress(from.getAddress(), announcedPort));
        return idOnlyResponse(query.transactionId());
    }

    private void recordAnnounce(InfoHash infoHash, PeerAddress peer) {
        peerStore.computeIfAbsent(infoHash, key -> new ConcurrentHashMap<>()).put(peer, Instant.now());
    }

    /** Also opportunistically prunes anything past PEER_EXPIRY from the store - no
     * separate sweep thread needed since every info hash with any announces at all gets
     * read via a get_peers query sooner or later. */
    private List<PeerAddress> livePeers(InfoHash infoHash) {
        Map<PeerAddress, Instant> peers = peerStore.get(infoHash);
        if (peers == null) {
            return List.of();
        }
        Instant cutoff = Instant.now().minus(PEER_EXPIRY);
        peers.values().removeIf(announcedAt -> announcedAt.isBefore(cutoff));
        return List.copyOf(peers.keySet());
    }

    private BString issueToken(InetAddress requesterAddress) {
        rotateSecretIfDue();
        return computeToken(currentSecret, requesterAddress);
    }

    /** Accepted against either the current or the previous secret, not just the current
     * one - otherwise a token issued just before a rotation would stop validating moments
     * later, well within the time a well-behaved client needs to look up peers and follow
     * up with an announce. */
    private boolean isValidToken(InetAddress requesterAddress, BString token) {
        rotateSecretIfDue();
        return token.equals(computeToken(currentSecret, requesterAddress))
                || token.equals(computeToken(previousSecret, requesterAddress));
    }

    private void rotateSecretIfDue() {
        if (Duration.between(secretRotatedAt, Instant.now()).compareTo(SECRET_ROTATION_INTERVAL) >= 0) {
            previousSecret = currentSecret;
            currentSecret = randomSecret();
            secretRotatedAt = Instant.now();
        }
    }

    private byte[] randomSecret() {
        byte[] secret = new byte[NodeId.LENGTH_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    /** BEP 5's own recommended token scheme: a hash of a secret (rotated periodically) and
     * the requester's IP - proves a later announce_peer's token came from a get_peers we
     * actually answered for that same address, with no server-side per-token state to
     * store or expire. */
    private static BString computeToken(byte[] secret, InetAddress requesterAddress) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(secret);
            sha1.update(requesterAddress.getAddress());
            return BString.of(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private BValue idValue() {
        return BString.of(ourId.bytes());
    }

    private void handleResponse(KrpcResponse response, InetSocketAddress from) {
        extractId(response.returnValues()).ifPresent(id -> seen(id, from));
        complete(response.transactionId(), response);
    }

    /** BEP 5 error replies carry no sender id, so there's nothing to feed the routing
     * table here - only the transaction match matters. */
    private void handleError(KrpcError error, InetSocketAddress from) {
        complete(error.transactionId(), error);
    }

    private void complete(BString transactionId, KrpcMessage message) {
        CompletableFuture<KrpcMessage> future = pendingQueries.get(transactionId);
        if (future != null) {
            future.complete(message);
        }
        // else: a reply for a transaction we're no longer waiting on (already timed out,
        // a duplicate, or unsolicited) - nothing to do.
    }

    private static Optional<NodeId> extractId(BDictionary returnValues) {
        if (!(returnValues.get(ID) instanceof BString id)) {
            return Optional.empty();
        }
        try {
            return Optional.of(NodeId.of(id.bytes()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Records that we've directly heard from id at from. The full BEP 5 "ping the
     * bucket's oldest contact before evicting it" replacement policy (RoutingTable#insert's
     * returned Optional) isn't acted on yet - a full bucket simply doesn't grow past k for
     * now; see design_docs/0028. */
    private void seen(NodeId id, InetSocketAddress from) {
        routingTable.insert(new NodeInfo(id, from.getAddress(), from.getPort()));
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
        try {
            receiveLoopThread.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (CompletableFuture<KrpcMessage> future : pendingQueries.values()) {
            future.cancel(false);
        }
        pendingQueries.clear();
    }
}
