package com.grimtorrenter.app;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;

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

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        CONNECTIONS.add(connection);
        Log.infof("WebSocket opened, %d connection(s) now tracked", CONNECTIONS.size());
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
