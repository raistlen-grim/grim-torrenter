package com.grimtorrenter.engine.storage;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.MultiFileTorrent;
import com.grimtorrenter.engine.metainfo.PieceHashes;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentStorageTest {

    private static byte[] fill(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    private static PieceHashes fakePieceHashes(int count) {
        return new PieceHashes(fill(count * PieceHashes.HASH_LENGTH, 0));
    }

    @Test
    void writesAndReadsBackWithinSingleFile(@TempDir Path tempDir) throws IOException {
        SingleFileTorrent metadata = new SingleFileTorrent(
                "movie.bin", 1000, 500, fakePieceHashes(2), InfoHash.of(fill(20, 1)), null, List.of());

        try (TorrentStorage storage = TorrentStorage.create(metadata, tempDir)) {
            byte[] data = fill(300, 7);
            storage.write(100, data);
            assertArrayEquals(data, storage.read(100, 300));
        }

        Path file = tempDir.resolve("movie.bin");
        assertTrue(Files.exists(file));
        assertEquals(1000, Files.size(file));
    }

    @Test
    void writeSpanningTwoFilesSplitsCorrectly(@TempDir Path tempDir) throws IOException {
        TorrentFile fileA = new TorrentFile(List.of("a.bin"), 100);
        TorrentFile fileB = new TorrentFile(List.of("b.bin"), 100);
        MultiFileTorrent metadata = new MultiFileTorrent(
                "torrent-dir", List.of(fileA, fileB), 100, fakePieceHashes(2),
                InfoHash.of(fill(20, 2)), null, List.of());

        // bytes [90,110) - spans a.bin's last 10 bytes and b.bin's first 10
        byte[] spanning = fill(20, 55);

        try (TorrentStorage storage = TorrentStorage.create(metadata, tempDir)) {
            storage.write(90, spanning);
            assertArrayEquals(spanning, storage.read(90, 20));
        }

        byte[] aBytes = Files.readAllBytes(tempDir.resolve("torrent-dir").resolve("a.bin"));
        byte[] bBytes = Files.readAllBytes(tempDir.resolve("torrent-dir").resolve("b.bin"));

        byte[] expectedATail = new byte[10];
        System.arraycopy(spanning, 0, expectedATail, 0, 10);
        byte[] actualATail = new byte[10];
        System.arraycopy(aBytes, 90, actualATail, 0, 10);
        assertArrayEquals(expectedATail, actualATail);

        byte[] expectedBHead = new byte[10];
        System.arraycopy(spanning, 10, expectedBHead, 0, 10);
        byte[] actualBHead = new byte[10];
        System.arraycopy(bBytes, 0, actualBHead, 0, 10);
        assertArrayEquals(expectedBHead, actualBHead);
    }

    @Test
    void createsNestedDirectoriesForMultiFilePaths(@TempDir Path tempDir) throws IOException {
        TorrentFile nested = new TorrentFile(List.of("sub", "deep", "file.txt"), 50);
        MultiFileTorrent metadata = new MultiFileTorrent(
                "torrent-dir", List.of(nested), 50, fakePieceHashes(1),
                InfoHash.of(fill(20, 3)), null, List.of());

        try (TorrentStorage storage = TorrentStorage.create(metadata, tempDir)) {
            storage.write(0, fill(50, 1));
        }

        assertTrue(Files.exists(tempDir.resolve("torrent-dir").resolve("sub").resolve("deep").resolve("file.txt")));
    }

    /** Same scenario as writeSpanningTwoFilesSplitsCorrectly above, but with a FileHandlePool
     * capacity of 1 - too small to hold both a.bin and b.bin open at once. Every read()/
     * write() call still has to work correctly regardless: this is the property the whole
     * bounded-pool design depends on (see design_docs/0047) - reads/writes must stay
     * correct under real cache churn, not just under a pool big enough to never evict. */
    @Test
    void writesAndReadsCorrectlyEvenWhenThePoolIsTooSmallToKeepBothFilesOpen(@TempDir Path tempDir) throws IOException {
        TorrentFile fileA = new TorrentFile(List.of("a.bin"), 100);
        TorrentFile fileB = new TorrentFile(List.of("b.bin"), 100);
        MultiFileTorrent metadata = new MultiFileTorrent(
                "torrent-dir", List.of(fileA, fileB), 100, fakePieceHashes(2),
                InfoHash.of(fill(20, 6)), null, List.of());
        FileHandlePool pool = new FileHandlePool(1);

        try (TorrentStorage storage = TorrentStorage.create(metadata, tempDir, pool)) {
            // Alternate between the two files enough times to force real eviction/reopen
            // churn between every single call, not just once.
            for (int i = 0; i < 5; i++) {
                storage.write(10, fill(5, i));
                storage.write(110, fill(5, i + 100));
                assertArrayEquals(fill(5, i), storage.read(10, 5));
                assertArrayEquals(fill(5, i + 100), storage.read(110, 5));
            }
        }
    }

    @Test
    void rejectsOutOfRangeOffset(@TempDir Path tempDir) throws IOException {
        SingleFileTorrent metadata = new SingleFileTorrent(
                "x.bin", 100, 100, fakePieceHashes(1), InfoHash.of(fill(20, 4)), null, List.of());

        try (TorrentStorage storage = TorrentStorage.create(metadata, tempDir)) {
            assertThrows(StorageException.class, () -> storage.read(100, 1));
            assertThrows(StorageException.class, () -> storage.write(150, new byte[]{1}));
        }
    }
}
