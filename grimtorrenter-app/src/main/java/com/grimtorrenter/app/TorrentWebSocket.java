package com.grimtorrenter.app;

import com.grimtorrenter.engine.settings.SettingsStore;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

/**
 * The /ws/torrents endpoint: checks the handshake (Origin, then the auth token) and hands
 * accepted connections to WebSocketBroadcaster, which owns them and does all the sending. No
 * client-to-server messages are handled. See design_docs/0019 and 0100.
 */
@WebSocket(path = "/ws/torrents")
public class TorrentWebSocket {

    /** Close code for "no valid auth token" - in the 4000-4999 range RFC 6455 leaves to
     * applications, and distinct from the 1008 an Origin rejection uses, so the client can tell
     * "your session has ended, log in again" (stop reconnecting, go to /login) from everything
     * else. The web UI treats it like a REST 401. See design_docs/0096. */
    static final CloseReason SESSION_ENDED = new CloseReason(4401, "Authentication required");
    private static final CloseReason ORIGIN_REJECTED = new CloseReason(1008, "Origin not allowed");

    @Inject
    SettingsStore settingsStore;

    @Inject
    SessionTokenStore sessionTokenStore;

    @Inject
    OriginPolicy originPolicy;

    @Inject
    WebSocketBroadcaster broadcaster;

    /** Browsers can't set custom headers on a WebSocket handshake - but the WebSocket
     * constructor's second argument (subprotocols) IS carried as a real request header
     * (Sec-WebSocket-Protocol), so the frontend sends ["bearer", &lt;token&gt;] there instead of
     * putting the token in the URL. Deliberately not a query param: a reverse proxy in front
     * of this app (which design_docs/0061 itself tells operators to add, for TLS) commonly
     * logs the full request URL including its query string by default, but not arbitrary
     * request headers - putting a long-lived credential in the URL would undermine the very
     * setup this app recommends. A connection rejected here is never registered with the
     * broadcaster and gets closed immediately, rather than silently receiving broadcasts it
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
        broadcaster.register(connection, token);
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
        broadcaster.unregister(connection);
    }
}
