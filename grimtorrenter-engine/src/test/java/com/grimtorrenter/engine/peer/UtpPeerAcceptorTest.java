package com.grimtorrenter.engine.peer;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.PeerWireCodec;
import com.grimtorrenter.engine.tracker.PeerId;
import com.grimtorrenter.engine.utp.UtpSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Real UtpSocket pair over real loopback DatagramSockets (same shape UtpSocketTest/
 * PeerConnectionUtpTransportTest already use) - design_docs/0074's slice 3 own Testing section
 * for UtpPeerAcceptor specifically: does it read the BT handshake, route by info hash, and
 * reject an unknown one, the same way PeerServer.handleConnection() already does for TCP. */
class UtpPeerAcceptorTest {

    private static byte[] fill(int length, int start) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }

    private static InfoHash knownInfoHash() {
        return InfoHash.of(fill(20, 0));
    }

    private static InfoHash unknownInfoHash() {
        return InfoHash.of(fill(20, 200));
    }

    private static PeerId somePeerId(int start) {
        return PeerId.of(fill(20, start));
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private record ConnectedPair(UtpSocket initiator, UtpSocket acceptor) {
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

    private static void writeHandshake(UtpSocket socket, InfoHash infoHash) throws IOException {
        PeerWireCodec.writeHandshake(socket.getOutputStream(), Handshake.withExtensionProtocol(infoHash,
                somePeerId(100)));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void routesAnAcceptedConnectionToTheHandlerForItsInfoHash() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(initiatorSocket, acceptorSocket);
        try {
            writeHandshake(pair.initiator(), knownInfoHash());

            BlockingQueue<Handshake> received = new ArrayBlockingQueue<>(1);
            UtpIncomingConnectionHandler handler = (utpSocket, handshake) -> received.add(handshake);
            UtpPeerAcceptor acceptor = new UtpPeerAcceptor(infoHash ->
                    infoHash.equals(knownInfoHash()) ? Optional.of(handler) : Optional.empty());

            Thread.ofVirtual().start(() -> acceptor.accept(pair.acceptor()));

            Handshake handshake = received.poll(10, TimeUnit.SECONDS);
            assertEquals(knownInfoHash(), handshake.infoHash());
        } finally {
            pair.initiator().close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void closesTheConnectionWhenNothingRecognizesItsInfoHash() throws Exception {
        DatagramSocket initiatorSocket = new DatagramSocket();
        DatagramSocket acceptorSocket = new DatagramSocket();
        ConnectedPair pair = connectPair(initiatorSocket, acceptorSocket);
        try {
            writeHandshake(pair.initiator(), unknownInfoHash());

            UtpPeerAcceptor acceptor = new UtpPeerAcceptor(infoHash -> Optional.empty());
            Thread.ofVirtual().start(() -> acceptor.accept(pair.acceptor()));

            // The acceptor closes utpSocket once it finds no handler - the initiator's own
            // pending receive() unblocks with null (EOF) once that FIN arrives, exactly the
            // same close-visible-as-EOF contract UtpSocketTest's own closeSendsAFin... test
            // already establishes for an ordinary close().
            assertNull(pair.initiator().receive());
        } finally {
            pair.initiator().close();
        }
    }
}
