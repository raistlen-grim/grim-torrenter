package com.grimtorrenter.engine.torrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.grimtorrenter.engine.tracker.PeerAddress;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailedAddressesTest {

    private static final long INITIAL = 1_000;
    private static final long MAX = 8_000;
    private final FailedAddresses failed = new FailedAddresses(INITIAL, MAX);
    private final PeerAddress peerA = new PeerAddress(InetAddress.getLoopbackAddress(), 1001);
    private final PeerAddress peerB = new PeerAddress(InetAddress.getLoopbackAddress(), 1002);

    @Test
    void anAddressThatNeverFailedIsNeitherContainedNorBackingOff() {
        assertFalse(failed.contains(peerA));
        assertFalse(failed.isBackingOff(peerA, 0));
        assertEquals(List.of(), failed.dueForRetry(Long.MAX_VALUE - 1));
    }

    @Test
    void aFailedAddressBacksOffThenBecomesDueForRetry() {
        failed.recordFailure(peerA, 0);

        assertTrue(failed.contains(peerA));
        assertTrue(failed.isBackingOff(peerA, INITIAL - 1));
        assertEquals(List.of(), failed.dueForRetry(INITIAL - 1));

        assertFalse(failed.isBackingOff(peerA, INITIAL));
        assertEquals(List.of(peerA), failed.dueForRetry(INITIAL));
        // Still "has failed" once due - fillConnections() offers it only after fresh candidates.
        assertTrue(failed.contains(peerA));
    }

    @Test
    void theBackoffDoublesWithEachFailureUpToTheMaximum() {
        failed.recordFailure(peerA, 0);
        failed.recordFailure(peerA, 0);
        assertTrue(failed.isBackingOff(peerA, 2 * INITIAL - 1));
        assertFalse(failed.isBackingOff(peerA, 2 * INITIAL));

        failed.recordFailure(peerA, 0);
        assertFalse(failed.isBackingOff(peerA, 4 * INITIAL));

        for (int i = 0; i < 100; i++) {
            failed.recordFailure(peerA, 0);
        }
        assertTrue(failed.isBackingOff(peerA, MAX - 1));
        assertFalse(failed.isBackingOff(peerA, MAX));
    }

    @Test
    void aSuccessfulConnectionClearsTheFailuresSoTheNextBackoffStartsOver() {
        failed.recordFailure(peerA, 0);
        failed.recordFailure(peerA, 0);
        failed.clear(peerA);
        assertFalse(failed.contains(peerA));

        failed.recordFailure(peerA, 0);
        assertFalse(failed.isBackingOff(peerA, INITIAL));
    }

    @Test
    void dueAddressesComeLongestWaitingFirst() {
        failed.recordFailure(peerB, 0);
        failed.recordFailure(peerA, 500);

        assertEquals(List.of(peerB, peerA), failed.dueForRetry(10_000));
        assertEquals(List.of(peerB), failed.dueForRetry(INITIAL));
    }

    @Test
    void anExcludedAddressIsNeverDueAndCannotBeBroughtBack() {
        failed.exclude(peerA);
        failed.recordFailure(peerA, 0);
        failed.clear(peerA);

        assertTrue(failed.contains(peerA));
        assertTrue(failed.isBackingOff(peerA, Long.MAX_VALUE - 1));
        assertEquals(List.of(), failed.dueForRetry(Long.MAX_VALUE - 1));
        assertEquals(1, failed.size());
    }
}
