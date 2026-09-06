package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiTrackerClientTest {

    private static byte[] fill(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    private static TrackerRequest fakeRequest() {
        return new TrackerRequest(InfoHash.of(fill(20, 1)), PeerId.of(fill(20, 2)), 6881, 0, 0, 100, null, 50);
    }

    private static PeerAddress peer(int lastOctet, int port) {
        try {
            return new PeerAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, (byte) lastOctet}), port);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TrackerClient failing() {
        return request -> {
            throw new TrackerException("simulated failure");
        };
    }

    private static TrackerClient succeeding(long interval, List<PeerAddress> peers, AtomicInteger callCount) {
        return request -> {
            callCount.incrementAndGet();
            return new TrackerResponse(interval, null, 0, 0, peers, null, null);
        };
    }

    @Test
    void announcesToEveryTrackerConcurrentlyRegardlessOfTierOrSuccess() {
        AtomicInteger tier0Calls = new AtomicInteger();
        AtomicInteger tier1FailingCalls = new AtomicInteger();
        AtomicInteger tier1SucceedingCalls = new AtomicInteger();
        TrackerClient tier0Tracker = request -> {
            tier0Calls.incrementAndGet();
            return new TrackerResponse(1800, null, 0, 0, List.of(), null, null);
        };
        TrackerClient tier1Failing = request -> {
            tier1FailingCalls.incrementAndGet();
            throw new TrackerException("simulated failure");
        };
        TrackerClient tier1Succeeding = succeeding(1800, List.of(), tier1SucceedingCalls);
        MultiTrackerClient client = new MultiTrackerClient(
                List.of(List.of(tier0Tracker), List.of(tier1Failing, tier1Succeeding)));

        client.announce(fakeRequest());

        // Every tracker reached, not just tier 0's - the old tier-fallback version would have
        // stopped at tier0Tracker and never touched tier 1 at all.
        assertEquals(1, tier0Calls.get());
        assertEquals(1, tier1FailingCalls.get());
        assertEquals(1, tier1SucceedingCalls.get());
    }

    @Test
    void aggregatesDistinctPeersFromEveryTrackerThatSucceeds() {
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(
                succeeding(1800, List.of(peer(1, 1111), peer(2, 2222)), new AtomicInteger()),
                succeeding(1800, List.of(peer(3, 3333)), new AtomicInteger()),
                failing())));

        TrackerResponse response = client.announce(fakeRequest());

        assertEquals(Set.of(peer(1, 1111), peer(2, 2222), peer(3, 3333)), Set.copyOf(response.peers()));
    }

    /** Two trackers reporting the same peer (a real, common case - public trackers often index
     * overlapping swarms for the same popular torrent) must collapse to one entry, not be
     * double-counted or double-connected-to. */
    @Test
    void deduplicatesTheSamePeerReportedByMultipleTrackers() {
        PeerAddress sharedPeer = peer(9, 9999);
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(
                succeeding(1800, List.of(sharedPeer), new AtomicInteger()),
                succeeding(1800, List.of(sharedPeer), new AtomicInteger()))));

        TrackerResponse response = client.announce(fakeRequest());

        assertEquals(List.of(sharedPeer), response.peers());
    }

    /** The shared reannounce cycle stays at least as responsive as the most demanding
     * tracker - using the minimum of every successful tracker's own reported interval, not
     * (say) the first one reached or an average. */
    @Test
    void usesTheMinimumIntervalAmongSuccessfulTrackers() {
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(
                succeeding(3600, List.of(), new AtomicInteger()),
                succeeding(900, List.of(), new AtomicInteger()),
                succeeding(1800, List.of(), new AtomicInteger()))));

        TrackerResponse response = client.announce(fakeRequest());

        assertEquals(900, response.interval());
    }

    /** A failing tracker doesn't prevent the others' peers/interval from being used - the
     * whole point of this class existing at all (design_docs/0022). */
    @Test
    void ignoresFailingTrackersAndUsesWhateverSucceeded() {
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(
                failing(), succeeding(1800, List.of(peer(1, 1111)), new AtomicInteger()), failing())));

        TrackerResponse response = client.announce(fakeRequest());

        assertEquals(List.of(peer(1, 1111)), response.peers());
    }

    @Test
    void throwsWhenEveryTrackerFails() {
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(failing()), List.of(failing())));

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
    }

    @Test
    void rejectsAllEmptyTiers() {
        assertThrows(IllegalArgumentException.class, () -> new MultiTrackerClient(List.of(List.of(), List.of())));
    }

    /** statuses() still aggregates every wrapped tracker across every tier - now that
     * announce() actually reaches all of them every call, both should show WORKING, not just
     * the first-tier one the old tier-fallback version would have left the second at
     * UNKNOWN. */
    @Test
    void statusesAggregatesEveryWrappedTrackerAcrossTiers() {
        TrackedTrackerClient primary = new TrackedTrackerClient(
                "http://a/announce", 0, succeeding(1800, List.of(), new AtomicInteger()));
        TrackedTrackerClient backup = new TrackedTrackerClient(
                "http://b/announce", 1, succeeding(1800, List.of(), new AtomicInteger()));
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(primary), List.of(backup)));

        client.announce(fakeRequest());

        List<TrackerStatus> statuses = client.statuses();
        assertEquals(2, statuses.size());
        assertTrue(statuses.stream().allMatch(s -> s.state() == TrackerStatus.State.WORKING));
        assertEquals(Set.of("http://a/announce", "http://b/announce"),
                statuses.stream().map(TrackerStatus::url).collect(Collectors.toSet()));
    }
}
