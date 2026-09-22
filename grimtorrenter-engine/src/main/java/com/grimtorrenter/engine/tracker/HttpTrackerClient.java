package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.proxy.MiniHttp;
import com.grimtorrenter.engine.proxy.ProxyProvider;
import com.grimtorrenter.engine.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * BEP 3 HTTP tracker client. Only requests/parses the compact peer list
 * format (compact=1) - effectively universal among real trackers; the
 * legacy non-compact dictionary-list format isn't supported.
 *
 * <p>One instance is expected to live for a torrent session's whole
 * lifetime and remembers the tracker's "tracker id" (if any) internally,
 * echoing it back on subsequent announces per BEP 3 convention - this is
 * tracker-connection-scoped state, not per-request data, so it isn't a
 * field on TrackerRequest.
 */
public final class HttpTrackerClient implements TrackerClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int COMPACT_PEER_ENTRY_LENGTH = 6;
    /** Real clients always identify themselves - Java's HttpClient default User-Agent gets
     * many trackers to reject the request outright (403) as obvious non-client traffic. */
    private static final String USER_AGENT = "GrimTorrenter/0.1.0";

    /** A tracker reply is a few KB of bencode (a compact peer list); this is a generous ceiling
     * that only exists to bound a hostile or broken tracker. Applies to the proxied path - the
     * direct path reads with the JDK client, as it always has. */
    private static final long MAX_PROXIED_RESPONSE_BYTES = 4L << 20;
    private static final int PROXIED_DEADLINE_SECONDS = 30;

    private final String announceUrl;
    private final HttpClient httpClient;
    private final ProxyProvider proxyProvider;
    private volatile String trackerId;

    public HttpTrackerClient(String announceUrl) {
        this(announceUrl, ProxyProvider.NONE);
    }

    /** proxyProvider is read on every announce: while it names a proxy the request is tunnelled
     * through it (and the tracker's hostname is resolved by the proxy, not locally); otherwise it
     * goes straight out as before. A proxy that can't be reached fails the announce - never a
     * silent direct request. See design_docs/0079. */
    public HttpTrackerClient(String announceUrl, ProxyProvider proxyProvider) {
        this(announceUrl, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), proxyProvider);
    }

    public HttpTrackerClient(String announceUrl, HttpClient httpClient) {
        this(announceUrl, httpClient, ProxyProvider.NONE);
    }

    private HttpTrackerClient(String announceUrl, HttpClient httpClient, ProxyProvider proxyProvider) {
        this.announceUrl = announceUrl;
        this.httpClient = httpClient;
        this.proxyProvider = proxyProvider;
    }

    @Override
    public TrackerResponse announce(TrackerRequest request) {
        String fullUrl = buildUrl(request);
        Optional<ProxySettings> proxy = proxyProvider.current();
        int status;
        byte[] body;
        if (proxy.isPresent()) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try {
                status = MiniHttp.get(URI.create(fullUrl), proxy.get(), MAX_PROXIED_RESPONSE_BYTES, sink, 0,
                        PROXIED_DEADLINE_SECONDS);
            } catch (IOException e) {
                throw new TrackerException("Tracker request to " + announceUrl + " through the proxy failed: "
                        + e.getMessage(), e);
            }
            body = sink.toByteArray();
        } else {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(fullUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                throw new TrackerException("Tracker request to " + announceUrl + " failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TrackerException("Tracker request to " + announceUrl + " was interrupted", e);
            }
            status = response.statusCode();
            body = response.body();
        }

        if (status != 200) {
            throw new TrackerException("Tracker request to " + announceUrl + " returned HTTP " + status);
        }

        return parseResponse(body);
    }

    private String buildUrl(TrackerRequest request) {
        StringBuilder url = new StringBuilder(announceUrl);
        url.append(announceUrl.contains("?") ? '&' : '?');
        url.append("info_hash=").append(percentEncode(request.infoHash().bytes()));
        url.append("&peer_id=").append(percentEncode(request.peerId().bytes()));
        url.append("&port=").append(request.port());
        url.append("&uploaded=").append(request.uploaded());
        url.append("&downloaded=").append(request.downloaded());
        url.append("&left=").append(request.left());
        url.append("&compact=1");
        url.append("&numwant=").append(request.numWant());
        if (request.event() != null) {
            url.append("&event=").append(request.event().wireName());
        }
        if (trackerId != null) {
            url.append("&trackerid=").append(percentEncode(trackerId.getBytes(StandardCharsets.UTF_8)));
        }
        return url.toString();
    }

    /**
     * info_hash/peer_id are raw binary, not text - java.net.URLEncoder is
     * string/charset-oriented and encodes space as '+', so a small
     * byte-level encoder is used instead for exact, unambiguous output.
     */
    private static String percentEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z') || (v >= '0' && v <= '9')
                    || v == '-' || v == '_' || v == '.' || v == '~') {
                sb.append((char) v);
            } else {
                sb.append('%').append(String.format("%02X", v));
            }
        }
        return sb.toString();
    }

    private TrackerResponse parseResponse(byte[] body) {
        BValue root = BencodeDecoder.decode(body);
        if (!(root instanceof BDictionary dict)) {
            throw new TrackerException("Tracker response was not a bencoded dictionary");
        }

        if (dict.get("failure reason") instanceof BString failureReason) {
            throw new TrackerException("Tracker announce failed: " + failureReason.utf8());
        }

        long interval = requireLong(dict, "interval");
        Long minInterval = optionalLong(dict, "min interval");
        int complete = (int) optionalLongOrDefault(dict, "complete", 0);
        int incomplete = (int) optionalLongOrDefault(dict, "incomplete", 0);
        String responseTrackerId = optionalString(dict, "tracker id");
        String warning = optionalString(dict, "warning message");
        List<PeerAddress> peers = parseCompactPeers(dict);

        if (responseTrackerId != null) {
            this.trackerId = responseTrackerId;
        }

        return new TrackerResponse(interval, minInterval, complete, incomplete, peers, responseTrackerId, warning);
    }

    private List<PeerAddress> parseCompactPeers(BDictionary dict) {
        if (!(dict.get("peers") instanceof BString compact)) {
            throw new TrackerException("Expected compact 'peers' string in tracker response");
        }
        byte[] raw = compact.bytes();
        if (raw.length % COMPACT_PEER_ENTRY_LENGTH != 0) {
            throw new TrackerException(
                    "Compact peers length " + raw.length + " is not a multiple of " + COMPACT_PEER_ENTRY_LENGTH);
        }
        List<PeerAddress> peers = new ArrayList<>(raw.length / COMPACT_PEER_ENTRY_LENGTH);
        for (int i = 0; i < raw.length; i += COMPACT_PEER_ENTRY_LENGTH) {
            byte[] addressBytes = {raw[i], raw[i + 1], raw[i + 2], raw[i + 3]};
            int port = ((raw[i + 4] & 0xFF) << 8) | (raw[i + 5] & 0xFF);
            try {
                peers.add(new PeerAddress(InetAddress.getByAddress(addressBytes), port));
            } catch (UnknownHostException e) {
                throw new TrackerException("Invalid peer address in tracker response", e);
            }
        }
        return List.copyOf(peers);
    }

    private static long requireLong(BDictionary dict, String key) {
        if (!(dict.get(key) instanceof BInteger i)) {
            throw new TrackerException("Missing or invalid required field '" + key + "' in tracker response");
        }
        return i.value();
    }

    private static Long optionalLong(BDictionary dict, String key) {
        BValue value = dict.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof BInteger i)) {
            throw new TrackerException("Field '" + key + "' expected to be an integer");
        }
        return i.value();
    }

    private static long optionalLongOrDefault(BDictionary dict, String key, long defaultValue) {
        Long value = optionalLong(dict, key);
        return value != null ? value : defaultValue;
    }

    private static String optionalString(BDictionary dict, String key) {
        BValue value = dict.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof BString s)) {
            throw new TrackerException("Field '" + key + "' expected to be a string");
        }
        return s.utf8();
    }
}
