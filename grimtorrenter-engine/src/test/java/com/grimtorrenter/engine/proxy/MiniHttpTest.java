package com.grimtorrenter.engine.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0079. Real HTTP servers behind the fake SOCKS5 proxy. */
class MiniHttpTest {

    private FakeSocks5Proxy proxy;
    private HttpServer http;
    private ServerSocket raw;

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) {
            proxy.close();
        }
        if (http != null) {
            http.stop(0);
        }
        if (raw != null) {
            raw.close();
        }
    }

    private InetSocketAddress startHttp(String path, int status, byte[] body, AtomicReference<String> hostHeader)
            throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(path, exchange -> {
            if (hostHeader != null) {
                hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        http.start();
        return new InetSocketAddress("127.0.0.1", http.getAddress().getPort());
    }

    private static String get(URI uri, ProxySettings proxy, long max) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int status = MiniHttp.get(uri, proxy, max, sink, 5, 20);
        assertEquals(200, status);
        return sink.toString(StandardCharsets.UTF_8);
    }

    @Test
    void getsABodyThroughTheProxyAndTheProxyResolvedTheHostName() throws Exception {
        proxy = new FakeSocks5Proxy();
        AtomicReference<String> hostHeader = new AtomicReference<>();
        proxy.map("web.invalid", startHttp("/hello", 200, "hi there".getBytes(StandardCharsets.UTF_8), hostHeader));

        String body = get(URI.create("http://web.invalid:8123/hello?x=1"), proxy.settings(), 1024);

        assertEquals("hi there", body);
        assertEquals("web.invalid:8123", proxy.connectTargets.get(0));
        assertEquals("web.invalid:8123", hostHeader.get());
    }

    @Test
    void aNon200StatusIsReturnedNotThrown() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.map("web.invalid", startHttp("/", 404, new byte[0], null));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        int status = MiniHttp.get(URI.create("http://web.invalid/missing"), proxy.settings(), 1024, sink, 5, 20);

        assertEquals(404, status);
        assertEquals(0, sink.size());
    }

    @Test
    void followsARedirectToAnotherPath() throws Exception {
        proxy = new FakeSocks5Proxy();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/a", exchange -> {
            exchange.getResponseHeaders().add("Location", "/b");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        http.createContext("/b", exchange -> {
            byte[] body = "landed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        proxy.map("web.invalid", new InetSocketAddress("127.0.0.1", http.getAddress().getPort()));

        assertEquals("landed", get(URI.create("http://web.invalid/a"), proxy.settings(), 1024));
        assertEquals(2, proxy.connectTargets.size(), "each hop is its own tunnel");
    }

    @Test
    void anEndlessRedirectLoopStopsAtTheLimit() throws Exception {
        proxy = new FakeSocks5Proxy();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        http.start();
        proxy.map("web.invalid", new InetSocketAddress("127.0.0.1", http.getAddress().getPort()));

        IOException e = assertThrows(IOException.class, () -> MiniHttp.get(
                URI.create("http://web.invalid/loop"), proxy.settings(), 1024, new ByteArrayOutputStream(), 3, 20));

        assertTrue(e.getMessage().contains("Too many redirects"), e.getMessage());
    }

    @Test
    void aBodyLargerThanTheLimitIsRefused() throws Exception {
        proxy = new FakeSocks5Proxy();
        proxy.map("web.invalid", startHttp("/big", 200, new byte[10_000], null));

        IOException e = assertThrows(IOException.class, () -> MiniHttp.get(
                URI.create("http://web.invalid/big"), proxy.settings(), 1000, new ByteArrayOutputStream(), 5, 20));

        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
    }

    /** A server may ignore HTTP/1.0 and answer chunked anyway - the body must still decode. */
    @Test
    void decodesAChunkedResponse() throws Exception {
        proxy = new FakeSocks5Proxy();
        raw = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket s = raw.accept()) {
                s.getInputStream().read(new byte[2048]);
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
                // test over
            }
        });
        proxy.map("web.invalid", new InetSocketAddress("127.0.0.1", raw.getLocalPort()));

        assertEquals("hello world", get(URI.create("http://web.invalid/"), proxy.settings(), 1024));
    }

    @Test
    void aServerThatIsNotSpeakingHttpIsAnError() throws Exception {
        proxy = new FakeSocks5Proxy();
        raw = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket s = raw.accept()) {
                s.getInputStream().read(new byte[2048]);
                s.getOutputStream().write("SSH-2.0-nope\r\n".getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
                // test over
            }
        });
        proxy.map("web.invalid", new InetSocketAddress("127.0.0.1", raw.getLocalPort()));

        assertThrows(IOException.class, () -> MiniHttp.get(
                URI.create("http://web.invalid/"), proxy.settings(), 1024, new ByteArrayOutputStream(), 5, 20));
    }

    @Test
    void onlyHttpAndHttpsUrlsAreAccepted() throws Exception {
        proxy = new FakeSocks5Proxy();

        assertThrows(IOException.class, () -> MiniHttp.get(
                URI.create("ftp://web.invalid/x"), proxy.settings(), 1024, new ByteArrayOutputStream(), 5, 20));
        assertEquals(0, proxy.connectionsAccepted.get());
    }

    @Test
    void anUnreachableProxyFailsTheRequestInsteadOfGoingDirect() throws Exception {
        InetSocketAddress web = startHttp("/", 200, "should never be fetched".getBytes(StandardCharsets.UTF_8), null);
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        assertThrows(IOException.class, () -> MiniHttp.get(
                URI.create("http://127.0.0.1:" + web.getPort() + "/"),
                new ProxySettings("127.0.0.1", closedPort, null, null), 1024, new ByteArrayOutputStream(), 5, 20));
    }
}
