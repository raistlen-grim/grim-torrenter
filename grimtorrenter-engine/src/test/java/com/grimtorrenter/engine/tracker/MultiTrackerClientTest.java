package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static TrackerResponse fakeResponse() {
        return new TrackerResponse(1800, null, 0, 0, List.of(), null, null);
    }

    private static TrackerClient failing() {
        return request -> {
            throw new TrackerException("simulated failure");
        };
    }

    private static TrackerClient succeeding(AtomicInteger callCount) {
        return request -> {
            callCount.incrementAndGet();
            return fakeResponse();
        };
    }

    @Test
    void usesFirstTrackerInFirstTierWhenItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(succeeding(calls), failing())));

        TrackerResponse response = client.announce(fakeRequest());

        assertEquals(fakeResponse(), response);
        assertEquals(1, calls.get());
    }

    @Test
    void fallsBackWithinATierWhenFirstTrackerFails() {
        AtomicInteger calls = new AtomicInteger();
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(failing(), succeeding(calls))));

        client.announce(fakeRequest());

        assertEquals(1, calls.get());
    }

    @Test
    void fallsBackToNextTierWhenEntireFirstTierFails() {
        AtomicInteger calls = new AtomicInteger();
        MultiTrackerClient client = new MultiTrackerClient(List.of(
                List.of(failing(), failing()),
                List.of(succeeding(calls))));

        client.announce(fakeRequest());

        assertEquals(1, calls.get());
    }

    @Test
    void throwsLastFailureWhenAllTrackersFail() {
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(failing()), List.of(failing())));

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
    }

    @Test
    void rejectsAllEmptyTiers() {
        assertThrows(IllegalArgumentException.class, () -> new MultiTrackerClient(List.of(List.of(), List.of())));
    }

    /** statuses() must aggregate every wrapped tracker, including the second tier's -
     * MultiTrackerClient's short-circuiting announce() never even calls it here, but its
     * (still-UNKNOWN) status must still be reported, not silently dropped. See
     * design_docs/0031. */
    @Test
    void statusesAggregatesEveryWrappedTrackerAcrossTiers() {
        TrackedTrackerClient primary = new TrackedTrackerClient("http://a/announce", 0, succeeding(new AtomicInteger()));
        TrackedTrackerClient backup = new TrackedTrackerClient("http://b/announce", 1, succeeding(new AtomicInteger()));
        MultiTrackerClient client = new MultiTrackerClient(List.of(List.of(primary), List.of(backup)));

        client.announce(fakeRequest());

        List<TrackerStatus> statuses = client.statuses();
        assertEquals(2, statuses.size());
        assertEquals("http://a/announce", statuses.get(0).url());
        assertEquals(TrackerStatus.State.WORKING, statuses.get(0).state());
        assertEquals("http://b/announce", statuses.get(1).url());
        assertEquals(TrackerStatus.State.UNKNOWN, statuses.get(1).state());
    }
}
