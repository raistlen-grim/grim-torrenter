package com.grimtorrenter.app;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * The open /ws/torrents connections, and the one way to push to them. TorrentWebSocket registers
 * and unregisters connections; TorrentEventListener, TorrentSnapshotScheduler and
 * JsonLinesEventStore broadcast. An injected singleton rather than static state on the endpoint,
 * so it doesn't matter whether quarkus-websockets-next makes one endpoint instance or one per
 * connection, and a test can hand a bean its own instance. See design_docs/0019 and 0100.
 *
 * <p>Each connection is kept with the token it authenticated with (or none, if auth was off), so
 * TorrentSnapshotScheduler can close it once that token stops being valid - logout, expiry, a
 * password change, or auth being switched on. See design_docs/0094.
 */
@ApplicationScoped
public class WebSocketBroadcaster {

    /** The token a connection opened with; {@code token} is null when auth was off. A record
     * because ConcurrentHashMap doesn't allow null values. */
    private record Client(String token) {
    }

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final Map<WebSocketConnection, Client> connections = new ConcurrentHashMap<>();

    void register(WebSocketConnection connection, String token) {
        connections.put(connection, new Client(token));
        Log.infof("WebSocket opened, %d connection(s) now tracked", connections.size());
    }

    void unregister(WebSocketConnection connection) {
        connections.remove(connection);
        Log.infof("WebSocket closed, %d connection(s) now tracked", connections.size());
    }

    /** Lets TorrentSnapshotScheduler skip building a snapshot nobody would receive. */
    boolean hasConnections() {
        return !connections.isEmpty();
    }

    /** Closes every connection whose token (null if it opened with auth off) fails the check.
     * The browser's reconnect then goes through TorrentWebSocket.onOpen's handshake check again.
     * See design_docs/0094. */
    void closeConnectionsWhoseTokenFails(Predicate<String> tokenIsValid, CloseReason reason) {
        connections.forEach((connection, client) -> {
            if (!tokenIsValid.test(client.token())) {
                Log.info("Closing a WebSocket whose auth token is no longer valid");
                if (connections.remove(connection) != null) {
                    connection.close(reason).subscribe().with(ignored -> { }, failure -> { });
                }
            }
        });
    }

    /** Non-blocking (sendText, not sendTextAndAwait): callers include TorrentSnapshotScheduler's
     * scheduled thread and engine state transitions, and a connection that went away without a
     * close handshake (a tab killed mid-navigation, a laptop asleep) could otherwise hold a send
     * open indefinitely. A send that fails or doesn't finish within SEND_TIMEOUT drops the
     * connection, so one bad connection costs a few seconds once, not a stuck thread per tick.
     *
     * <p>A failed connection is also closed, not just forgotten: a socket left open but never
     * sent to again looks healthy to the browser, so its reconnect logic never runs and the UI
     * silently goes stale. See design_docs/0094. */
    void broadcast(String json) {
        Log.debugf("Broadcasting to %d connection(s)", connections.size());
        for (WebSocketConnection connection : connections.keySet()) {
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

    private void dropAndClose(WebSocketConnection connection) {
        if (connections.remove(connection) != null) {
            connection.close().subscribe().with(ignored -> { }, failure -> { });
        }
    }
}
