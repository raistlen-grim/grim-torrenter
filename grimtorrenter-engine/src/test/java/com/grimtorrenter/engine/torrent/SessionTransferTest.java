package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.stats.TransferDelta;
import com.grimtorrenter.engine.stats.TransferRate;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SessionTransferTest {

    private static final long SECOND = 1_000_000_000L;

    /** Stands in for a PeerConnection: two cumulative counters. */
    private static final class FakeConnection {
        long downloaded;
        long uploaded;
    }

    private static SessionTransfer<FakeConnection> newTransfer() {
        return new SessionTransfer<>(c -> c.downloaded, c -> c.uploaded);
    }

    @Test
    void theTorrentIsTheSumOfItsPeers() {
        SessionTransfer<FakeConnection> transfer = newTransfer();
        FakeConnection a = new FakeConnection();
        FakeConnection b = new FakeConnection();
        Set<FakeConnection> live = Set.of(a, b);
        transfer.sample(live, true, SECOND, 0);

        a.downloaded += 300;
        b.downloaded += 200;
        b.uploaded += 50;
        TransferDelta delta = transfer.sample(live, true, SECOND, 1_000);

        assertEquals(new TransferDelta(500, 50), delta);
        assertEquals(250, transfer.download().bytesPerSec()); // (0 + 500) / 2 samples
        assertEquals(300, transfer.peerDownload(a).samples()[1]);
        assertEquals(200, transfer.peerDownload(b).samples()[1]);
        assertEquals(50, transfer.peerUpload(b).samples()[1]);
    }

    @Test
    void aConnectionThatClosedSinceTheLastTickIsCountedOnceThenDropped() {
        SessionTransfer<FakeConnection> transfer = newTransfer();
        FakeConnection leaving = new FakeConnection();
        Set<FakeConnection> live = new HashSet<>(Set.of(leaving));
        transfer.sample(live, true, SECOND, 0);

        leaving.downloaded += 400;
        live.remove(leaving);

        assertEquals(new TransferDelta(400, 0), transfer.sample(live, true, SECOND, 1_000));
        assertEquals(new TransferDelta(0, 0), transfer.sample(live, true, SECOND, 2_000));
        assertSame(TransferRate.IDLE, transfer.peerDownload(leaving));
    }

    @Test
    void aNewConnectionCountsEverythingItMovedSinceConnecting() {
        SessionTransfer<FakeConnection> transfer = newTransfer();
        FakeConnection joining = new FakeConnection();
        joining.downloaded = 1_000;

        assertEquals(new TransferDelta(1_000, 0), transfer.sample(Set.of(joining), true, SECOND, 0));
    }

    @Test
    void notRunningClearsTheMetersButStillReportsTheBytes() {
        SessionTransfer<FakeConnection> transfer = newTransfer();
        FakeConnection peer = new FakeConnection();
        Set<FakeConnection> live = Set.of(peer);
        peer.downloaded = 800;
        transfer.sample(live, true, SECOND, 0);

        peer.downloaded = 1_000;
        TransferDelta delta = transfer.sample(live, false, SECOND, 1_000);

        assertEquals(new TransferDelta(200, 0), delta);
        assertSame(TransferRate.IDLE, transfer.download());
        assertSame(TransferRate.IDLE, transfer.peerDownload(peer));
    }

    @Test
    void minuteHistoryRecordsEveryTick() {
        SessionTransfer<FakeConnection> transfer = newTransfer();
        FakeConnection peer = new FakeConnection();
        peer.downloaded = 6_000;
        transfer.sample(Set.of(peer), true, SECOND, 60_000);

        assertEquals(6_000L, transfer.minutes().snapshot(60_000).download().get(59));
    }
}
