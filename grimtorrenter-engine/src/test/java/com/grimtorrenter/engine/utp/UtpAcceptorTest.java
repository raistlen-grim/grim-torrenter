package com.grimtorrenter.engine.utp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real loopback DatagramSockets - design_docs/0074's slice 3 own Testing section. One socket
 * plays the role DhtNode plays in production: its own receive loop hands every datagram straight
 * to a UtpAcceptor, which is the only thing that ever touches that socket's connection state. */
class UtpAcceptorTest {

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    /** Stands in for DhtNode.receiveLoop() - the one thread allowed to call sharedSocket.receive(). */
    private static Thread startSharedSocketReadLoop(DatagramSocket sharedSocket, UtpAcceptor acceptor) {
        Thread thread = Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[4096];
            while (!sharedSocket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    sharedSocket.receive(packet);
                } catch (Exception e) {
                    return;
                }
                byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                acceptor.handlePacket(data, (InetSocketAddress) packet.getSocketAddress());
            }
        });
        return thread;
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
    void acceptedConnectionExchangesDataBothWaysOverTheSharedSocket() throws Exception {
        DatagramSocket sharedSocket = new DatagramSocket();
        DatagramSocket initiatorSocket = new DatagramSocket();
        BlockingQueue<UtpSocket> accepted = new ArrayBlockingQueue<>(1);
        UtpAcceptor acceptor = new UtpAcceptor(sharedSocket, accepted::add);
        Thread readLoop = startSharedSocketReadLoop(sharedSocket, acceptor);
        try {
            UtpSocket initiator = UtpSocket.connect(initiatorSocket, loopback(sharedSocket.getLocalPort()));
            UtpSocket acceptedSocket = accepted.poll(10, TimeUnit.SECONDS);
            assertNotNull(acceptedSocket, "UtpAcceptor never handed off an accepted connection");

            byte[] toAcceptor = new byte[3000];
            new Random(1).nextBytes(toAcceptor);
            initiator.send(toAcceptor);
            assertArrayEquals(toAcceptor, readExactly(acceptedSocket, toAcceptor.length));

            byte[] toInitiator = new byte[2000];
            new Random(2).nextBytes(toInitiator);
            acceptedSocket.send(toInitiator);
            assertArrayEquals(toInitiator, readExactly(initiator, toInitiator.length));

            initiator.close();
            acceptedSocket.close();
        } finally {
            sharedSocket.close();
            initiatorSocket.close();
            readLoop.interrupt();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void closingAnAcceptedConnectionRemovesItFromTheRegistry() throws Exception {
        DatagramSocket sharedSocket = new DatagramSocket();
        DatagramSocket initiatorSocket = new DatagramSocket();
        BlockingQueue<UtpSocket> accepted = new ArrayBlockingQueue<>(1);
        UtpAcceptor acceptor = new UtpAcceptor(sharedSocket, accepted::add);
        Thread readLoop = startSharedSocketReadLoop(sharedSocket, acceptor);
        try {
            UtpSocket initiator = UtpSocket.connect(initiatorSocket, loopback(sharedSocket.getLocalPort()));
            UtpSocket acceptedSocket = accepted.poll(10, TimeUnit.SECONDS);
            assertNotNull(acceptedSocket);
            assertEquals(1, acceptor.connectionCount());

            acceptedSocket.close();
            initiator.close();

            // acceptedSocket.close() runs its own onClosed callback synchronously, so the
            // registry entry is already gone by the time close() returns - no polling needed.
            assertEquals(0, acceptor.connectionCount());
        } finally {
            sharedSocket.close();
            initiatorSocket.close();
            readLoop.interrupt();
        }
    }
}
