package com.grimtorrenter.engine.torrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which block requests are currently outstanding on which connections, across every peer of one
 * torrent - so TorrentSession never asks two peers for the same block at once (the default
 * sequential piece order otherwise sends every connected peer after the same first few
 * blocks, and only one peer's worth of them ever counts), except deliberately in endgame mode.
 * See design_docs/0080.
 *
 * <p>Normally a block has exactly one holder. A claim expires after {@code timeoutMillis}, at
 * which point any <em>other</em> connection may take the block over - a peer that accepted a
 * request and then went quiet can't hold a block hostage forever. The owner itself never
 * re-claims its own expired claim (it would just be re-sending a request the peer already has).
 * In endgame mode, tryClaimDuplicate() adds extra holders (up to a cap) to a block that's already
 * in flight.
 *
 * <p>Lock-free (ConcurrentHashMap only, holder lists are immutable and swapped atomically via
 * compute), per design_docs/0007. Bounded by the torrent's block count times the duplicate cap;
 * entries are removed when the block arrives, the holder chokes us, or the holder disconnects.
 */
final class InFlightBlocks {

    private record Key(int index, int begin) {}

    private record Claim(Object owner, long claimedAtMillis) {}

    private final ConcurrentHashMap<Key, List<Claim>> claims = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    InFlightBlocks(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    int size() {
        return claims.size();
    }

    /** Read-only check, for choosing which piece to work on - tryClaim() is what actually
     * reserves a block. Free means: nobody holds it, or every holder has sat on it past the
     * timeout and none of them is owner. */
    boolean isFreeFor(int index, int begin, Object owner, long nowMillis) {
        return takeable(claims.get(new Key(index, begin)), owner, nowMillis);
    }

    /** Atomically reserves the block exclusively for owner (replacing any expired holders).
     * False if someone else holds a live claim, or owner itself already holds it. */
    boolean tryClaim(int index, int begin, Object owner, long nowMillis) {
        boolean[] won = new boolean[1];
        claims.compute(new Key(index, begin), (key, holders) -> {
            if (takeable(holders, owner, nowMillis)) {
                won[0] = true;
                return List.of(new Claim(owner, nowMillis));
            }
            return holders;
        });
        return won[0];
    }

    /** Endgame: whether owner could be added as an extra holder - not already one, and the
     * block has fewer than maxHolders. */
    boolean canDuplicate(int index, int begin, Object owner, int maxHolders) {
        List<Claim> holders = claims.get(new Key(index, begin));
        return holders != null && holders.size() < maxHolders && holders.stream().noneMatch(c -> c.owner() == owner);
    }

    /** Endgame: adds owner as an additional holder of an already-in-flight block. */
    boolean tryClaimDuplicate(int index, int begin, Object owner, long nowMillis, int maxHolders) {
        boolean[] won = new boolean[1];
        claims.computeIfPresent(new Key(index, begin), (key, holders) -> {
            if (holders.size() < maxHolders && holders.stream().noneMatch(c -> c.owner() == owner)) {
                List<Claim> extended = new ArrayList<>(holders);
                extended.add(new Claim(owner, nowMillis));
                won[0] = true;
                return List.copyOf(extended);
            }
            return holders;
        });
        return won[0];
    }

    /** Drops the block's claims (it arrived) and returns everyone who held one, so the caller
     * can cancel the now-redundant requests still outstanding on the others. */
    List<Object> release(int index, int begin) {
        List<Claim> removed = claims.remove(new Key(index, begin));
        return removed == null ? List.of() : removed.stream().map(Claim::owner).toList();
    }

    void releaseAll(Object owner) {
        for (Key key : claims.keySet()) {
            claims.computeIfPresent(key, (k, holders) -> {
                List<Claim> remaining = holders.stream().filter(c -> c.owner() != owner).toList();
                return remaining.isEmpty() ? null : remaining;
            });
        }
    }

    private boolean takeable(List<Claim> holders, Object owner, long nowMillis) {
        if (holders == null) {
            return true;
        }
        for (Claim claim : holders) {
            if (claim.owner() == owner || nowMillis - claim.claimedAtMillis() <= timeoutMillis) {
                return false;
            }
        }
        return true;
    }
}
