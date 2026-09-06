package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedTrackerClientTest {

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

    @Test
    void startsUnknownBeforeAnyAnnounce() {
        TrackedTrackerClient client = new TrackedTrackerClient(
                "http://tracker.example/announce", 0, request -> {
                    throw new AssertionError("should not be called");
                });

        List<TrackerStatus> statuses = client.statuses();

        assertEquals(1, statuses.size());
        TrackerStatus status = statuses.get(0);
        assertEquals("http://tracker.example/announce", status.url());
        assertEquals(0, status.tier());
        assertEquals(TrackerStatus.State.UNKNOWN, status.state());
        assertNull(status.lastAnnouncedAt());
        assertNull(status.nextAnnounceAt());
        assertNull(status.lastError());
        assertNull(status.seeders());
        assertNull(status.leechers());
    }

    @Test
    void recordsWorkingStatusAndComputesNextAnnounceFromTheResponsesOwnInterval() {
        TrackerResponse response = new TrackerResponse(1800, null, 12, 3, List.of(), null, null);
        TrackedTrackerClient client = new TrackedTrackerClient(
                "http://tracker.example/announce", 1, request -> response);

        TrackerResponse actual = client.announce(fakeRequest());

        assertEquals(response, actual);
        TrackerStatus status = client.statuses().get(0);
        assertEquals(TrackerStatus.State.WORKING, status.state());
        assertNotNull(status.lastAnnouncedAt());
        assertEquals(status.lastAnnouncedAt().plusSeconds(1800), status.nextAnnounceAt());
        assertEquals(12, status.seeders());
        assertEquals(3, status.leechers());
        assertNull(status.lastError());
    }

    @Test
    void recordsErrorStatusAndRethrowsWithoutSwallowingTheFailure() {
        TrackerException failure = new TrackerException("simulated failure");
        TrackedTrackerClient client = new TrackedTrackerClient(
                "http://tracker.example/announce", 0, request -> {
                    throw failure;
                });

        TrackerException thrown = assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));

        assertEquals(failure, thrown);
        TrackerStatus status = client.statuses().get(0);
        assertEquals(TrackerStatus.State.ERROR, status.state());
        assertNotNull(status.lastAnnouncedAt());
        assertNull(status.nextAnnounceAt());
        assertEquals("simulated failure", status.lastError());
    }

    /** See the "keep last-known values" call in design_docs/0031 - a subsequent failure
     * shouldn't blank out a still-informative seeders/leechers count from the last success. */
    @Test
    void failureAfterASuccessKeepsTheLastKnownSeedersAndLeechers() {
        TrackerResponse success = new TrackerResponse(1800, null, 12, 3, List.of(), null, null);
        boolean[] shouldFail = {false};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0, request -> {
            if (shouldFail[0]) {
                throw new TrackerException("now failing");
            }
            return success;
        });

        client.announce(fakeRequest());
        shouldFail[0] = true;
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));

        TrackerStatus status = client.statuses().get(0);
        assertEquals(TrackerStatus.State.ERROR, status.state());
        assertEquals(12, status.seeders());
        assertEquals(3, status.leechers());
        assertEquals("now failing", status.lastError());
    }

    private record Notification(String kind, String url, String lastError) {
    }

    private static TrackerStatusListener recordingListener(List<Notification> notifications) {
        return new TrackerStatusListener() {
            @Override
            public void onTrackerUnreachable(String url, String lastError) {
                notifications.add(new Notification("unreachable", url, lastError));
            }

            @Override
            public void onTrackerRecovered(String url) {
                notifications.add(new Notification("recovered", url, null));
            }
        };
    }

    /** See design_docs/0055's own TRACKER_UNREACHABLE addendum: a single failed announce cycle
     * is tolerated (a tracker can fail one reannounce and recover the next) - only two
     * consecutive failures with no intervening success are reported. */
    @Test
    void doesNotReportUnreachableAfterOnlyOneFailure() {
        List<Notification> notifications = new ArrayList<>();
        TrackerException failure = new TrackerException("simulated failure");
        TrackedTrackerClient client = new TrackedTrackerClient(
                "http://tracker.example/announce", 0, request -> {
                    throw failure;
                }, recordingListener(notifications));

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));

        assertTrue(notifications.isEmpty());
    }

    @Test
    void reportsUnreachableOnlyAfterTwoConsecutiveFailures() {
        List<Notification> notifications = new ArrayList<>();
        TrackerException failure = new TrackerException("simulated failure");
        TrackedTrackerClient client = new TrackedTrackerClient(
                "http://tracker.example/announce", 0, request -> {
                    throw failure;
                }, recordingListener(notifications));

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));

        assertEquals(1, notifications.size());
        assertEquals(new Notification("unreachable", "http://tracker.example/announce", "simulated failure"),
                notifications.get(0));

        // A third consecutive failure doesn't re-report - already reported, stays reported.
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        assertEquals(1, notifications.size());
    }

    @Test
    void reportsRecoveredOnTheFirstSuccessAfterUnreachableWasReported() {
        List<Notification> notifications = new ArrayList<>();
        TrackerResponse success = new TrackerResponse(1800, null, 12, 3, List.of(), null, null);
        boolean[] shouldFail = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0, request -> {
            if (shouldFail[0]) {
                throw new TrackerException("simulated failure");
            }
            return success;
        }, recordingListener(notifications));

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        assertEquals(1, notifications.size());

        shouldFail[0] = false;
        client.announce(fakeRequest());

        assertEquals(2, notifications.size());
        assertEquals(new Notification("recovered", "http://tracker.example/announce", null), notifications.get(1));
    }

    /** A success after only one failure (unreachable never reported) has nothing to "recover"
     * from in the user's eyes, and shouldn't fire a spurious RECOVERED event. */
    @Test
    void doesNotReportRecoveredWhenUnreachableWasNeverReported() {
        List<Notification> notifications = new ArrayList<>();
        TrackerResponse success = new TrackerResponse(1800, null, 12, 3, List.of(), null, null);
        boolean[] shouldFail = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0, request -> {
            if (shouldFail[0]) {
                throw new TrackerException("simulated failure");
            }
            return success;
        }, recordingListener(notifications));

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        shouldFail[0] = false;
        client.announce(fakeRequest());

        assertTrue(notifications.isEmpty());
    }
}
