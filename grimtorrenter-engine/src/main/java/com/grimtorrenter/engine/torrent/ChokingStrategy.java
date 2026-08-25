package com.grimtorrenter.engine.torrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure selection logic, decoupled from PeerConnection (generic over T) so
 * it's unit-testable without real sockets - same rationale as
 * PieceSelectionStrategy's use of IntPredicate (design_docs/0016).
 *
 * <p>Deliberately simple: no upload-rate tracking or reciprocity (real
 * BEP 3 tit-for-tat) - just a capped rotation across whoever's currently
 * interested, so no peer is starved forever once there are more interested
 * peers than unchoke slots. Confirmed with the user as a reasonable
 * trade-off given this project's needs - see design_docs/0025.
 */
final class ChokingStrategy {

    private ChokingStrategy() {
    }

    static <T> Set<T> selectToUnchoke(List<T> interestedPeers, int maxUnchoked, int rotation) {
        if (interestedPeers.size() <= maxUnchoked) {
            return new HashSet<>(interestedPeers);
        }
        List<T> rotated = new ArrayList<>(interestedPeers);
        Collections.rotate(rotated, rotation);
        return new HashSet<>(rotated.subList(0, maxUnchoked));
    }
}
