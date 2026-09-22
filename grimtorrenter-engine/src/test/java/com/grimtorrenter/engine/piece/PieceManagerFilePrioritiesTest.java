package com.grimtorrenter.engine.piece;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.MultiFileTorrent;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.TorrentFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0075. */
class PieceManagerFilePrioritiesTest {

    private static final int PIECE_LENGTH = 10;

    /*
     * Three files laid out back to back, pieceLength 10, total 30 -> 3 pieces:
     *   A: bytes 0-14  (15) -> pieces 0, 1
     *   B: bytes 15-24 (10) -> pieces 1, 2
     *   C: bytes 25-29 (5)  -> piece 2
     * Piece 1 is shared by A and B; piece 2 by B and C.
     */
    private static byte[] pieceBytes(int index) {
        byte[] b = new byte[PIECE_LENGTH];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (index * 31 + i);
        }
        return b;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MultiFileTorrent metadata() {
        ByteArrayOutputStream hashes = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) {
            hashes.writeBytes(sha1(pieceBytes(i)));
        }
        byte[] infoHash = new byte[20];
        infoHash[0] = 9;
        return new MultiFileTorrent("t",
                List.of(new TorrentFile(List.of("a"), 15), new TorrentFile(List.of("b"), 10),
                        new TorrentFile(List.of("c"), 5)),
                PIECE_LENGTH, new PieceHashes(hashes.toByteArray()), InfoHash.of(infoHash), null, List.of());
    }

    private static FilePriorities priorities(FilePriority a, FilePriority b, FilePriority c) {
        return new FilePriorities(List.of(a, b, c));
    }

    @Test
    void everyFileMediumWantsEveryPieceInIndexOrder() {
        PieceManager manager = new PieceManager(metadata());

        for (int i = 0; i < 3; i++) {
            assertEquals(FilePriority.MEDIUM.tier(), manager.priorityTier(i));
        }
        assertEquals(OptionalInt.of(0), manager.selectNextPiece(i -> true));
        assertEquals(30, manager.wantedBytes());
    }

    @Test
    void aPieceSharedWithAWantedFileStaysWantedEvenIfItsOtherFileIsSkipped() {
        PieceManager manager = new PieceManager(metadata(),
                priorities(FilePriority.SKIP, FilePriority.MEDIUM, FilePriority.SKIP));

        assertFalse(manager.isWanted(0), "piece 0 overlaps only the skipped file A");
        assertTrue(manager.isWanted(1), "piece 1 is shared with wanted file B");
        assertTrue(manager.isWanted(2), "piece 2 is shared with wanted file B");
        assertEquals(20, manager.wantedBytes());
        assertEquals(OptionalInt.of(1), manager.selectNextPiece(i -> true));
    }

    @Test
    void aPieceTakesTheHighestPriorityAmongTheFilesItOverlaps() {
        PieceManager manager = new PieceManager(metadata(),
                priorities(FilePriority.LOW, FilePriority.MEDIUM, FilePriority.HIGH));

        assertEquals(FilePriority.LOW.tier(), manager.priorityTier(0));
        assertEquals(FilePriority.MEDIUM.tier(), manager.priorityTier(1));
        assertEquals(FilePriority.HIGH.tier(), manager.priorityTier(2));
    }

    @Test
    void selectionWalksHighThenMediumThenLowRegardlessOfIndex() {
        PieceManager manager = new PieceManager(metadata(),
                priorities(FilePriority.LOW, FilePriority.MEDIUM, FilePriority.HIGH));

        assertEquals(OptionalInt.of(2), manager.selectNextPiece(i -> true));
        assertTrue(manager.verify(2, pieceBytes(2)));
        assertEquals(OptionalInt.of(1), manager.selectNextPiece(i -> true));
        assertTrue(manager.verify(1, pieceBytes(1)));
        assertEquals(OptionalInt.of(0), manager.selectNextPiece(i -> true));
    }

    @Test
    void selectionNeverPicksAnUnwantedPieceEvenIfItIsTheOnlyOneThePeerHas() {
        PieceManager manager = new PieceManager(metadata(),
                priorities(FilePriority.SKIP, FilePriority.MEDIUM, FilePriority.SKIP));

        assertEquals(OptionalInt.empty(), manager.selectNextPiece(i -> i == 0));
    }

    @Test
    void wantedCompleteIsReachedWithoutEveryPieceBeingComplete() {
        PieceManager manager = new PieceManager(metadata(),
                priorities(FilePriority.SKIP, FilePriority.SKIP, FilePriority.MEDIUM));

        assertFalse(manager.isWantedComplete());
        assertEquals(10, manager.wantedBytes());
        assertTrue(manager.verify(2, pieceBytes(2)));

        assertTrue(manager.isWantedComplete());
        assertFalse(manager.isAllComplete());
        assertEquals(10, manager.wantedBytesCompleted());
        assertFalse(manager.isStillNeeded(2));
        assertFalse(manager.isStillNeeded(0), "an unwanted piece is never 'still needed'");
    }

    @Test
    void changingPrioritiesLaterRecomputesEveryPiece() {
        PieceManager manager = new PieceManager(metadata(),
                priorities(FilePriority.SKIP, FilePriority.SKIP, FilePriority.MEDIUM));
        assertFalse(manager.isWanted(0));

        manager.setFilePriorities(FilePriorities.allMedium(3));

        assertTrue(manager.isWanted(0));
        assertFalse(manager.isWantedComplete());
    }

    @Test
    void skippingEveryFileIsRejectedAndLeavesThePreviousPrioritiesInPlace() {
        PieceManager manager = new PieceManager(metadata());

        assertThrows(IllegalArgumentException.class, () -> manager.setFilePriorities(
                priorities(FilePriority.SKIP, FilePriority.SKIP, FilePriority.SKIP)));

        assertEquals(FilePriority.MEDIUM.tier(), manager.priorityTier(0));
    }
}
