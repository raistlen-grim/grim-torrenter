package com.grimtorrenter.app;

import com.grimtorrenter.engine.settings.SettingsStore;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Broadcast-only - no client-to-server messages are handled. Connections are tracked in a
 * self-managed static map rather than relying on a framework-provided cross-connection broadcast
 * helper, so this works regardless of whether quarkus-websockets-next instantiates one shared
 * endpoint instance or one per connection. See design_docs/0019.
 *
 * <p>Each connection is kept with the token it authenticated with (or none, if auth was off), so
 * TorrentSnapshotScheduler can close it once that token stops being valid - logout, expiry, a
 * password change, or auth being switched on. See design_docs/0094.
 */
@WebSocket(path = "/ws/torrents")
public class TorrentWebSocket {

    /** The token a connection opened with; {@code token} is null when auth was off. A record
     * because ConcurrentHashMap doesn't allow null values. */
    private record Client(String token) {
    }

    private static final Map<WebSocketConnection, Client> CONNECTIONS = new ConcurrentHashMap<>();

    /** Close code for "no valid auth token" - in the 4000-4999 range RFC 6455 leaves to
     * applications, and distinct from the 1008 an Origin rejection uses, so the client can tell
     * "your session has ended, log in again" (stop reconnecting, go to /login) from everything
     * else. The web UI treats it like a REST 401. See design_docs/0096. */
    static final CloseReason SESSION_ENDED = new CloseReason(4401, "Authentication required");
    private static final CloseReason ORIGIN_REJECTED = new CloseReason(1008, "Origin not allowed");
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    @Inject
    SettingsStore settingsStore;

    @Inject
    SessionTokenStore sessionTokenStore;

    @Inject
    OriginPolicy originPolicy;

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
     * <p>The handshake response has to select one of the offered subprotocols: a browser that
     * offered any fails the connection as soon as the handshake completes if the server picks
     * none.
     * application.properties therefore lists "bearer" under
     * quarkus.websockets-next.server.supported-subprotocols. See design_docs/0061's
     * 2026-10-01 correction. */
    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        var handshake = connection.handshakeRequest();
        if (!originPolicy.allows(handshake.header("Origin"), handshake.header("Host"),
                handshake.header("X-Forwarded-Host"))) {
            // Cross-site WebSocket hijacking - see OriginPolicy and design_docs/0093.
            Log.warnf("Rejecting WebSocket handshake from origin %s (Host %s) - set "
                    + "grimtorrenter.allowed-origins if a reverse proxy rewrites Host",
                    handshake.header("Origin"), handshake.header("Host"));
            connection.close(ORIGIN_REJECTED).subscribe().with(ignored -> { }, failure -> { });
            return;
        }
        String token = tokenFromSubprotocol(connection);
        if (settingsStore.current().authEnabled() && !sessionTokenStore.validate(token)) {
            Log.debug("Rejecting WebSocket handshake with a missing/invalid auth token");
            connection.close(SESSION_ENDED).subscribe().with(ignored -> { }, failure -> { });
            return;
        }
        CONNECTIONS.put(connection, new Client(token));
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

    /** Lets TorrentSnapshotScheduler skip building a snapshot nobody would receive. */
    static boolean hasConnections() {
        return !CONNECTIONS.isEmpty();
    }

    /** Closes every connection whose token (null if it opened with auth off) fails the check.
     * The browser's reconnect then goes through onOpen's handshake check again. See
     * design_docs/0094. */
    static void closeConnectionsWhoseTokenFails(Predicate<String> tokenIsValid) {
        CONNECTIONS.forEach((connection, client) -> {
            if (!tokenIsValid.test(client.token())) {
                Log.info("Closing a WebSocket whose auth token is no longer valid");
                if (CONNECTIONS.remove(connection) != null) {
                    connection.close(SESSION_ENDED).subscribe().with(ignored -> { }, failure -> { });
                }
            }
        });
    }

    private static void dropAndClose(WebSocketConnection connection) {
        if (CONNECTIONS.remove(connection) != null) {
            connection.close().subscribe().with(ignored -> { }, failure -> { });
        }
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
     * forever.
     *
     * <p>A failed connection is also closed, not just forgotten: a socket left open but never
     * sent to again looks healthy to the browser, so its reconnect logic never runs and the UI
     * silently goes stale (e.g. after a laptop sleep). See design_docs/0094. */
    static void broadcast(String json) {
        Log.debugf("Broadcasting to %d connection(s)", CONNECTIONS.size());
        for (WebSocketConnection connection : CONNECTIONS.keySet()) {
            connection.sendText(json)
                    .ifNoItem().after(SEND_TIMEOUT)
                    .failWith(() -> new TimeoutException("WebSocket send timed out after " + SEND_TIMEOUT))
                    .subscribe().with(
                            ignored -> { },
                            failure -> {
                                Log.warnf(failure, "Closing a WebSocket connection that failed to send");
                                dropAndClose(connection);
                            });
        }
    }
}
