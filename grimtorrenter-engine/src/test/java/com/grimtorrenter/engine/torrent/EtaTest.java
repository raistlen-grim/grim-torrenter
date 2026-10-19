package com.grimtorrenter.engine.torrent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** EtaStatus's precedence, through TorrentSession.etaFrom(). See design_docs/0102. */
class EtaTest {

    @Test
    void nothingLeftIsCompleteEvenWhenStopped() {
        assertEquals(EtaStatus.COMPLETE, TorrentSession.etaFrom(0, false, 0, 0, false, 0).status());
    }

    @Test
    void notRunningComesBeforeAnythingAboutPeers() {
        assertEquals(EtaStatus.NOT_RUNNING, TorrentSession.etaFrom(100, false, 0, 5, true, 0).status());
    }

    @Test
    void runningWithNoPeers() {
        assertEquals(EtaStatus.NO_PEERS, TorrentSession.etaFrom(100, true, 0, 0, false, 50).status());
    }

    @Test
    void unavailablePiecesWinOverAMovingRate() {
        Eta eta = TorrentSession.etaFrom(100, true, 3, 12, false, 1_000);

        assertEquals(EtaStatus.UNAVAILABLE, eta.status());
        assertEquals(12, eta.unavailablePieces());
        assertNull(eta.seconds());
    }

    @Test
    void everyPeerChokingUs() {
        assertEquals(EtaStatus.CHOKED, TorrentSession.etaFrom(100, true, 3, 0, true, 1_000).status());
    }

    @Test
    void stalledWhenNothingArrivedInTheLastMinute() {
        assertEquals(EtaStatus.STALLED, TorrentSession.etaFrom(100, true, 3, 0, false, 0).status());
    }

    @Test
    void anEstimateRoundsUp() {
        Eta eta = TorrentSession.etaFrom(1_001, true, 3, 0, false, 100);

        assertEquals(EtaStatus.ESTIMATE, eta.status());
        assertEquals(11L, eta.seconds());
        assertEquals(0, eta.unavailablePieces());
    }
}
