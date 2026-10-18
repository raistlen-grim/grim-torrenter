package com.grimtorrenter.engine.piece;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * Highest file-priority tier first (HIGH, then MEDIUM, then LOW), lowest incomplete piece index
 * the peer has within a tier; never an unwanted (all-SKIP) piece. With every file MEDIUM this is
 * exactly Phase 1's original "lowest incomplete piece index" order. See design_docs/0009 and
 * design_docs/0075.
 */
public final class SequentialPieceSelectionStrategy implements PieceSelectionStrategy {

    /** One pass over the incomplete pieces, in index order: the first HIGH piece the peer has
     * is returned at once; otherwise the first MEDIUM, then the first LOW, found on the way.
     * Completed pieces are skipped in bulk (PieceManager.nextIncompletePiece()). */
    @Override
    public OptionalInt selectNextPiece(PieceManager manager, IntPredicate peerHasPiece) {
        int[] firstByTier = {-1, -1, -1, -1};
        int highest = FilePriority.HIGH.tier();
        for (int i = manager.nextIncompletePiece(0); i < manager.pieceCount(); i = manager.nextIncompletePiece(i + 1)) {
            int tier = manager.tierUnderLock(i);
            if (tier == 0 || firstByTier[tier] >= 0 || !peerHasPiece.test(i)) {
                continue;
            }
            if (tier == highest) {
                return OptionalInt.of(i);
            }
            firstByTier[tier] = i;
        }
        for (int tier = highest; tier >= FilePriority.LOW.tier(); tier--) {
            if (firstByTier[tier] >= 0) {
                return OptionalInt.of(firstByTier[tier]);
            }
        }
        return OptionalInt.empty();
    }
}
