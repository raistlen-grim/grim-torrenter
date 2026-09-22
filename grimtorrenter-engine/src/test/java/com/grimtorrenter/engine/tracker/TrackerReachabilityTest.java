package com.grimtorrenter.engine.tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerReachabilityTest {

    private static final String URL = "http://tracker.example/announce";

    @Test
    void onlyTheFirstTorrentToReportATrackerIsNewsworthy() {
        TrackerReachability reachability = new TrackerReachability();

        assertTrue(reachability.markUnreachable(URL, "aa"));
        assertFalse(reachability.markUnreachable(URL, "bb"));
        assertFalse(reachability.markUnreachable(URL, "aa"));
    }

    @Test
    void recoveryFiresOnceAndClearsEveryTorrentsVote() {
        TrackerReachability reachability = new TrackerReachability();
        reachability.markUnreachable(URL, "aa");
        reachability.markUnreachable(URL, "bb");

        assertTrue(reachability.markRecovered(URL));
        assertFalse(reachability.markRecovered(URL));
        // Back to a clean slate: the next outage is newsworthy again.
        assertTrue(reachability.markUnreachable(URL, "bb"));
    }

    @Test
    void recoveryOfATrackerNeverReportedUnreachableIsNotNewsworthy() {
        assertFalse(new TrackerReachability().markRecovered(URL));
    }

    @Test
    void forgettingTheLastVoterDropsTheEntrySilently() {
        TrackerReachability reachability = new TrackerReachability();
        reachability.markUnreachable(URL, "aa");
        reachability.markUnreachable(URL, "bb");

        reachability.forget("aa");
        assertFalse(reachability.markUnreachable(URL, "cc"));

        reachability.forget("bb");
        reachability.forget("cc");
        assertFalse(reachability.markRecovered(URL));
        assertTrue(reachability.markUnreachable(URL, "dd"));
    }
}
