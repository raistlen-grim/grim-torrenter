package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.torrent.TorrentSession;

import java.util.Collection;

/**
 * The engine-wide transfer picture behind the header and footer: overall rates, the global limits
 * and whether they're holding traffic back, and the lifetime ratio. GET /api/stats/transfer, and
 * the "transfer" WebSocket message on every snapshot tick. See design_docs/0102.
 *
 * @param downloadCapped true when the global download limit made something wait in the last few
 *                       seconds. A torrent with its own limit doesn't draw from it, so a torrent
 *                       can be capped while this is false.
 * @param ratio          lifetime uploaded over verified downloaded, across every torrent - the
 *                       same definition as each torrent's own ratio (design_docs/0064). Null
 *                       when nothing has been downloaded yet.
 */
public record TransferView(
        long downloadRateBytesPerSec,
        long uploadRateBytesPerSec,
        long downloadLimitBytesPerSec,
        String downloadLimitSource,
        boolean downloadCapped,
        long uploadLimitBytesPerSec,
        String uploadLimitSource,
        boolean uploadCapped,
        long lifetimeDownloadedBytes,
        long lifetimeUploadedBytes,
        Double ratio
) {
    public static TransferView from(TorrentEngine.GlobalTransfer transfer, Collection<TorrentSession> sessions) {
        long downloaded = 0;
        long uploaded = 0;
        for (TorrentSession session : sessions) {
            downloaded += session.bytesDownloaded();
            uploaded += session.lifetimeUploadedBytes();
        }
        return new TransferView(
                transfer.download().bytesPerSec(),
                transfer.upload().bytesPerSec(),
                transfer.downloadLimit().bytesPerSec(),
                transfer.downloadLimit().source().name(),
                transfer.downloadCapped(),
                transfer.uploadLimit().bytesPerSec(),
                transfer.uploadLimit().source().name(),
                transfer.uploadCapped(),
                downloaded,
                uploaded,
                downloaded > 0 ? (double) uploaded / downloaded : null);
    }
}
