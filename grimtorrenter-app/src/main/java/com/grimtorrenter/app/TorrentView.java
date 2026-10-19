package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.ratelimit.AppliedLimit;
import com.grimtorrenter.engine.stats.TransferRate;
import com.grimtorrenter.engine.torrent.Eta;
import com.grimtorrenter.engine.torrent.EtaStatus;
import com.grimtorrenter.engine.torrent.TorrentSession;

import java.time.Instant;
import java.util.List;

public record TorrentView(
        String infoHash,
        String name,
        String state,
        double progress,
        long bytesDownloaded,
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
        long wastedBytes,
        /** Ids of the labels on this torrent - ids, not names; the names come from GET
         * /api/labels (and the "labels" WebSocket message), so a rename never changes a torrent
         * row. Empty for a pending magnet. See design_docs/0077. */
        List<String> labelIds,
        /** True while files this torrent wants are absent from disk and it is waiting to be told
         * what to do: state is ERROR (or STOPPED if paused meanwhile) and lastError says so. A
         * recheck downloads them again; removing the torrent is the other way out. A flag as
         * well as the message so a client can offer those two choices without reading text. See
         * design_docs/0088. */
        boolean missingFiles,
        /** The current rates: the mean of the last 5 s, measured server-side. 0 unless running.
         * See design_docs/0102. */
        long downloadRateBytesPerSec,
        long uploadRateBytesPerSec,
        /** Bytes/sec for each of up to the last 60 s, oldest first, for a sparkline - empty when
         * nothing arrived in that time, so idle torrents cost nothing. */
        long[] downloadRateTrend,
        /** Seconds to completion; null unless etaStatus is ESTIMATE. */
        Long etaSeconds,
        /** Why the ETA is what it is: COMPLETE, NOT_RUNNING, NO_PEERS, UNAVAILABLE, CHOKED,
         * STALLED or ESTIMATE - see EtaStatus. */
        String etaStatus,
        /** Wanted pieces no connected peer has; set only when etaStatus is UNAVAILABLE. */
        int unavailablePieces,
        /** The limit applying to this torrent right now (0 for none) and its source: NONE,
         * GLOBAL, SCHEDULED or TORRENT. */
        long downloadLimitBytesPerSec,
        String downloadLimitSource,
        /** True when a rate limiter held this torrent back in the last few seconds - not "rate
         * equals limit", since inheriting torrents share one global bucket. */
        boolean downloadCapped,
        long uploadLimitBytesPerSec,
        String uploadLimitSource,
        boolean uploadCapped
) {
    /** Rates, ETA and caps are the server's own figures (design_docs/0102), so a client shows
     * them as they come - no rate calculation of its own. bytesReceived, which existed only for
     * the browser's old rate calculation, is gone.
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
        TransferRate download = session.downloadRate();
        TransferRate upload = session.uploadRate();
        Eta eta = session.eta();
        AppliedLimit downloadLimit = session.downloadLimit();
        AppliedLimit uploadLimit = session.uploadLimit();
        long now = System.currentTimeMillis();
        return new TorrentView(
                session.metadata().infoHash().hex(),
                session.metadata().name(),
                session.state().name(),
                session.progress(),
                session.bytesDownloaded(),
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
                session.wastedBytes(),
                session.labelIds(),
                session.missingFiles(),
                download.bytesPerSec(),
                upload.bytesPerSec(),
                download.recentTrend(),
                eta.seconds(),
                eta.status().name(),
                eta.unavailablePieces(),
                downloadLimit.bytesPerSec(),
                downloadLimit.source().name(),
                session.downloadCapped(now),
                uploadLimit.bytesPerSec(),
                uploadLimit.source().name(),
                session.uploadCapped(now));
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
                null,
                false,
                pending.trackers().size(),
                false,
                false,
                null,
                0,
                0,
                0,
                0,
                List.of(),
                false,
                0,
                0,
                new long[0],
                null,
                EtaStatus.NOT_RUNNING.name(),
                0,
                0,
                AppliedLimit.Source.NONE.name(),
                false,
                0,
                AppliedLimit.Source.NONE.name(),
                false);
    }
}
