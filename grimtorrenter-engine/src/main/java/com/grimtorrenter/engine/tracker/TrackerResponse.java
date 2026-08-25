package com.grimtorrenter.engine.tracker;

import java.util.List;

/** minInterval, trackerId, and warning are nullable - not every tracker sends them. */
public record TrackerResponse(
        long interval,
        Long minInterval,
        int complete,
        int incomplete,
        List<PeerAddress> peers,
        String trackerId,
        String warning
) {
    public TrackerResponse {
        peers = List.copyOf(peers);
    }
}
