package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    private static List<PeerAddress> fakePeers(int count) {
        try {
            List<PeerAddress> peers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                peers.add(new PeerAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, (byte) i}), 6881 + i));
            }
            return peers;
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
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
        TrackerResponse response = new TrackerResponse(1800, null, 12, 3, fakePeers(7), null, null);
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
        assertEquals(7, status.peers());
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
     * shouldn't blank out a still-informative seeders/leechers/peers count from the last
     * success. See design_docs/0067 for peers joining seeders/leechers here. */
    @Test
    void failureAfterASuccessKeepsTheLastKnownSeedersAndLeechers() {
        TrackerResponse success = new TrackerResponse(1800, null, 12, 3, fakePeers(4), null, null);
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
        assertEquals(4, status.peers());
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

    /** Mutable clock so a test can cross STABLE_WINDOW without sleeping. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-21T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Duration JUST_OVER = TrackedTrackerClient.STABLE_WINDOW.plusSeconds(1);
    private static final TrackerResponse SUCCESS = new TrackerResponse(1800, null, 12, 3, List.of(), null, null);

    /** A togglable delegate: fails while failing[0] is true, succeeds otherwise. */
    private static TrackerClient togglable(boolean[] failing) {
        return request -> {
            if (failing[0]) {
                throw new TrackerException("simulated failure");
            }
            return SUCCESS;
        };
    }

    /** See design_docs/0055's 2026-09-21 revision: failures inside STABLE_WINDOW - however many
     * - are tolerated. */
    @Test
    void doesNotReportUnreachableWhileFailuresAreInsideTheStableWindow() {
        List<Notification> notifications = new ArrayList<>();
        TestClock clock = new TestClock();
        boolean[] failing = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0,
                togglable(failing), recordingListener(notifications), clock);

        for (int i = 0; i < 5; i++) {
            assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
            clock.advance(Duration.ofMinutes(5));
        }

        assertTrue(notifications.isEmpty());
    }

    @Test
    void reportsUnreachableOnceFailuresSpanTheStableWindow() {
        List<Notification> notifications = new ArrayList<>();
        TestClock clock = new TestClock();
        boolean[] failing = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0,
                togglable(failing), recordingListener(notifications), clock);

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        clock.advance(JUST_OVER);
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));

        assertEquals(List.of(new Notification("unreachable", "http://tracker.example/announce",
                "simulated failure")), notifications);

        // Already reported - further failures don't re-report.
        clock.advance(JUST_OVER);
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        assertEquals(1, notifications.size());
    }

    /** The flapping case that motivated the revision: fail/fail/succeed repeated forever must
     * never report anything, since no failure streak ever spans the window. */
    @Test
    void aFlappingTrackerReportsNothing() {
        List<Notification> notifications = new ArrayList<>();
        TestClock clock = new TestClock();
        boolean[] failing = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0,
                togglable(failing), recordingListener(notifications), clock);

        for (int cycle = 0; cycle < 20; cycle++) {
            failing[0] = true;
            assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
            clock.advance(Duration.ofMinutes(10));
            assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
            clock.advance(Duration.ofMinutes(10));
            failing[0] = false;
            client.announce(fakeRequest());
            clock.advance(Duration.ofMinutes(10));
        }

        assertTrue(notifications.isEmpty());
    }

    @Test
    void reportsRecoveredOnlyAfterSuccessesSpanTheStableWindow() {
        List<Notification> notifications = new ArrayList<>();
        TestClock clock = new TestClock();
        boolean[] failing = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0,
                togglable(failing), recordingListener(notifications), clock);
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        clock.advance(JUST_OVER);
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        assertEquals(1, notifications.size());

        failing[0] = false;
        client.announce(fakeRequest());
        assertEquals(1, notifications.size());

        clock.advance(JUST_OVER);
        client.announce(fakeRequest());

        assertEquals(2, notifications.size());
        assertEquals(new Notification("recovered", "http://tracker.example/announce", null), notifications.get(1));
    }

    /** A failure mid-recovery restarts the success streak - no RECOVERED for a tracker that's
     * still flapping. */
    @Test
    void aFailureDuringRecoveryRestartsTheStableWindow() {
        List<Notification> notifications = new ArrayList<>();
        TestClock clock = new TestClock();
        boolean[] failing = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0,
                togglable(failing), recordingListener(notifications), clock);
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        clock.advance(JUST_OVER);
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));

        failing[0] = false;
        client.announce(fakeRequest());
        clock.advance(Duration.ofMinutes(20));
        failing[0] = true;
        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        failing[0] = false;
        clock.advance(Duration.ofMinutes(20));
        client.announce(fakeRequest());
        clock.advance(Duration.ofMinutes(20));
        client.announce(fakeRequest());

        // The success streak restarted after the failure and has only run 20 minutes - no
        // recovery yet, even though 60 minutes have passed since the first success.
        assertEquals(1, notifications.size());
    }

    /** Never reported unreachable, so there's nothing to "recover" from. */
    @Test
    void doesNotReportRecoveredWhenUnreachableWasNeverReported() {
        List<Notification> notifications = new ArrayList<>();
        TestClock clock = new TestClock();
        boolean[] failing = {true};
        TrackedTrackerClient client = new TrackedTrackerClient("http://tracker.example/announce", 0,
                togglable(failing), recordingListener(notifications), clock);

        assertThrows(TrackerException.class, () -> client.announce(fakeRequest()));
        failing[0] = false;
        client.announce(fakeRequest());
        clock.advance(JUST_OVER);
        client.announce(fakeRequest());

        assertTrue(notifications.isEmpty());
    }
}
