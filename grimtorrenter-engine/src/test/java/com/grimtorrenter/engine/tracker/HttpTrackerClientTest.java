package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses the JDK's built-in com.sun.net.httpserver.HttpServer as a real local
 * tracker stand-in rather than a mocking library - no new dependency needed
 * for a single test class, and it exercises the real HttpClient round trip.
 */
class HttpTrackerClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(byte[] responseBody, int statusCode, AtomicReference<String> capturedQuery)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announce", exchange -> {
            if (capturedQuery != null) {
                capturedQuery.set(exchange.getRequestURI().getRawQuery());
            }
            exchange.sendResponseHeaders(statusCode, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/announce";
    }

    private static byte[] fakeInfoHashBytes() {
        byte[] b = new byte[20];
        for (int i = 0; i < 20; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    private static byte[] fakePeerIdBytes() {
        byte[] b = new byte[20];
        for (int i = 0; i < 20; i++) {
            b[i] = (byte) (100 + i);
        }
        return b;
    }

    private static String extractParam(String query, String key) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        throw new AssertionError("Param '" + key + "' not found in query: " + query);
    }

    private static byte[] percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    @Test
    void parsesSuccessfulCompactResponseAndSendsCorrectlyEncodedRequest() throws IOException {
        byte[] compactPeers = {(byte) 192, (byte) 168, 1, 1, 0x1A, (byte) 0xE1}; // 192.168.1.1:6881
        BDictionary response = new BDictionary(Map.of(
                BString.of("interval"), new BInteger(1800),
                BString.of("complete"), new BInteger(5),
                BString.of("incomplete"), new BInteger(2),
                BString.of("peers"), BString.of(compactPeers)));

        AtomicReference<String> capturedQuery = new AtomicReference<>();
        String url = startServer(BencodeEncoder.encode(response), 200, capturedQuery);

        HttpTrackerClient client = new HttpTrackerClient(url);
        TrackerRequest request = new TrackerRequest(
                InfoHash.of(fakeInfoHashBytes()), PeerId.of(fakePeerIdBytes()),
                6881, 0, 0, 1000, TrackerEvent.STARTED, 50);

        TrackerResponse result = client.announce(request);

        assertEquals(1800, result.interval());
        assertEquals(5, result.complete());
        assertEquals(2, result.incomplete());
        assertEquals(1, result.peers().size());
        assertEquals("192.168.1.1", result.peers().get(0).address().getHostAddress());
        assertEquals(6881, result.peers().get(0).port());

        String query = capturedQuery.get();
        assertNotNull(query);
        assertTrue(query.contains("event=started"));
        assertTrue(query.contains("compact=1"));
        assertArrayEquals(fakeInfoHashBytes(), percentDecode(extractParam(query, "info_hash")));
        assertArrayEquals(fakePeerIdBytes(), percentDecode(extractParam(query, "peer_id")));
    }

    @Test
    void throwsOnFailureReason() throws IOException {
        BDictionary response = new BDictionary(Map.of(
                BString.of("failure reason"), BString.of("unregistered torrent")));
        String url = startServer(BencodeEncoder.encode(response), 200, null);

        HttpTrackerClient client = new HttpTrackerClient(url);
        TrackerRequest request = new TrackerRequest(
                InfoHash.of(fakeInfoHashBytes()), PeerId.of(fakePeerIdBytes()),
                6881, 0, 0, 1000, null, 50);

        TrackerException ex = assertThrows(TrackerException.class, () -> client.announce(request));
        assertTrue(ex.getMessage().contains("unregistered torrent"));
    }

    @Test
    void throwsOnNonCompactPeerList() throws IOException {
        BDictionary response = new BDictionary(Map.of(
                BString.of("interval"), new BInteger(1800),
                BString.of("peers"), new BList(List.of())));
        String url = startServer(BencodeEncoder.encode(response), 200, null);

        HttpTrackerClient client = new HttpTrackerClient(url);
        TrackerRequest request = new TrackerRequest(
                InfoHash.of(fakeInfoHashBytes()), PeerId.of(fakePeerIdBytes()),
                6881, 0, 0, 1000, null, 50);

        assertThrows(TrackerException.class, () -> client.announce(request));
    }

    @Test
    void throwsOnHttpErrorStatus() throws IOException {
        String url = startServer(new byte[0], 500, null);

        HttpTrackerClient client = new HttpTrackerClient(url);
        TrackerRequest request = new TrackerRequest(
                InfoHash.of(fakeInfoHashBytes()), PeerId.of(fakePeerIdBytes()),
                6881, 0, 0, 1000, null, 50);

        assertThrows(TrackerException.class, () -> client.announce(request));
    }

    @Test
    void echoesTrackerIdOnSubsequentAnnounce() throws IOException {
        BDictionary response = new BDictionary(Map.of(
                BString.of("interval"), new BInteger(1800),
                BString.of("peers"), BString.of(new byte[0]),
                BString.of("tracker id"), BString.of("abc123")));

        AtomicReference<String> capturedQuery = new AtomicReference<>();
        String url = startServer(BencodeEncoder.encode(response), 200, capturedQuery);

        HttpTrackerClient client = new HttpTrackerClient(url);
        TrackerRequest request = new TrackerRequest(
                InfoHash.of(fakeInfoHashBytes()), PeerId.of(fakePeerIdBytes()),
                6881, 0, 0, 1000, TrackerEvent.STARTED, 50);

        client.announce(request);
        assertFalse(capturedQuery.get().contains("trackerid="));

        client.announce(request);
        assertTrue(capturedQuery.get().contains("trackerid=abc123"));
    }
}
