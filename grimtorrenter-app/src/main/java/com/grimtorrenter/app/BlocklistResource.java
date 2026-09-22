package com.grimtorrenter.app;

import com.grimtorrenter.engine.blocklist.Blocklist;
import com.grimtorrenter.engine.engine.TorrentEngine;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Status of, and a manual reload for, the IP blocklist (design_docs/0078). No DTO wrapper -
 * Blocklist.Status is already a flat record of primitives and strings. The settings that drive
 * it (enabled/source/refresh interval) live in the ordinary /api/settings resource. */
@Path("/api/blocklist")
public class BlocklistResource {

    @Inject
    TorrentEngine torrentEngine;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Blocklist.Status status() {
        return torrentEngine.blocklist().status();
    }

    /** Starts a reload and returns immediately with the status as it stands (loading: true) -
     * the load itself runs on its own thread, so the caller polls GET for the outcome. */
    @POST
    @Path("/reload")
    @Produces(MediaType.APPLICATION_JSON)
    public Blocklist.Status reload() {
        torrentEngine.blocklist().reloadNow();
        return torrentEngine.blocklist().status();
    }
}
