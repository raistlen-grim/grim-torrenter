package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.proxy.Socks5;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.UncheckedIOException;

/**
 * The SOCKS5 proxy's status, its write-only password, and a "test it" action (design_docs/0079).
 * The proxy's host/port/username/enabled/block settings live in the ordinary /api/settings
 * resource; the password does not - it is stored in its own file and, unlike everything else, is
 * never returned by any endpoint: the status only says whether one is set.
 */
@Path("/api/proxy")
public class ProxyResource {

    /** Request body for setting the password. */
    public record PasswordRequest(String password) {
    }

    @Inject
    TorrentEngine torrentEngine;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TorrentEngine.ProxyStatus status() {
        return torrentEngine.proxyStatus();
    }

    /** An empty password clears it, same as DELETE. */
    @PUT
    @Path("/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public TorrentEngine.ProxyStatus setPassword(PasswordRequest request) {
        try {
            torrentEngine.proxyConfig().setPassword(request == null ? null : request.password());
            torrentEngine.retryFailedStarts();
        } catch (UncheckedIOException e) {
            throw new WebApplicationException("Could not save the proxy password", Response.Status.INTERNAL_SERVER_ERROR);
        }
        return torrentEngine.proxyStatus();
    }

    @DELETE
    @Path("/password")
    @Produces(MediaType.APPLICATION_JSON)
    public TorrentEngine.ProxyStatus clearPassword() {
        try {
            torrentEngine.proxyConfig().clearPassword();
            torrentEngine.retryFailedStarts();
        } catch (UncheckedIOException e) {
            throw new WebApplicationException("Could not remove the proxy password", Response.Status.INTERNAL_SERVER_ERROR);
        }
        return torrentEngine.proxyStatus();
    }

    /** Checks the currently *saved* proxy settings and password (save first, then test): is it
     * reachable, are the credentials accepted, does it relay UDP? Blocks for up to a few
     * seconds. */
    @POST
    @Path("/test")
    @Produces(MediaType.APPLICATION_JSON)
    public Socks5.TestResult test() {
        return torrentEngine.testProxy();
    }
}
