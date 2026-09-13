package com.grimtorrenter.engine.utp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Real UtpSocket pairs over real loopback DatagramSockets - design_docs/0074's slice 1 own
 * Testing section. Each test owns its sockets/connections and closes them itself; there's no
 * shared fixture since every test binds its own ephemeral ports. */
class UtpSocketTest {

    private record ConnectedPair(UtpSocket initiator, UtpSocket acceptor) {
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static ConnectedPair connectPair(DatagramSocket initiatorSocket, DatagramSocket acceptorSocket)
            throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<UtpSocket> acceptFuture = executor.submit(() -> UtpSocket.accept(acceptorSocket));
            UtpSocket initiator = UtpSocket.connect(initiatorSocket, loopback(acceptorSocket.getLocalPort()));
            UtpSocket acceptor = acceptFuture.get(10, TimeUnit.SECONDS);
            return new ConnectedPair(initiator, acceptor);
        } finally {
            executor.shutdown();
        }
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
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void connectionIdsAreAsymmetricInBothDirections() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(initiatorSocket, acceptorSocket);
        try {
            // design_docs/0074's own asymmetric-IDs note: the initiator sends with recvId and
            // expects replies addressed recvId + 1 - i.e. each side's own "send" ID must equal
            // the other side's own "receive" ID, and neither side's two IDs are ever equal to
            // each other.
            assertEquals(pair.initiator().sendConnectionId(), pair.acceptor().receiveConnectionId());
            assertEquals(pair.acceptor().sendConnectionId(), pair.initiator().receiveConnectionId());
            assertNotEquals(pair.initiator().sendConnectionId(), pair.initiator().receiveConnectionId());
            assertNotEquals(pair.acceptor().sendConnectionId(), pair.acceptor().receiveConnectionId());
        } finally {
            pair.initiator().close();
            pair.acceptor().close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void cleanTransferRoundTripsMultipleChunksCorrectly() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(initiatorSocket, acceptorSocket);
        UtpSocket sender = pair.initiator();
        UtpSocket receiver = pair.acceptor();
        try {
            byte[] data = new byte[5600]; // 4 full 1400-byte packets, no partial remainder
            new Random(42).nextBytes(data);

            sender.send(data);
            byte[] received = readExactly(receiver, data.length);

            assertArrayEquals(data, received);
        } finally {
            sender.close();
            receiver.close();
        }
    }

    /** design_docs/0074's own "no selective ack yet" note: dropping the second of several
     * packets means every packet sent after it also arrives "out of order" and gets silently
     * dropped by the receiver in turn, so recovery here means the sender's own per-packet RTO
     * eventually retransmits all of them, not just the one actually lost on the wire - slower
     * than SACK-based recovery would be, but still correct. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aSingleDroppedPacketIsRecoveredViaRtoRetransmission() throws Exception {
        AtomicInteger dataPacketsSeen = new AtomicInteger();
        LossyDatagramSocket senderSocket = new LossyDatagramSocket(wireBytes -> {
            UtpPacket decoded = UtpPacketCodec.decode(wireBytes);
            return decoded.type() == UtpPacketType.DATA && dataPacketsSeen.getAndIncrement() == 1;
        });
        DatagramSocket receiverSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(senderSocket, receiverSocket);
        UtpSocket sender = pair.initiator();
        UtpSocket receiver = pair.acceptor();
        try {
            byte[] data = new byte[5600]; // 4 packets - the 2nd (index 1) is dropped on the wire
            new Random(7).nextBytes(data);

            sender.send(data);
            byte[] received = readExactly(receiver, data.length);

            assertArrayEquals(data, received);
        } finally {
            sender.close();
            receiver.close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void sequenceNumbersCompareCorrectlyAcrossTheWraparoundBoundary() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        UtpSocket sender;
        UtpSocket receiver;
        try {
            Future<UtpSocket> acceptFuture = executor.submit(() -> UtpSocket.accept(acceptorSocket));
            // 0xFFFE is consumed by the SYN itself; the first real DATA packet uses 0xFFFF, the
            // second wraps to 0x0000, the third to 0x0001 - exercises the wrap on both the
            // sender's own seqNr assignment and the receiver's ackNr comparison/advancement.
            sender = UtpSocket.connect(initiatorSocket, loopback(acceptorSocket.getLocalPort()), 0xFFFE);
            receiver = acceptFuture.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
        try {
            byte[] data = new byte[1400 * 3];
            new Random(11).nextBytes(data);

            sender.send(data);
            byte[] received = readExactly(receiver, data.length);

            assertArrayEquals(data, received);
        } finally {
            sender.close();
            receiver.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void closeSendsAFinThatUnblocksThePeersPendingReceive() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(initiatorSocket, acceptorSocket);
        UtpSocket a = pair.initiator();
        UtpSocket b = pair.acceptor();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> pendingReceive = executor.submit(b::receive);

            a.close();

            assertNull(pendingReceive.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            b.close();
        }
    }
}
