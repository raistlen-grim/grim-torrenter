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
     * real clients ship with (matches libtorrent's own default router list, minus
     * router.bitcomet.com - confirmed no longer resolving at all, dropped rather than kept
     * as permanently-dead weight). Five, not the original three - a live investigation
     * (2026-08-30, see design_docs/0028's own addendum) found one real network where
     * router.bittorrent.com, router.utorrent.com, and dht.aelitis.com are all consistently
     * unreachable (deterministically, not flaky - the same hosts failed across multiple
     * separate runs and a direct manual ping test), leaving only dht.transmissionbt.com
     * actually useful there. dht.libtorrent.org (a different port, 25401 - real bootstrap
     * nodes don't agree on one) was confirmed reachable from that same network and is a real,
     * actively-used libtorrent/qBittorrent bootstrap host, not a speculative addition. Every
     * host here is kept even where it's currently unreachable from one tested network (rather
     * than pruned down to "only what worked once") - BEP 5 already treats an unreachable
     * bootstrap host as expected/tolerated, not fatal, so an extra independent host only ever
     * helps on whichever network it does work from, never hurts. */
    static final List<BootstrapHost> DEFAULT_HOSTS = List.of(
            new BootstrapHost("router.bittorrent.com", 6881),
            new BootstrapHost("dht.transmissionbt.com", 6881),
            new BootstrapHost("router.utorrent.com", 6881),
            new BootstrapHost("dht.libtorrent.org", 25401),
            new BootstrapHost("dht.aelitis.com", 6881));

    private static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(5);
    private static final System.Logger LOG = System.getLogger(Bootstrap.class.getName());

    private Bootstrap() {
    }

    static void run(DhtNode dhtNode) {
        run(dhtNode, DEFAULT_HOSTS, DEFAULT_QUERY_TIMEOUT);
    }

    static void run(DhtNode dhtNode, List<BootstrapHost> hosts, Duration queryTimeout) {
        for (BootstrapHost host : hosts) {
            seedFrom(dhtNode, host, queryTimeout);
        }
        NodeLookup.run(dhtNode, dhtNode.ourId(), queryTimeout);
    }

    /** A bootstrap node that doesn't resolve or doesn't respond is logged and skipped, not
     * fatal - if every single one fails (e.g. no network at all), the routing table just
     * stays empty and the following NodeLookup immediately finds nothing to do, rather
     * than this throwing and taking down whatever's calling it. */
    private static void seedFrom(DhtNode dhtNode, BootstrapHost host, Duration queryTimeout) {
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host.host()), host.port());
            dhtNode.ping(address, queryTimeout);
        } catch (DhtException | UnknownHostException e) {
            LOG.log(System.Logger.Level.DEBUG, "Bootstrap node " + host.host() + " did not respond", e);
        }
    }
}
