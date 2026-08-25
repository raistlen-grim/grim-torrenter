package com.grimtorrenter.engine.piece;

import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
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
 * <p>Does not track in-flight requests across different peers - two peers
 * could in principle both be asked for the same block. That's a bandwidth
 * optimization ("endgame mode" in most clients), not a correctness
 * requirement, and is deferred - see design_docs/0016.
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

    public PieceManager(TorrentMetadata metadata) {
        this(metadata, new SequentialPieceSelectionStrategy());
    }

    public PieceManager(TorrentMetadata metadata, PieceSelectionStrategy selectionStrategy) {
        this.pieces = metadata.pieces();
        this.totalLength = metadata.totalLength();
        this.nominalPieceLength = metadata.pieceLength();
        this.pieceCount = pieces.count();
        this.selectionStrategy = selectionStrategy;
        this.blockReceived = new BitSet[pieceCount];
        for (int i = 0; i < pieceCount; i++) {
            blockReceived[i] = new BitSet(blockCount(i));
        }
        this.completedPieces = new BitSet(pieceCount);
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
        boolean matches = pieces.matches(pieceIndex, sha1(actualBytes));
        lock.lock();
        try {
            if (matches) {
                completedPieces.set(pieceIndex);
            } else {
                blockReceived[pieceIndex].clear();
            }
        } finally {
            lock.unlock();
        }
        return matches;
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

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available on this JVM", e);
        }
    }
}
