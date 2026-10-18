package com.grimtorrenter.engine.piece;

import com.grimtorrenter.engine.Hashing;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.TorrentFile;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;

import java.util.BitSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntPredicate;

/**
 * Piece/block bookkeeping and hash verification. Deliberately
 * storage-agnostic (see design_docs/0016): callers hand it bytes to
 * verify() and read block offsets/lengths from it to drive their own I/O,
 * rather than this class holding a TorrentStorage reference - keeps
 * "piece" and "storage" independent siblings with no dependency between
 * them.
 *
 * <p>Does not track in-flight requests itself - TorrentSession's InFlightBlocks does, so
 * two peers aren't asked for the same block (design_docs/0080, superseding the deferral in
 * design_docs/0016). Endgame mode (duplicate requests at the very end) is
 * now implemented in TorrentSession (its own claims allow extra holders).
 *
 * <p>Bookkeeping methods are guarded by a ReentrantLock, not synchronized - see
 * design_docs/0050. Never actually a virtual-thread pinning risk (nothing blocking ever
 * happens while it's held, just BitSet math), but a synchronized method here read against
 * design_docs/0007's own "avoid synchronized in the hot path" guidance closely enough to be
 * worth removing rather than explaining away. selectNextPiece() calls back into stateOf() on
 * the same instance (via PieceSelectionStrategy), which is exactly the reentrant-acquire
 * case ReentrantLock (like synchronized) supports safely.
 */
public final class PieceManager {

    public static final int BLOCK_SIZE = 16 * 1024;

    private final PieceHashes pieces;
    private final long totalLength;
    private final long nominalPieceLength;
    private final int pieceCount;
    private final PieceSelectionStrategy selectionStrategy;

    private final ReentrantLock lock = new ReentrantLock();
    private final BitSet[] blockReceived;
    private final BitSet completedPieces;
    private final List<TorrentFile> files;
    /** Guarded by lock. See design_docs/0075. */
    private FilePriorities filePriorities;
    private byte[] pieceTier;
    /** Running totals, kept in step with completedPieces and pieceTier so the progress and
     * announce figures cost O(1) rather than a walk over every piece. Guarded by lock;
     * recounted in full only when the priorities change or completion is reset. */
    private long completedBytes;
    private long wantedBytes;
    private long wantedCompletedBytes;
    private int wantedPieces;
    private int wantedCompletedPieces;

    public PieceManager(TorrentMetadata metadata) {
        this(metadata, new SequentialPieceSelectionStrategy());
    }

    public PieceManager(TorrentMetadata metadata, PieceSelectionStrategy selectionStrategy) {
        this(metadata, selectionStrategy, FilePriorities.allMedium(metadata.files().size()));
    }

    public PieceManager(TorrentMetadata metadata, FilePriorities filePriorities) {
        this(metadata, new SequentialPieceSelectionStrategy(), filePriorities);
    }

    public PieceManager(TorrentMetadata metadata, PieceSelectionStrategy selectionStrategy,
                        FilePriorities filePriorities) {
        this.pieces = metadata.pieces();
        this.totalLength = metadata.totalLength();
        this.nominalPieceLength = metadata.pieceLength();
        this.pieceCount = pieces.count();
        this.selectionStrategy = selectionStrategy;
        this.files = metadata.files();
        this.blockReceived = new BitSet[pieceCount];
        for (int i = 0; i < pieceCount; i++) {
            blockReceived[i] = new BitSet(blockCount(i));
        }
        this.completedPieces = new BitSet(pieceCount);
        this.filePriorities = filePriorities;
        this.pieceTier = computePieceTiers(filePriorities);
        recountWanted();
    }

    /**
     * Replaces the per-file priorities (design_docs/0075) and recomputes every piece's tier
     * under the same lock the rest of the bookkeeping uses. Throws if nothing would be
     * wanted - a torrent with every file skipped would "complete" instantly.
     */
    public void setFilePriorities(FilePriorities newPriorities) {
        if (!newPriorities.anyWanted()) {
            throw new IllegalArgumentException("At least one file must be wanted");
        }
        byte[] tiers = computePieceTiers(newPriorities);
        lock.lock();
        try {
            this.filePriorities = newPriorities;
            this.pieceTier = tiers;
            recountWanted();
        } finally {
            lock.unlock();
        }
    }

    public FilePriorities filePriorities() {
        lock.lock();
        try {
            return filePriorities;
        } finally {
            lock.unlock();
        }
    }

