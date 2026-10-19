package com.grimtorrenter.app;

import com.grimtorrenter.engine.stats.HistoryResolution;
import com.grimtorrenter.engine.stats.TransferHistory;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.Locale;

/**
 * A window of transfer history, oldest first: the average bytes/sec over each interval, null
 * where nothing was recorded (the app wasn't running, or the torrent didn't exist yet). The last
 * value is the interval in progress. GET /api/stats/history and
 * GET /api/torrents/{infoHash}/history. See design_docs/0102.
 *
 * @param endEpochMillis where the window ends - the end of the newest interval
 */
public record HistoryView(
        String resolution,
        long intervalSeconds,
        long endEpochMillis,
        List<Long> download,
        List<Long> upload
) {
    public static HistoryView from(HistoryResolution resolution, TransferHistory history) {
        return new HistoryView(resolution.name().toLowerCase(Locale.ROOT), history.intervalMillis() / 1000,
                history.endEpochMillis(), history.download(), history.upload());
    }

    /** "second", "minute" or "hour" (any case); anything else, or nothing, is a 400. */
    static HistoryResolution parseResolution(String value) {
        if (value != null) {
            for (HistoryResolution resolution : HistoryResolution.values()) {
                if (resolution.name().equalsIgnoreCase(value)) {
                    return resolution;
                }
            }
        }
        throw new BadRequestException("resolution must be second, minute or hour");
    }
}
