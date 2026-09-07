package com.grimtorrenter.app;

import com.grimtorrenter.engine.settings.SettingsStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Login/logout/password-change for GrimTorrenter's own management API - a single shared
 * password, no username: this app has exactly one torrent list per deployment, not one per
 * account, and a username field would misleadingly imply otherwise. See design_docs/0061.
 *
 * <p>/status and /login are always reachable regardless of Settings.authEnabled -
 * AuthenticationFilter allowlists both explicitly, since a client with no token yet has to be
 * able to find out whether one is needed and, if so, obtain one.
 */
@Path("/api/auth")
public class AuthResource {

    @Inject
    AuthStore authStore;

    @Inject
    SessionTokenStore sessionTokenStore;

    @Inject
    SettingsStore settingsStore;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public AuthStatusView status() {
        return new AuthStatusView(settingsStore.current().authEnabled(), authStore.hasPassword());
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public LoginResponse login(LoginRequest request) {
        if (sessionTokenStore.isLockedOut()) {
            throw new WebApplicationException("Too many failed attempts - try again shortly", 429);
        }
        if (!authStore.verify(request.password())) {
            sessionTokenStore.recordFailure();
            throw new NotAuthorizedException("Invalid password");
        }
        sessionTokenStore.recordSuccess();
        return new LoginResponse(sessionTokenStore.issue());
    }

    @POST
    @Path("/logout")
    public Response logout(@HeaderParam("Authorization") String authorizationHeader) {
        sessionTokenStore.revoke(BearerToken.extract(authorizationHeader));
        return Response.noContent().build();
    }

    /** Two modes, based on whether a password is currently stored: with none yet, this sets
     * the initial one unconditionally - no more "open" than the rest of the API already is
     * before any password exists, and Settings.authEnabled can't be true yet either (see
     * SettingsResource's own rejection). With one already stored, the request must supply the
     * correct current password - independent of whether authEnabled is even on, so an
     * otherwise-still-open deployment can't have its about-to-be-protected password silently
     * overwritten by anyone who happens to hit this endpoint first. See design_docs/0061. */
    @PUT
    @Path("/password")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changePassword(PasswordChangeRequest request) {
        if (request.newPassword() == null || request.newPassword().isBlank()) {
            return ErrorResponses.badRequest("newPassword must not be blank");
        }
        if (authStore.hasPassword() && !authStore.verify(request.currentPassword())) {
            throw new NotAuthorizedException("Current password is incorrect");
        }
        authStore.setPassword(request.newPassword());
        return Response.noContent().build();
    }
}
