package com.grimtorrenter.engine.piece;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * peerHasPiece is a plain IntPredicate, not a peer.PeerConnection - keeps
 * selection strategies testable and reusable with no dependency on the
 * peer package. See design_docs/0016.
 */
public interface PieceSelectionStrategy {

    OptionalInt selectNextPiece(PieceManager manager, IntPredicate peerHasPiece);
}
