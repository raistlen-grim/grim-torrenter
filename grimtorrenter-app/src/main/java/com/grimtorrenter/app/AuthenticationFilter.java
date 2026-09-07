package com.grimtorrenter.app;

import com.grimtorrenter.engine.settings.SettingsStore;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * Gates every /api/* request behind a valid bearer token once Settings.authEnabled is true -
 * a complete no-op (fully open, matching this app's behavior before this feature existed)
 * while it's false. /api/auth/login and /api/auth/status stay reachable regardless - a client
 * with no token yet has to be able to find out whether one is needed and, if so, obtain one.
 * See design_docs/0061.
 *
 * <p>Doesn't need to know about the WebSocket endpoint (/ws/torrents) at all - that's
 * dispatched by quarkus-websockets-next, entirely outside the JAX-RS filter chain this
 * @Provider participates in. See TorrentWebSocket's own handshake-time check instead.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {

    private static final Set<String> ALWAYS_ALLOWED_PATHS = Set.of("api/auth/login", "api/auth/status");

    @Inject
    SettingsStore settingsStore;

    @Inject
    SessionTokenStore sessionTokenStore;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!settingsStore.current().authEnabled()) {
            return;
        }
        String path = requestContext.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (ALWAYS_ALLOWED_PATHS.contains(path)) {
            return;
        }
        String token = BearerToken.extract(requestContext.getHeaderString("Authorization"));
        if (token == null || !sessionTokenStore.validate(token)) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
        }
    }
}
