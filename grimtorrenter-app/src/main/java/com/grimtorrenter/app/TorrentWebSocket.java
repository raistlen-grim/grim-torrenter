package com.grimtorrenter.app;

import com.grimtorrenter.engine.settings.SettingsStore;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Broadcast-only in Phase 1 - no client-to-server messages are handled.
 * Connections are tracked in a self-managed static set rather than relying
 * on a framework-provided cross-connection broadcast helper, so this works
 * regardless of whether quarkus-websockets-next instantiates one shared
 * endpoint instance or one per connection. See design_docs/0019 for the
 * verification caveats on this file's exact API usage.
 */
@WebSocket(path = "/ws/torrents")
public class TorrentWebSocket {

    private static final Set<WebSocketConnection> CONNECTIONS = ConcurrentHashMap.newKeySet();
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    @Inject
    SettingsStore settingsStore;

    @Inject
    SessionTokenStore sessionTokenStore;

    /** Browsers can't set custom headers on a WebSocket handshake - but the WebSocket
     * constructor's second argument (subprotocols) IS carried as a real request header
     * (Sec-WebSocket-Protocol), so the frontend sends ["bearer", &lt;token&gt;] there instead of
     * putting the token in the URL. Deliberately not a query param: a reverse proxy in front
     * of this app (which design_docs/0061 itself tells operators to add, for TLS) commonly
     * logs the full request URL including its query string by default, but not arbitrary
     * request headers - putting a long-lived credential in the URL would undermine the very
     * setup this app recommends. A connection rejected here is simply never added to
     * CONNECTIONS and gets closed immediately, rather than silently receiving broadcasts it
     * was never entitled to. See design_docs/0061.
     *
     * <p>Flagged, like this file's other API usage (design_docs/0019), as not yet confirmed
     * by actually compiling it: handshakeRequest().header(String) is written against the
     * documented shape of quarkus-websockets-next's HandshakeRequest, not verified against its
     * real signature. This endpoint doesn't echo a chosen subprotocol back in the handshake
     * response - per RFC 6455 that's optional, and browsers complete the connection fine
     * without one (they just see an empty WebSocket.protocol), so no response-side API is
     * needed even if this framework doesn't expose one. */
    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        if (settingsStore.current().authEnabled() && !sessionTokenStore.validate(tokenFromSubprotocol(connection))) {
            Log.warn("Rejecting WebSocket handshake with a missing/invalid auth token");
            connection.close().subscribe().with(ignored -> { }, failure -> { });
            return;
        }
        CONNECTIONS.add(connection);
        Log.infof("WebSocket opened, %d connection(s) now tracked", CONNECTIONS.size());
    }

    /** The client always offers exactly ["bearer", &lt;token&gt;], in that order - the header
     * value is those two names comma-joined by the browser (e.g. "bearer, abc123"). */
    private static String tokenFromSubprotocol(WebSocketConnection connection) {
        String header = connection.handshakeRequest().header("Sec-WebSocket-Protocol");
        if (header == null) {
            return null;
        }
        String[] parts = header.split(",", 2);
        if (parts.length != 2 || !"bearer".equalsIgnoreCase(parts[0].trim())) {
            return null;
        }
        return parts[1].trim();
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        CONNECTIONS.remove(connection);
        Log.infof("WebSocket closed, %d connection(s) now tracked", CONNECTIONS.size());
    }

    /** Deliberately non-blocking (sendText, not sendTextAndAwait) - this is called from
     * TorrentSnapshotScheduler's scheduled thread, not a per-request one, so blocking here
     * ties that thread up until every connection's send completes. A connection that went
     * stale without a clean close handshake (@OnClose never fires for it - e.g. a browser
     * tab yanked away mid-navigation, not just a graceful reload) could otherwise hang a
     * send indefinitely; a real incident this caused during frontend development, with
     * live-reload repeatedly abandoning connections mid-broadcast, motivated this. A
     * bounded timeout plus removing the connection on any failure means one bad connection
     * can only cost a few seconds once, not accumulate a stuck thread every broadcast tick
     * forever. */
    static void broadcast(String json) {
        Log.debugf("Broadcasting to %d connection(s)", CONNECTIONS.size());
        for (WebSocketConnection connection : CONNECTIONS) {
            connection.sendText(json)
                    .ifNoItem().after(SEND_TIMEOUT)
                    .failWith(() -> new TimeoutException("WebSocket send timed out after " + SEND_TIMEOUT))
                    .subscribe().with(
                            ignored -> { },
                            failure -> {
                                Log.warnf(failure, "Removing a WebSocket connection that failed to send");
                                CONNECTIONS.remove(connection);
                            });
        }
    }
}
