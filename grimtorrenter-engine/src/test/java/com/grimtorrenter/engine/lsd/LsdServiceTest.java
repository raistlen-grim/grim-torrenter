package com.grimtorrenter.engine.lsd;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A genuine loopback-multicast integration test, same spirit/acceptance as DhtNodeTest's and
 * PeerServerTest's own real-socket tests (design_docs/0007's blocking-I/O-is-fine-for-this
 * style) - both LsdService instances here bind the real BEP 14 port (6771, fixed by spec, see
 * LsdCodec.MULTICAST_PORT) via SO_REUSEADDR and join the group on the host's loopback interface
 * only, via the package-private constructor.
 */
class LsdServiceTest {

    private LsdService a;
    private LsdService b;

    @AfterEach
    void tearDown() {
        if (a != null) {
            a.close();
        }
        if (b != null) {
            b.close();
        }
    }

    private static List<NetworkInterface> loopbackInterface() throws Exception {
        NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
        return List.of(loopback);
    }

    private static InfoHash infoHashOf(int seed) {
        byte[] bytes = new byte[20];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return InfoHash.of(bytes);
    }

    @Test
    void announcingFromOneInstanceIsReceivedByAnother() throws Exception {
        InfoHash infoHash = infoHashOf(1);
        CountDownLatch found = new CountDownLatch(1);
        AtomicReference<InfoHash> foundInfoHash = new AtomicReference<>();
        AtomicReference<PeerAddress> foundAddress = new AtomicReference<>();

        a = new LsdService(6881, (h, addr) -> {
        }, loopbackInterface());
        b = new LsdService(6882, (h, addr) -> {
            foundInfoHash.set(h);
            foundAddress.set(addr);
            found.countDown();
        }, loopbackInterface());

        a.announce(List.of(infoHash));

        assertTrue(found.await(2, TimeUnit.SECONDS));
        assertEquals(infoHash, foundInfoHash.get());
        assertEquals(6881, foundAddress.get().port());
    }

    @Test
    void anInstanceNeverReportsItsOwnAnnouncementBackToItself() throws Exception {
        CountDownLatch selfReported = new CountDownLatch(1);

        a = new LsdService(6881, (h, addr) -> selfReported.countDown(), loopbackInterface());

        a.announce(List.of(infoHashOf(2)));

        // Loopback delivery to the sender's own socket is expected at the transport level -
        // the cookie check is what must suppress it before onPeerFound ever fires.
        assertFalse(selfReported.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void aMalformedMulticastPacketIsDroppedWithoutKillingTheReceiveLoop() throws Exception {
        InfoHash infoHash = infoHashOf(3);
        CountDownLatch found = new CountDownLatch(1);

        b = new LsdService(6882, (h, addr) -> found.countDown(), loopbackInterface());

        try (DatagramSocket garbageSender = new DatagramSocket()) {
            byte[] garbage = "not a BT-SEARCH announcement at all".getBytes(StandardCharsets.US_ASCII);
            DatagramPacket packet = new DatagramPacket(garbage, garbage.length,
                    InetAddress.getByName(LsdCodec.MULTICAST_GROUP), LsdCodec.MULTICAST_PORT);
            garbageSender.send(packet);
        }

        a = new LsdService(6881, (h, addr) -> {
        }, loopbackInterface());
        a.announce(List.of(infoHash));

        assertTrue(found.await(2, TimeUnit.SECONDS));
    }
}
