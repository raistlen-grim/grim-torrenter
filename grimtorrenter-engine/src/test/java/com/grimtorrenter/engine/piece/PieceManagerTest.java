package com.grimtorrenter.engine.piece;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PieceManagerTest {

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fill(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (seed * 7 + i);
        }
        return b;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    /*
     * pieceLength = 32768 (2 blocks of 16384 each), total = 70000 -> 3 pieces:
     *   piece 0: 32768 bytes (2 full blocks)
     *   piece 1: 32768 bytes (2 full blocks)
     *   piece 2: 4464 bytes (1 partial block)
     */
    private static TorrentMetadata metadataWithPartialLastPiece() {
        long pieceLength = 32768;
        long totalLength = 70000;
        byte[] piece0 = fill(32768, 0);
        byte[] piece1 = fill(32768, 1);
        byte[] piece2 = fill((int) (totalLength - 2 * pieceLength), 2);
        PieceHashes pieces = new PieceHashes(concat(sha1(piece0), sha1(piece1), sha1(piece2)));
        return new SingleFileTorrent("test.bin", totalLength, pieceLength, pieces,
                InfoHash.of(fill(20, 5)), null, List.of());
    }

    @Test
    void computesPieceAndBlockLayoutIncludingLastPieceTruncation() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());

        assertEquals(3, manager.pieceCount());
        assertEquals(32768, manager.pieceLength(0));
        assertEquals(32768, manager.pieceLength(1));
        assertEquals(4464, manager.pieceLength(2));

        assertEquals(2, manager.blockCount(0));
        assertEquals(2, manager.blockCount(1));
        assertEquals(1, manager.blockCount(2));

        assertEquals(16384, manager.blockLength(0, 0));
        assertEquals(16384, manager.blockLength(0, 1));
        assertEquals(4464, manager.blockLength(2, 0));

        assertEquals(0L, manager.pieceOffset(0));
        assertEquals(32768L, manager.pieceOffset(1));
        assertEquals(65536L, manager.pieceOffset(2));
        assertEquals(65536L, manager.globalOffset(2, 0));
    }

    @Test
    void tracksBlockReceiptAndReadinessToVerify() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());

        assertEquals(PieceState.NEEDED, manager.stateOf(0));
        assertFalse(manager.isPieceReadyToVerify(0));

        manager.markBlockReceived(0, 0);
        assertEquals(PieceState.IN_PROGRESS, manager.stateOf(0));
        assertFalse(manager.isPieceReadyToVerify(0));

        manager.markBlockReceived(0, 16384);
        assertTrue(manager.isPieceReadyToVerify(0));
    }

    @Test
    void verifySucceedsAndMarksComplete() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());
        byte[] piece0 = fill(32768, 0);

        manager.markBlockReceived(0, 0);
        manager.markBlockReceived(0, 16384);

        assertTrue(manager.verify(0, piece0));
        assertEquals(PieceState.COMPLETE, manager.stateOf(0));
        assertTrue(manager.isComplete(0));
        assertFalse(manager.isAllComplete());
    }

    @Test
    void verifyFailureResetsBlocksForRedownload() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());
        byte[] wrongData = fill(32768, 99);

        manager.markBlockReceived(0, 0);
        manager.markBlockReceived(0, 16384);

        assertFalse(manager.verify(0, wrongData));
        assertEquals(PieceState.NEEDED, manager.stateOf(0));
        assertFalse(manager.isComplete(0));
    }

    @Test
    void isBlockReceivedReflectsMarkBlockReceived() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());

        assertFalse(manager.isBlockReceived(0, 0));
        manager.markBlockReceived(0, 0);
        assertTrue(manager.isBlockReceived(0, 0));
        assertFalse(manager.isBlockReceived(0, 1));
    }

    @Test
    void selectNextBlockReturnsFirstMissingThenEmptyWhenDone() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());

        assertEquals(OptionalInt.of(0), manager.selectNextBlock(0));
        manager.markBlockReceived(0, 0);
        assertEquals(OptionalInt.of(1), manager.selectNextBlock(0));
        manager.markBlockReceived(0, 16384);
        assertEquals(OptionalInt.empty(), manager.selectNextBlock(0));
    }

    @Test
    void sequentialSelectionSkipsCompleteAndUnavailablePieces() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());
        byte[] piece0 = fill(32768, 0);
        manager.markBlockReceived(0, 0);
        manager.markBlockReceived(0, 16384);
        manager.verify(0, piece0);

        // Peer has pieces 0 and 2 - piece 0 already complete, piece 1 unavailable -> expect piece 2
        OptionalInt selected = manager.selectNextPiece(index -> index == 0 || index == 2);
        assertEquals(OptionalInt.of(2), selected);
    }

    @Test
    void selectNextPieceReturnsEmptyWhenPeerHasNothingUseful() {
        PieceManager manager = new PieceManager(metadataWithPartialLastPiece());
        assertEquals(OptionalInt.empty(), manager.selectNextPiece(index -> false));
    }
}
