package com.grimtorrenter.engine.dht;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

/**
 * Populates a freshly-created DhtNode's routing table from cold start, per BEP 5: ping a
 * handful of well-known, long-lived bootstrap nodes (just enough to get each one recorded
 * as a direct contact - see DhtNode's "only trust directly-heard-from nodes" hygiene rule),
 * then run an iterative find_node lookup (NodeLookup) for our own id - the standard way a
 * Kademlia node fills in its whole routing table on startup, since a lookup naturally
 * surfaces nodes across every distance band away from us, not just near the bootstrap
 * nodes themselves.
 *
 * <p>Package-private - DhtNode.bootstrap() is the public entry point production code uses;
 * the extra parameters here exist so tests can point at a fake bootstrap node (and use a
 * much shorter timeout) instead of the real network.
 */
final class Bootstrap {

    /** The mainline DHT's own long-lived, widely-used bootstrap nodes - the same ones most
     * real clients ship with. */
    static final List<String> DEFAULT_HOSTS =
            List.of("router.bittorrent.com", "dht.transmissionbt.com", "router.utorrent.com");
    static final int DEFAULT_PORT = 6881;

    private static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(5);
    private static final System.Logger LOG = System.getLogger(Bootstrap.class.getName());

    private Bootstrap() {
    }

    static void run(DhtNode dhtNode) {
        run(dhtNode, DEFAULT_HOSTS, DEFAULT_PORT, DEFAULT_QUERY_TIMEOUT);
    }

    static void run(DhtNode dhtNode, List<String> hosts, int port, Duration queryTimeout) {
        for (String host : hosts) {
            seedFrom(dhtNode, host, port, queryTimeout);
        }
        NodeLookup.run(dhtNode, dhtNode.ourId(), queryTimeout);
    }

    /** A bootstrap node that doesn't resolve or doesn't respond is logged and skipped, not
     * fatal - if every single one fails (e.g. no network at all), the routing table just
     * stays empty and the following NodeLookup immediately finds nothing to do, rather
     * than this throwing and taking down whatever's calling it. */
    private static void seedFrom(DhtNode dhtNode, String host, int port, Duration queryTimeout) {
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), port);
            dhtNode.ping(address, queryTimeout);
        } catch (DhtException | UnknownHostException e) {
            LOG.log(System.Logger.Level.DEBUG, "Bootstrap node " + host + " did not respond", e);
        }
    }
}
