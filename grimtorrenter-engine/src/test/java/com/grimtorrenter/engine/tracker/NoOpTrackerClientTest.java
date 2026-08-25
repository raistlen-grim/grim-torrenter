package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpTrackerClientTest {

    @Test
    void announceAlwaysSucceedsWithNoPeersAndABoundedInterval() {
        InfoHash infoHash = InfoHash.of(HexFormat.of().parseHex("00".repeat(20)));
        PeerId peerId = PeerId.of(HexFormat.of().parseHex("11".repeat(20)));
        TrackerRequest request = new TrackerRequest(
                infoHash, peerId, 6881, 0, 0, 0, TrackerEvent.STARTED, 50);

        TrackerResponse response = new NoOpTrackerClient().announce(request);

        assertTrue(response.peers().isEmpty());
        // Positive, and comfortably clear of Long.MAX_VALUE - the scheduler that
        // eventually uses this converts it to nanoseconds, which does overflow at
        // Long.MAX_VALUE itself (see NoOpTrackerClient's own Javadoc).
        assertTrue(response.interval() > 0 && response.interval() < Long.MAX_VALUE / 2);
    }
}
