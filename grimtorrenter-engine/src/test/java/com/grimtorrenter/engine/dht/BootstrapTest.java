package com.grimtorrenter.engine.dht;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

    private DhtNode bootstrapNode;
    private DhtNode joiningNode;

    @AfterEach
    void tearDown() {
        if (bootstrapNode != null) {
            bootstrapNode.close();
        }
        if (joiningNode != null) {
            joiningNode.close();
        }
    }

    private static NodeId idWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return NodeId.of(bytes);
    }

    @Test
    void bootstrapPopulatesRoutingTableFromAFakeBootstrapNode() {
        bootstrapNode = new DhtNode(idWithLastByte(1), 0);
        // Gives the joining node's subsequent iterative lookup something to discover
        // beyond the bootstrap node itself.
        bootstrapNode.routingTable()
                .insert(new NodeInfo(idWithLastByte(2), InetAddress.getLoopbackAddress(), 9999));

        joiningNode = new DhtNode(idWithLastByte(99), 0);

        Bootstrap.run(joiningNode, List.of(new BootstrapHost("localhost", bootstrapNode.port())), TIMEOUT);

        assertTrue(joiningNode.routingTable().size() >= 1);
        assertTrue(joiningNode.routingTable().closestNodes(idWithLastByte(1), 1)
                .contains(new NodeInfo(idWithLastByte(1), InetAddress.getLoopbackAddress(), bootstrapNode.port())));
    }

    @Test
    void bootstrapWithNoReachableHostLeavesRoutingTableEmptyWithoutThrowing() {
        joiningNode = new DhtNode(idWithLastByte(1), 0);

        // Nothing bound on this port on loopback.
        Bootstrap.run(joiningNode, List.of(new BootstrapHost("localhost", 1)), SHORT_TIMEOUT);

        assertEquals(0, joiningNode.routingTable().size());
    }

    /** design_docs/0028's own 2026-08-30 addendum (the follow-up fix section) - five hosts,
     * not the original three, and dht.libtorrent.org genuinely uses a different port (25401)
     * than the other four's shared 6881, confirmed live rather than assumed. */
    @Test
    void defaultHostsIncludesFiveRedundantBootstrapHostsWithTheirOwnPorts() {
        assertEquals(5, Bootstrap.DEFAULT_HOSTS.size());
        assertTrue(Bootstrap.DEFAULT_HOSTS.contains(new BootstrapHost("router.bittorrent.com", 6881)));
        assertTrue(Bootstrap.DEFAULT_HOSTS.contains(new BootstrapHost("dht.transmissionbt.com", 6881)));
        assertTrue(Bootstrap.DEFAULT_HOSTS.contains(new BootstrapHost("router.utorrent.com", 6881)));
        assertTrue(Bootstrap.DEFAULT_HOSTS.contains(new BootstrapHost("dht.libtorrent.org", 25401)));
        assertTrue(Bootstrap.DEFAULT_HOSTS.contains(new BootstrapHost("dht.aelitis.com", 6881)));
    }
}
