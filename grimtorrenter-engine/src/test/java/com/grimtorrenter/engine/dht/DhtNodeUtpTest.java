package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.utp.UtpSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** design_docs/0074's slice 3 own end-to-end proof: a real UtpSocket connecting against DhtNode's
 * own shared socket/port, while ordinary KRPC traffic (ping()) also flows through the very same
 * node - not just that the demux logic compiles, but that the two protocols genuinely coexist on
 * one socket the way the design doc claims. */
class DhtNodeUtpTest {

    private static final NodeId NODE_ID = NodeId.of(new byte[20]);
    private static final NodeId PINGER_ID = idWithLastByte(1);

    private static NodeId idWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return NodeId.of(bytes);
    }

    private DhtNode node;
    private DhtNode pinger;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.close();
        }
        if (pinger != null) {
            pinger.close();
        }
    }

    private static InetSocketAddress addressOf(DhtNode dhtNode) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), dhtNode.port());
    }

    private static byte[] readExactly(UtpSocket socket, int totalLength) {
        byte[] result = new byte[totalLength];
        int offset = 0;
        while (offset < totalLength) {
            byte[] chunk = socket.receive();
            assertNotNull(chunk, "connection closed before all data arrived");
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aRealUtpConnectionAndOrdinaryKrpcTrafficCoexistOnTheSameSharedSocket() throws Exception {
        BlockingQueue<UtpSocket> accepted = new ArrayBlockingQueue<>(1);
        node = new DhtNode(NODE_ID, 0, accepted::add);
        pinger = new DhtNode(PINGER_ID, 0);

        // Ordinary DHT traffic against the very same socket the µTP connection below also uses.
        pinger.ping(addressOf(node), Duration.ofSeconds(2));

        DatagramSocket initiatorSocket = new DatagramSocket();
        try {
            UtpSocket initiator = UtpSocket.connect(initiatorSocket, addressOf(node));
            UtpSocket acceptedSocket = accepted.poll(10, TimeUnit.SECONDS);
            assertNotNull(acceptedSocket, "DhtNode never handed off an accepted µTP connection");

            byte[] data = new byte[4000];
            new Random(3).nextBytes(data);
            initiator.send(data);
            assertArrayEquals(data, readExactly(acceptedSocket, data.length));

            // KRPC still works after a µTP connection has been demuxed onto the same socket.
            pinger.ping(addressOf(node), Duration.ofSeconds(2));

            initiator.close();
            acceptedSocket.close();
        } finally {
            initiatorSocket.close();
        }
    }
}
