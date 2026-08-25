package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Separate from TorrentResource - DHT status is global engine state, not scoped to any
 * one torrent, and is meant to be polled on demand only while something's displaying it
 * rather than riding the always-on torrent snapshot broadcast. See design_docs/0028. */
@Path("/api/dht")
public class DhtResource {

    @Inject
    TorrentEngine torrentEngine;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public DhtStatusView status() {
        return DhtStatusView.from(torrentEngine.dhtStatus());
    }
}
