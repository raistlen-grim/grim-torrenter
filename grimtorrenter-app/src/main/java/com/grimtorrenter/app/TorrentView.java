package com.grimtorrenter.app;

import com.grimtorrenter.engine.torrent.TorrentSession;

import java.time.Instant;

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
        boolean dhtBackstopActive,
        /** Null when unknown - see TorrentSession.addedAt()'s own Javadoc. */
        Instant addedAt
) {
    /** bytesReceived (raw, includes not-yet-verified data - see
     * TorrentSession.bytesReceived()) is separate from bytesDownloaded (verified-complete
     * pieces only, drives progress/%) specifically so a client-side rate calculation has a
     * continuously-moving number to work from - bytesDownloaded alone only moves in
     * whole-piece jumps, which reads as a stalled 0 B/s for long stretches on torrents with
     * large pieces even while data is genuinely streaming in. See design_docs/0031.
     *
     * <p>usesDht/trackerCount ride this always-broadcast DTO rather than a dedicated
     * Summary endpoint - both are now cheap (usesDht() and trackers().size()), and
     * the detail header already reads this same live-pushed data via TorrentEventsService
     * rather than a separate fetch, so a dedicated endpoint would only have duplicated it.
     * See design_docs/0031's Summary section.
     *
     * <p>usesDht reads TorrentSession.usesDht() (design_docs/0036's own 2026-09-06 revision) -
     * true whenever DHT is actually eligible as a peer source for this torrent right now (DHT
     * configured and the torrent isn't private, BEP 27), regardless of tracker presence or
     * health. dhtBackstopActive is a separate, tracker-health-only signal: true whenever the
     * tracker's own most recent attempt failed, independent of whether DHT happens to be
     * eligible at all. See design_docs/0036/0039. */
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
                session.usesDht(),
                session.trackers().size(),
                session.isDhtBackstopActive(),
                session.addedAt());
    }
}