    /** 0 = unwanted (every overlapping file is SKIP), 1-3 = LOW/MEDIUM/HIGH, the highest among
     * the non-skipped files this piece overlaps. */
    public int priorityTier(int pieceIndex) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            return pieceTier[pieceIndex];
        } finally {
            lock.unlock();
        }
    }

    public boolean isWanted(int pieceIndex) {
        return priorityTier(pieceIndex) > 0;
    }

    /** Wanted and not yet verified - what "still needed" means everywhere a skipped file
     * shouldn't count (relevance, remaining bytes, completion). */
    public boolean isStillNeeded(int pieceIndex) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            return pieceTier[pieceIndex] > 0 && !completedPieces.get(pieceIndex);
        } finally {
            lock.unlock();
        }
    }

    /** Whether the wanted, not-yet-verified blocks still missing number at most limit - exits
     * early once the running count passes it, so asking "are we down to the last handful?" stays
     * cheap in the middle of a big download. Used to detect endgame (design_docs/0080). */
    public boolean missingWantedBlocksAtMost(int limit) {
        lock.lock();
        try {
            long missing = 0;
            for (int i = 0; i < pieceCount; i++) {
                if (pieceTier[i] > 0 && !completedPieces.get(i)) {
                    missing += blockCount(i) - blockReceived[i].cardinality();
                    if (missing > limit) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isWantedComplete() {
        lock.lock();
        try {
            return wantedCompletedPieces == wantedPieces;
        } finally {
            lock.unlock();
        }
    }

    /** The wanted pieces not yet verified, as a fresh BitSet. */
    public BitSet stillNeededPieces() {
        lock.lock();
        try {
            BitSet needed = new BitSet(pieceCount);
            for (int i = 0; i < pieceCount; i++) {
                if (pieceTier[i] > 0) {
                    needed.set(i);
                }
            }
            needed.andNot(completedPieces);
            return needed;
        } finally {
            lock.unlock();
        }
    }

    public long wantedBytes() {
        lock.lock();
        try {
            return wantedBytes;
        } finally {
            lock.unlock();
        }
    }

    public long wantedBytesCompleted() {
        lock.lock();
        try {
            return wantedCompletedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Bytes in verified pieces, wanted or not. */
    public long completedBytes() {
        lock.lock();
        try {
            return completedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Caller holds lock, or is the constructor. */
    private void recountWanted() {
        completedBytes = 0;
        wantedBytes = 0;
        wantedCompletedBytes = 0;
        wantedPieces = 0;
        wantedCompletedPieces = 0;
        for (int i = 0; i < pieceCount; i++) {
            boolean complete = completedPieces.get(i);
            if (complete) {
                completedBytes += pieceLength(i);
            }
            if (pieceTier[i] > 0) {
                wantedPieces++;
                wantedBytes += pieceLength(i);
                if (complete) {
                    wantedCompletedPieces++;
                    wantedCompletedBytes += pieceLength(i);
                }
            }
        }
    }

    private byte[] computePieceTiers(FilePriorities priorities) {
        byte[] tiers = new byte[pieceCount];
        long fileStart = 0;
        for (int f = 0; f < files.size(); f++) {
            long length = files.get(f).length();
            int tier = priorities.get(f).tier();
            if (length > 0 && tier > 0 && pieceCount > 0) {
                int first = (int) (fileStart / nominalPieceLength);
                int last = (int) Math.min(pieceCount - 1, (fileStart + length - 1) / nominalPieceLength);
                for (int p = first; p <= last; p++) {
                    if (tier > tiers[p]) {
                        tiers[p] = (byte) tier;
                    }
                }
            }
            fileStart += length;
        }
        return tiers;
    }

    public int pieceCount() {
        return pieceCount;
    }

    public long pieceOffset(int pieceIndex) {
        validateIndex(pieceIndex);
        return (long) pieceIndex * nominalPieceLength;
    }

    /** The last piece is shorter than nominalPieceLength unless totalLength divides evenly. */
    public int pieceLength(int pieceIndex) {
        validateIndex(pieceIndex);
        if (pieceIndex == pieceCount - 1) {
            return (int) (totalLength - pieceOffset(pieceIndex));
        }
        return (int) nominalPieceLength;
    }

    public int blockCount(int pieceIndex) {
        return (pieceLength(pieceIndex) + BLOCK_SIZE - 1) / BLOCK_SIZE;
    }

    /** Offset within the piece (the wire protocol's Request "begin" field), not a global torrent offset. */
    public int blockOffsetWithinPiece(int pieceIndex, int blockIndex) {
        return blockIndex * BLOCK_SIZE;
    }

    public int blockLength(int pieceIndex, int blockIndex) {
        int offset = blockOffsetWithinPiece(pieceIndex, blockIndex);
        return Math.min(BLOCK_SIZE, pieceLength(pieceIndex) - offset);
    }

    /** Global torrent-wide byte offset - what TorrentStorage.read/write expect. */
    public long globalOffset(int pieceIndex, int blockIndex) {
        return pieceOffset(pieceIndex) + blockOffsetWithinPiece(pieceIndex, blockIndex);
    }

    public PieceState stateOf(int pieceIndex) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            if (completedPieces.get(pieceIndex)) {
                return PieceState.COMPLETE;
            }
            return blockReceived[pieceIndex].isEmpty() ? PieceState.NEEDED : PieceState.IN_PROGRESS;
        } finally {
            lock.unlock();
        }
    }

    public void markBlockReceived(int pieceIndex, int begin) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            blockReceived[pieceIndex].set(begin / BLOCK_SIZE);
        } finally {
            lock.unlock();
        }
    }

    public boolean isPieceReadyToVerify(int pieceIndex) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            return !completedPieces.get(pieceIndex)
                    && blockReceived[pieceIndex].cardinality() == blockCount(pieceIndex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hashes actualBytes and compares against the expected piece hash. On
     * mismatch, this piece's block bookkeeping is reset so it gets
     * re-requested from scratch.
     */
    public boolean verify(int pieceIndex, byte[] actualBytes) {
        validateIndex(pieceIndex);
        boolean matches = pieces.matches(pieceIndex, Hashing.sha1(actualBytes));
        lock.lock();
        try {
            if (matches && !completedPieces.get(pieceIndex)) {
                completedPieces.set(pieceIndex);
                completedBytes += pieceLength(pieceIndex);
                if (pieceTier[pieceIndex] > 0) {
                    wantedCompletedPieces++;
                    wantedCompletedBytes += pieceLength(pieceIndex);
                }
            } else if (!matches) {
                blockReceived[pieceIndex].clear();
            }
        } finally {
            lock.unlock();
        }
        return matches;
    }

    /** Forgets every completed piece and every received block, as if nothing had been
     * downloaded - the starting point for a forced recheck, which then re-establishes what is
     * really on disk by verifying each piece again. See design_docs/0082. */
    public void resetCompletion() {
        lock.lock();
        try {
            completedPieces.clear();
            for (BitSet blocks : blockReceived) {
                blocks.clear();
            }
            recountWanted();
        } finally {
            lock.unlock();
        }
    }

    public boolean isComplete(int pieceIndex) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            return completedPieces.get(pieceIndex);
        } finally {
            lock.unlock();
        }
    }

    public boolean isAllComplete() {
        lock.lock();
        try {
            return completedPieces.cardinality() == pieceCount;
        } finally {
            lock.unlock();
        }
    }

    public int completedCount() {
        lock.lock();
        try {
            return completedPieces.cardinality();
        } finally {
            lock.unlock();
        }
    }

    /** For a selection strategy, which runs under lock (selectNextPiece() holds it): the first
     * piece at or after from that isn't verified yet, or pieceCount if there is none. Skips
     * completed pieces a word at a time, so a nearly finished torrent isn't rescanned piece by
     * piece. */
    int nextIncompletePiece(int from) {
        return Math.min(completedPieces.nextClearBit(from), pieceCount);
    }

    /** priorityTier() without taking the lock, for a strategy already running under it. */
    int tierUnderLock(int pieceIndex) {
        return pieceTier[pieceIndex];
    }

    public OptionalInt selectNextPiece(IntPredicate peerHasPiece) {
        lock.lock();
        try {
            return selectionStrategy.selectNextPiece(this, peerHasPiece);
        } finally {
            lock.unlock();
        }
    }

    public boolean isBlockReceived(int pieceIndex, int blockIndex) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            return blockReceived[pieceIndex].get(blockIndex);
        } finally {
            lock.unlock();
        }
    }

    public OptionalInt selectNextBlock(int pieceIndex) {
        validateIndex(pieceIndex);
        lock.lock();
        try {
            BitSet received = blockReceived[pieceIndex];
            int count = blockCount(pieceIndex);
            for (int i = 0; i < count; i++) {
                if (!received.get(i)) {
                    return OptionalInt.of(i);
                }
            }
            return OptionalInt.empty();
        } finally {
            lock.unlock();
        }
    }

    private void validateIndex(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= pieceCount) {
            throw new IndexOutOfBoundsException(
                    "Piece index " + pieceIndex + " out of range [0," + pieceCount + ")");
        }
    }
}
