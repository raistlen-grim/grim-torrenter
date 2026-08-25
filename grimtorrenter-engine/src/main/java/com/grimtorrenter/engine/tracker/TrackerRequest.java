package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.metainfo.InfoHash;

/** event is null for a periodic re-announce (only set for started/stopped/completed). */
public record TrackerRequest(
        InfoHash infoHash,
        PeerId peerId,
        int port,
        long uploaded,
        long downloaded,
        long left,
        TrackerEvent event,
        int numWant
) {
}
