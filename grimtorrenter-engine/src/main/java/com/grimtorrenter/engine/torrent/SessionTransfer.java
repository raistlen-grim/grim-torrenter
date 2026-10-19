package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.stats.HistoryRing;
import com.grimtorrenter.engine.stats.TransferDelta;
import com.grimtorrenter.engine.stats.TransferMeter;
import com.grimtorrenter.engine.stats.TransferRate;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * One torrent's transfer meters, its peers' meters, and its minute history, fed by the engine's
 * 1 s sampling tick. See design_docs/0102.
 *
 * <p>Each tick reads every connection's cumulative byte counters and meters the change since the
 * last tick. The torrent's figure is the sum of its peers', so the two always agree. A connection
 * that closed since the last tick is still in the map: its final change is counted once, then it
 * is dropped. Summing per-connection changes, rather than differencing bytesReceived(), matters:
 * TorrentSession folds a closing connection into its accumulators before removing it from the
 * live set, so bytesReceived() briefly counts it twice.
 *
 * <p>Only the tick writes. The map is concurrent because peers() reads per-peer rates from other
 * threads.
 *
 * <p>Generic over the connection type, read through two counter functions, so tests can drive it
 * without real sockets; TorrentSession uses PeerConnection.
 */
final class SessionTransfer<C> {

    private static final int MINUTES_KEPT = 60;

    /** A connection's meters, and its counters as of the last tick (tick thread only). */
    private static final class PeerMeters {
        final TransferMeter download = new TransferMeter();
        final TransferMeter upload = new TransferMeter();
        long lastDownloaded;
        long lastUploaded;
    }

    private final TransferMeter download = new TransferMeter();
    private final TransferMeter upload = new TransferMeter();
    private final HistoryRing minutes = new HistoryRing(60_000, MINUTES_KEPT);
    private final Map<C, PeerMeters> peers = new ConcurrentHashMap<>();
    private final ToLongFunction<C> downloadedBytes;
    private final ToLongFunction<C> uploadedBytes;

    SessionTransfer(ToLongFunction<C> downloadedBytes, ToLongFunction<C> uploadedBytes) {
        this.downloadedBytes = downloadedBytes;
        this.uploadedBytes = uploadedBytes;
    }

    /** Tick thread only. While not running, the meters are cleared every tick, so the torrent
     * reads 0 and a resume starts fresh; bytes still moving (connections closing) count toward
     * the history and the engine's total. */
    TransferDelta sample(Set<C> live, boolean running, long elapsedNanos, long nowMillis) {
        long downloaded = 0;
        long uploaded = 0;
        for (C connection : live) {
            PeerMeters meters = peers.computeIfAbsent(connection, ignored -> new PeerMeters());
            long down = advanceDownload(meters, connection);
            long up = advanceUpload(meters, connection);
            if (running) {
                meters.download.record(down, elapsedNanos, nowMillis);
                meters.upload.record(up, elapsedNanos, nowMillis);
            }
            downloaded += down;
            uploaded += up;
        }
        Iterator<Map.Entry<C, PeerMeters>> departed = peers.entrySet().iterator();
        while (departed.hasNext()) {
            Map.Entry<C, PeerMeters> entry = departed.next();
            if (!live.contains(entry.getKey())) {
                downloaded += advanceDownload(entry.getValue(), entry.getKey());
                uploaded += advanceUpload(entry.getValue(), entry.getKey());
                departed.remove();
            }
        }
        if (running) {
            download.record(downloaded, elapsedNanos, nowMillis);
            upload.record(uploaded, elapsedNanos, nowMillis);
        } else {
            download.clear();
            upload.clear();
            peers.values().forEach(meters -> {
                meters.download.clear();
                meters.upload.clear();
            });
        }
        minutes.add(downloaded, uploaded, elapsedNanos / 1_000_000, nowMillis);
        return new TransferDelta(downloaded, uploaded);
    }

    /** The change in the connection's download counter since the last tick, and moves the mark.
     * A counter never goes backwards; if one did, it counts as 0. */
    private long advanceDownload(PeerMeters meters, C connection) {
        long now = downloadedBytes.applyAsLong(connection);
        long change = Math.max(0, now - meters.lastDownloaded);
        meters.lastDownloaded = now;
        return change;
    }

    private long advanceUpload(PeerMeters meters, C connection) {
        long now = uploadedBytes.applyAsLong(connection);
        long change = Math.max(0, now - meters.lastUploaded);
        meters.lastUploaded = now;
        return change;
    }

    TransferRate download() {
        return download.current();
    }

    TransferRate upload() {
        return upload.current();
    }

    TransferRate peerDownload(C connection) {
        PeerMeters meters = peers.get(connection);
        return meters == null ? TransferRate.IDLE : meters.download.current();
    }

    TransferRate peerUpload(C connection) {
        PeerMeters meters = peers.get(connection);
        return meters == null ? TransferRate.IDLE : meters.upload.current();
    }

    HistoryRing minutes() {
        return minutes;
    }
}
