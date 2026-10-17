package com.grimtorrenter.engine.utp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** design_docs/0074's slice 5 own Testing section - the one integration risk a pure
     * LedbatCongestionControlTest unit test can't catch on its own: a transfer larger than the
     * old fixed 64KB window cap must still complete correctly, proving the ack-driven growth
     * loop (acquireWindowSlot() re-checking a shrinking/growing dynamic cap) doesn't stall or
     * deadlock across many congestion-window updates, not just a handful. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void largeTransferCompletesCorrectlyUnderLedbatGrowth() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(initiatorSocket, acceptorSocket);
        UtpSocket sender = pair.initiator();
        UtpSocket receiver = pair.acceptor();
        try {
            byte[] data = new byte[200 * 1024]; // well past the old fixed 64KB cap
            new Random(99).nextBytes(data);

            sender.send(data);
            byte[] received = readExactly(receiver, data.length);

            assertArrayEquals(data, received);
        } finally {
            sender.close();
            receiver.close();
        }
    }

    /** A reader that stops reading (PeerConnection's read loop blocked on the download rate
     * limiter) must hold back the sender, not let unread data pile up in memory - and once it
     * reads again, the transfer must resume and complete intact. See design_docs/0092. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aReaderThatStopsReadingBoundsWhatIsBufferedAndTheTransferStillCompletes() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(initiatorSocket, acceptorSocket);
        UtpSocket sender = pair.initiator();
        UtpSocket receiver = pair.acceptor();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            byte[] data = new byte[4 * UtpSocket.RECEIVE_BUFFER_LIMIT_BYTES];
            new Random(1234).nextBytes(data);
            Future<?> sending = executor.submit(() -> sender.send(data));

            // Long enough for the sender to fill the buffer and then sit on a zero window.
            Thread.sleep(3000);
            assertTrue(receiver.queuedBytes() <= UtpSocket.RECEIVE_BUFFER_LIMIT_BYTES,
                    "buffered " + receiver.queuedBytes());
            assertTrue(receiver.queuedBytes() > 0, "nothing arrived at all");

            byte[] received = readExactly(receiver, data.length);
            sending.get(30, TimeUnit.SECONDS);
            assertArrayEquals(data, received);
        } finally {
            executor.shutdownNow();
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

    /** design_docs/0074's slice 4 - proves the Duration-based overload actually honors a short,
     * dedicated budget rather than falling back to HANDSHAKE_MAX_RETRIES's own several-second
     * one; the @Timeout below is the actual assertion that it does. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void connectWithADurationTimeoutGivesUpQuicklyWhenNothingAnswers() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket unusedSocket = new DatagramSocket();
        int unreachablePort = unusedSocket.getLocalPort();
        unusedSocket.close(); // nothing is listening on this port for the rest of the test
        try {
            assertThrows(UtpException.class,
                    () -> UtpSocket.connect(initiatorSocket, loopback(unreachablePort), Duration.ofSeconds(1)));
        } finally {
            initiatorSocket.close();
        }
    }
}
