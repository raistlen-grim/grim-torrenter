package com.grimtorrenter.engine.torrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InFlightBlocksTest {

    private static final long TIMEOUT = 1_000;
    private final InFlightBlocks blocks = new InFlightBlocks(TIMEOUT);
    private final Object peerA = new Object();
    private final Object peerB = new Object();
    private final Object peerC = new Object();

    @Test
    void firstClaimWinsAndSecondPeerCannotTakeALiveClaim() {
        assertTrue(blocks.tryClaim(0, 0, peerA, 0));
        assertFalse(blocks.tryClaim(0, 0, peerB, 500));
        assertFalse(blocks.isFreeFor(0, 0, peerB, 500));
        assertEquals(1, blocks.size());
    }

    @Test
    void differentBlocksClaimIndependently() {
        assertTrue(blocks.tryClaim(0, 0, peerA, 0));
        assertTrue(blocks.tryClaim(0, 16384, peerB, 0));
        assertTrue(blocks.tryClaim(1, 0, peerC, 0));
        assertEquals(3, blocks.size());
    }

    @Test
    void ownerCannotReclaimItsOwnBlockEvenAfterExpiry() {
        assertTrue(blocks.tryClaim(0, 0, peerA, 0));
        assertFalse(blocks.tryClaim(0, 0, peerA, TIMEOUT + 1));
        assertFalse(blocks.isFreeFor(0, 0, peerA, TIMEOUT + 1));
    }

    @Test
    void anotherPeerCanTakeOverAfterTheTimeout() {
        assertTrue(blocks.tryClaim(0, 0, peerA, 0));
        assertFalse(blocks.tryClaim(0, 0, peerB, TIMEOUT));
        assertTrue(blocks.isFreeFor(0, 0, peerB, TIMEOUT + 1));
        assertTrue(blocks.tryClaim(0, 0, peerB, TIMEOUT + 1));
        // The takeover replaced the old holder, so it no longer counts as one.
        assertEquals(List.of(peerB), blocks.release(0, 0));
    }

    @Test
    void releaseReturnsEveryHolderAndForgetsTheBlock() {
        blocks.tryClaim(0, 0, peerA, 0);
        blocks.tryClaimDuplicate(0, 0, peerB, 0, 3);

        List<Object> holders = blocks.release(0, 0);

        assertEquals(2, holders.size());
        assertTrue(holders.contains(peerA));
        assertTrue(holders.contains(peerB));
        assertEquals(0, blocks.size());
        assertTrue(blocks.isFreeFor(0, 0, peerC, 0));
        assertTrue(blocks.release(0, 0).isEmpty());
    }

    @Test
    void duplicateRequiresAnExistingClaim() {
        assertFalse(blocks.canDuplicate(0, 0, peerB, 3));
        assertFalse(blocks.tryClaimDuplicate(0, 0, peerB, 0, 3));
        assertEquals(0, blocks.size());
    }

    @Test
    void duplicateIsCappedAndNeverTheSamePeerTwice() {
        blocks.tryClaim(0, 0, peerA, 0);
        assertFalse(blocks.canDuplicate(0, 0, peerA, 3));
        assertFalse(blocks.tryClaimDuplicate(0, 0, peerA, 0, 3));

        assertTrue(blocks.tryClaimDuplicate(0, 0, peerB, 0, 3));
        assertFalse(blocks.tryClaimDuplicate(0, 0, peerB, 0, 3));
        assertTrue(blocks.tryClaimDuplicate(0, 0, peerC, 0, 3));

        Object peerD = new Object();
        assertFalse(blocks.canDuplicate(0, 0, peerD, 3));
        assertFalse(blocks.tryClaimDuplicate(0, 0, peerD, 0, 3));
        assertEquals(3, blocks.release(0, 0).size());
    }

    @Test
    void duplicateHolderDoesNotMakeABlockClaimableWhileAnyHolderIsLive() {
        blocks.tryClaim(0, 0, peerA, 0);
        blocks.tryClaimDuplicate(0, 0, peerB, 900, 3);

        // peerA's claim has expired but peerB's (added later) hasn't.
        assertFalse(blocks.tryClaim(0, 0, peerC, TIMEOUT + 1));
        assertTrue(blocks.tryClaim(0, 0, peerC, 900 + TIMEOUT + 1));
    }

    @Test
    void releaseAllDropsOnlyThatOwnersClaims() {
        blocks.tryClaim(0, 0, peerA, 0);
        blocks.tryClaim(0, 16384, peerA, 0);
        blocks.tryClaim(1, 0, peerB, 0);
        blocks.tryClaimDuplicate(1, 0, peerA, 0, 3);

        blocks.releaseAll(peerA);

        assertEquals(1, blocks.size());
        assertTrue(blocks.isFreeFor(0, 0, peerB, 0));
        assertTrue(blocks.isFreeFor(0, 16384, peerB, 0));
        // Block (1,0) survives with peerB as its only remaining holder.
        assertEquals(List.of(peerB), blocks.release(1, 0));
    }
}
