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

    @Override
    public OptionalInt selectNextPiece(PieceManager manager, IntPredicate peerHasPiece) {
        for (int tier = FilePriority.HIGH.tier(); tier >= FilePriority.LOW.tier(); tier--) {
            for (int i = 0; i < manager.pieceCount(); i++) {
                if (manager.priorityTier(i) == tier
                        && manager.stateOf(i) != PieceState.COMPLETE
                        && peerHasPiece.test(i)) {
                    return OptionalInt.of(i);
                }
            }
        }
        return OptionalInt.empty();
    }
}
