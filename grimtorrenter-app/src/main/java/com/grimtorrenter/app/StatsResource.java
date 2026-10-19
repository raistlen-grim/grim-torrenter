package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.stats.HistoryResolution;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Engine-wide transfer figures and history - same "separate small resource" shape as
 * SystemResource and DhtResource, since none of it belongs to one torrent. See design_docs/0102. */
@Path("/api/stats")
public class StatsResource {

    @Inject
    TorrentEngine torrentEngine;

    @GET
    @Path("/transfer")
    @Produces(MediaType.APPLICATION_JSON)
    public TransferView transfer() {
        return TransferView.from(torrentEngine.globalTransfer(), torrentEngine.listTorrents());
    }

    /** resolution=second (the last minute), minute (the last 2 hours) or hour (the last 7 days,
     * kept across restarts). */
    @GET
    @Path("/history")
    @Produces(MediaType.APPLICATION_JSON)
    public HistoryView history(@QueryParam("resolution") String resolutionParam) {
        HistoryResolution resolution = HistoryView.parseResolution(resolutionParam);
        return HistoryView.from(resolution, torrentEngine.transferHistory(resolution));
    }
}
