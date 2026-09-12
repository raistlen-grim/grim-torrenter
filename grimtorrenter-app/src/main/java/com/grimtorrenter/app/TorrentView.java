package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
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
        boolean usesLsd,
        /** Null when unknown - see TorrentSession.addedAt()'s own Javadoc. */
        Instant addedAt,
        long lifetimeUploadedBytes,
        long timeActiveMillis,
        /** 0 if this torrent has never completed - see TorrentSession.completedAtEpochMillis()'s
         * own Javadoc. See design_docs/0064. */
        long completedAtEpochMillis,
        /** Bytes received and discarded to a failed piece hash check, lifetime - see
         * TorrentSession.wastedBytes()'s own Javadoc. See design_docs/0066. */
        long wastedBytes
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
     * eligible at all. See design_docs/0036/0039.
     *
     * <p>usesLsd reads TorrentSession.usesLsd() (design_docs/0062) - true whenever BEP 14 Local
     * Service Discovery was running at the engine level when this session was created and the
     * torrent isn't private (BEP 27), same shape as usesDht but sourced from a construction-time
     * snapshot rather than a live session-owned reference - see TorrentSession's own lsdActive
     * field Javadoc for why.
     *
     * <p>lifetimeUploadedBytes/timeActiveMillis/completedAtEpochMillis (design_docs/0064) are
     * this torrent's totals across every process run, not just this one - same
     * cheap-enough-not-to-need-a-dedicated-endpoint reasoning as usesDht/trackerCount above. */
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
                session.usesLsd(),
                session.addedAt(),
                session.lifetimeUploadedBytes(),
                session.timeActiveMillis(),
                session.completedAtEpochMillis(),
                session.wastedBytes());
    }

    /** A magnet still fetching metadata, rendered through the exact same DTO shape a resolved
     * torrent uses - state "FETCHING_METADATA" (not one of TorrentSession's own TorrentState
     * values - see TorrentEngine.PendingMagnet's own Javadoc for why), every byte/piece/rate
     * field zeroed (there's no TorrentSession, and therefore nothing real to report), name
     * falling back to the info hash hex when the magnet carried no display name. See
     * design_docs/0070. */
    public static TorrentView fromPendingMagnet(TorrentEngine.PendingMagnet pending) {
        String name = pending.displayName() != null ? pending.displayName() : pending.infoHash().hex();
        return new TorrentView(
                pending.infoHash().hex(),
                name,
                "FETCHING_METADATA",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                false,
                pending.trackers().size(),
                false,
                false,
                null,
                0,
                0,
                0,
                0);
    }
}
