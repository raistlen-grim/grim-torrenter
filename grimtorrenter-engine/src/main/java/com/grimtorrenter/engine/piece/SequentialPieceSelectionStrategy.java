package com.grimtorrenter.engine.piece;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/** Phase 1's selection strategy - lowest incomplete piece index the peer has. See design_docs/0009. */
public final class SequentialPieceSelectionStrategy implements PieceSelectionStrategy {

    @Override
    public OptionalInt selectNextPiece(PieceManager manager, IntPredicate peerHasPiece) {
        for (int i = 0; i < manager.pieceCount(); i++) {
            if (manager.stateOf(i) != PieceState.COMPLETE && peerHasPiece.test(i)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }
}
