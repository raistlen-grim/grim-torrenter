package com.grimtorrenter.engine.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0079. Runs the real SOCKS5 client against a real (fake) SOCKS5 proxy on loopback. */
class Socks5Test {

    private FakeSocks5Proxy proxy;
    private ServerSocket echoServer;
    private DatagramSocket udpEcho;

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) {
            proxy.close();
        }
        if (echoServer != null) {
            echoServer.close();
        }
        if (udpEcho != null) {
            udpEcho.close();
        }
    }

    /** A TCP server that echoes every byte back. */
    private InetSocketAddress startEchoServer() throws IOException {
        echoServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!echoServer.isClosed()) {
                try {
                    Socket socket = echoServer.accept();
                    Thread.ofVirtual().start(() -> {
                        try (Socket s = socket) {
                            s.getInputStream().transferTo(s.getOutputStream());
                        } catch (IOException ignored) {
                            // client went away
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), echoServer.getLocalPort());
    }

    /** A UDP server that echoes every datagram back to its sender. */
    private InetSocketAddress startUdpEcho() throws IOException {
        udpEcho = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[2048];
            while (!udpEcho.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpEcho.receive(packet);
                    udpEcho.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
                } catch (IOException e) {
                    return;
                }
            }
        });
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), udpEcho.getLocalPort());
    }

    private static void assertEchoes(Socket socket, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(bytes);
        out.flush();
        socket.setSoTimeout(3000);
        assertArrayEquals(bytes, socket.getInputStream().readNBytes(bytes.length));
    }

    @Test
    void connectsThroughAProxyWithNoAuthentication() throws Exception {
        proxy = new FakeSocks5Proxy();
        InetSocketAddress echo = startEchoServer();

        try (Socket socket = Socks5.connect(proxy.settings(), echo, 3000)) {
            assertEchoes(socket, "hello through the proxy");
        }

        assertEquals("127.0.0.1:" + echo.getPort(), proxy.connectTargets.get(0));
    }

    /** The whole point of doing SOCKS5 by hand: an unresolved destination reaches the proxy as a
     * name, so the proxy - not this machine - does the DNS lookup. */
    @Test
    void anUnresolvedDestinationIsSentToTheProxyAsANameNotResolvedLocally() throws Exception {
        proxy = new FakeSocks5Proxy();
        InetSocketAddress echo = startEchoServer();
        proxy.map("echo.invalid", echo);

        try (Socket socket = Socks5.connect(proxy.settings(), "echo.invalid", 9999, 3000)) {
            assertEchoes(socket, "resolved by the proxy");
        }
        try (Socket socket = Socks5.connect(proxy.settings(),
                InetSocketAddress.createUnresolved("echo.invalid", 9999), 3000)) {
            assertEchoes(socket, "still by name");
        }

        assertEquals("echo.invalid:9999", proxy.connectTargets.get(0));
        assertEquals("echo.invalid:9999", proxy.connectTargets.get(1));
    }

    @Test
    void authenticatesWithAUsernameAndPassword() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.requiredUsername = "alice";
        proxy.requiredPassword = "s3cret";
        InetSocketAddress echo = startEchoServer();

        try (Socket socket = Socks5.connect(proxy.settings("alice", "s3cret"), echo, 3000)) {
            assertEchoes(socket, "authenticated");
        }
    }

    @Test
    void aWrongPasswordIsRejectedWithAClearMessage() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.requiredUsername = "alice";
        proxy.requiredPassword = "s3cret";
        InetSocketAddress echo = startEchoServer();

        IOException e = assertThrows(IOException.class,
                () -> Socks5.connect(proxy.settings("alice", "wrong"), echo, 3000));

        assertTrue(e.getMessage().contains("rejected the username or password"), e.getMessage());
    }

    @Test
    void aProxyThatRequiresAuthWhenNoneIsConfiguredSaysSo() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.requiredUsername = "alice";
        proxy.requiredPassword = "s3cret";
        InetSocketAddress echo = startEchoServer();

        IOException e = assertThrows(IOException.class, () -> Socks5.connect(proxy.settings(), echo, 3000));

        assertTrue(e.getMessage().contains("rejected every authentication method"), e.getMessage());
    }

    @Test
    void aRefusedConnectIsAnIoExceptionNamingTheReason() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.connectReplyCode = 5;

        IOException e = assertThrows(IOException.class,
                () -> Socks5.connect(proxy.settings(), "anywhere.invalid", 80, 3000));

        assertTrue(e.getMessage().contains("connection refused"), e.getMessage());
    }

    @Test
    void anUnreachableProxyIsReportedAsSuch() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        IOException e = assertThrows(IOException.class, () -> Socks5.connect(
                new ProxySettings("127.0.0.1", closedPort, null, null), "x.invalid", 80, 2000));

        assertTrue(e.getMessage().contains("Could not reach the proxy"), e.getMessage());
    }

    @Test
    void aServerThatIsNotASocks5ProxyIsRecognisedAsNot() throws Exception {
        try (ServerSocket notAProxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket s = notAProxy.accept()) {
                    s.getInputStream().read();
                    s.getOutputStream().write(new byte[] {4, 0});
                } catch (IOException ignored) {
                    // test over
                }
            });

            IOException e = assertThrows(IOException.class, () -> Socks5.connect(
                    new ProxySettings("127.0.0.1", notAProxy.getLocalPort(), null, null), "x.invalid", 80, 2000));

            assertTrue(e.getMessage().contains("isn't a SOCKS5 proxy"), e.getMessage());
        }
    }

    @Test
    void theProviderOverloadConnectsDirectlyWithNoProxyAndThroughItWithOne() throws Exception {
        proxy = new FakeSocks5Proxy();
        InetSocketAddress echo = startEchoServer();

        try (Socket direct = Socks5.connect(ProxyProvider.NONE, echo, 3000)) {
            assertEchoes(direct, "direct");
        }
        assertEquals(0, proxy.connectionsAccepted.get(), "no proxy configured - the proxy must not be used");

        ProxyProvider provider = () -> Optional.of(proxy.settings());
        try (Socket proxied = Socks5.connect(provider, echo, 3000)) {
            assertEchoes(proxied, "proxied");
        }
        assertEquals(1, proxy.connectTargets.size());
    }

    /** A proxy that fails is a failed connection - the provider overload must not quietly go direct. */
    @Test
    void aFailingProxyNeverFallsBackToADirectConnection() throws Exception {
        InetSocketAddress echo = startEchoServer();
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        ProxyProvider dead = () -> Optional.of(new ProxySettings("127.0.0.1", closedPort, null, null));

        assertThrows(IOException.class, () -> Socks5.connect(dead, echo, 2000));
    }

    @Test
    void udpRelayCarriesADatagramToADestinationAndBack() throws Exception {
        proxy = new FakeSocks5Proxy();
        InetSocketAddress udpServer = startUdpEcho();
        proxy.map("udp.invalid", udpServer);

        try (Socks5.UdpRelay relay = Socks5.openUdpRelay(proxy.settings(), 3000)) {
            relay.send("udp.invalid", 1234, "ping".getBytes(StandardCharsets.UTF_8));

            assertEquals("ping", new String(relay.receive(3000), StandardCharsets.UTF_8));
        }

        assertEquals("udp.invalid:1234", proxy.udpTargets.get(0));
    }

    @Test
    void udpRelayReceiveTimesOutWhenNothingComesBack() throws Exception {
        proxy = new FakeSocks5Proxy();

        try (Socks5.UdpRelay relay = Socks5.openUdpRelay(proxy.settings(), 3000)) {
            assertThrows(SocketTimeoutException.class, () -> relay.receive(200));
        }
    }

    @Test
    void aProxyThatDoesNotRelayUdpIsReportedAsSuch() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.rejectUdp = true;

        IOException e = assertThrows(IOException.class, () -> Socks5.openUdpRelay(proxy.settings(), 3000));

        assertTrue(e.getMessage().contains("command not supported"), e.getMessage());
    }

    @Test
    void testReportsAReachableProxyThatRelaysUdp() throws Exception {
        proxy = new FakeSocks5Proxy();

        Socks5.TestResult result = Socks5.test(proxy.settings(), 3000);

        assertTrue(result.reachable());
        assertTrue(result.udpSupported());
    }

    @Test
    void testReportsAReachableProxyWithoutUdpDistinctlyFromAnUnreachableOne() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.rejectUdp = true;

        Socks5.TestResult noUdp = Socks5.test(proxy.settings(), 3000);
        assertTrue(noUdp.reachable());
        assertFalse(noUdp.udpSupported());

        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        Socks5.TestResult unreachable = Socks5.test(new ProxySettings("127.0.0.1", closedPort, null, null), 2000);
        assertFalse(unreachable.reachable());
        assertTrue(unreachable.message().contains("Could not reach the proxy"), unreachable.message());
    }

    @Test
    void testReportsAWrongPasswordAsNotReachable() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.requiredUsername = "alice";
        proxy.requiredPassword = "s3cret";

        Socks5.TestResult result = Socks5.test(proxy.settings("alice", "nope"), 3000);

        assertFalse(result.reachable());
        assertTrue(result.message().contains("rejected the username or password"), result.message());
    }

    @Test
    void theSettingsRecordNeverPrintsThePassword() {
        String text = new ProxySettings("proxy.example", 1080, "alice", "s3cret").toString();

        assertFalse(text.contains("s3cret"));
        assertTrue(text.contains("proxy.example:1080"));
    }
}
