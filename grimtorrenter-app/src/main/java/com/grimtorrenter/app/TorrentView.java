package com.grimtorrenter.app;

import com.grimtorrenter.engine.torrent.TorrentSession;

public record TorrentView(
        String infoHash,
        String name,
        String state,
        double progress,
        long bytesDownloaded,
        long bytesReceived,
        long bytesUploaded,
        long totalLength,
        int connectedPeers,
        int completedPieces,
        int totalPieces,
        String lastError,
        boolean usesDht,
        int trackerCount,
        boolean dhtBackstopActive
) {
    /** bytesReceived (raw, includes not-yet-verified data - see
     * TorrentSession.bytesReceived()) is separate from bytesDownloaded (verified-complete
     * pieces only, drives progress/%) specifically so a client-side rate calculation has a
     * continuously-moving number to work from - bytesDownloaded alone only moves in
     * whole-piece jumps, which reads as a stalled 0 B/s for long stretches on torrents with
     * large pieces even while data is genuinely streaming in. See design_docs/0031.
     *
     * <p>usesDht/trackerCount ride this always-broadcast DTO rather than a dedicated
     * Summary endpoint - both are now cheap (isTrackerless() and trackers().size()), and
     * the detail header already reads this same live-pushed data via TorrentEventsService
     * rather than a separate fetch, so a dedicated endpoint would only have duplicated it.
     * See design_docs/0031's Summary section.
     *
     * <p>dhtBackstopActive is separate from usesDht - a tracker-bearing torrent
     * (usesDht=false) can still have this true while its tracker is unreachable and DHT is
     * standing in; usesDht alone (isTrackerless()) only ever means "has zero trackers at
     * all." See design_docs/0036/0039. */
    public static TorrentView from(TorrentSession session) {
        Throwable error = session.lastError();
        return new TorrentView(
                session.metadata().infoHash().hex(),
                session.metadata().name(),
                session.state().name(),
                session.progress(),
                session.bytesDownloaded(),
                session.bytesReceived(),
                session.bytesUploaded(),
                session.metadata().totalLength(),
                session.connectedPeerCount(),
                session.completedPieceCount(),
                session.metadata().pieces().count(),
                error != null ? error.getMessage() : null,
                session.isTrackerless(),
                session.trackers().size(),
                session.isDhtBackstopActive());
    }
}
