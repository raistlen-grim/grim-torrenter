package com.grimtorrenter.engine.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileHandlePoolTest {

    private static Path createFile(Path dir, String name) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, new byte[]{1, 2, 3});
        return path;
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new FileHandlePool(0));
    }

    @Test
    void acquireReturnsAUsableChannel(@TempDir Path tempDir) throws IOException {
        Path file = createFile(tempDir, "a.bin");
        FileHandlePool pool = new FileHandlePool(4);

        FileChannel channel = pool.acquire(file);
        try {
            ByteBuffer buf = ByteBuffer.allocate(3);
            channel.read(buf, 0);
            assertEquals(3, buf.position());
        } finally {
            pool.release(file);
        }
    }

    @Test
    void reacquiringTheSamePathReturnsTheSameOpenChannel(@TempDir Path tempDir) throws IOException {
        Path file = createFile(tempDir, "a.bin");
        FileHandlePool pool = new FileHandlePool(4);

        FileChannel first = pool.acquire(file);
        pool.release(file);
        FileChannel second = pool.acquire(file);
        pool.release(file);

        assertEquals(first, second);
    }

    /** Capacity 1, two different files - the second acquire() has to evict the first (which
     * is idle, released right after its own acquire) to make room. Proven not by inspecting
     * internals but by reacquiring the evicted path afterward and confirming it still works -
     * exactly the "reopen on demand" property this pool depends on for pause/resume to stay
     * safe (see design_docs/0047, design_docs/0030). */
    @Test
    void evictsTheLeastRecentlyUsedIdleEntryWhenAtCapacity(@TempDir Path tempDir) throws IOException {
        Path a = createFile(tempDir, "a.bin");
        Path b = createFile(tempDir, "b.bin");
        FileHandlePool pool = new FileHandlePool(1);

        pool.acquire(a);
        pool.release(a);
        assertEquals(1, pool.openCount());

        pool.acquire(b);
        pool.release(b);
        assertEquals(1, pool.openCount(), "capacity 1 should never hold more than one open channel once both sides are idle");

        // 'a' was evicted to make room for 'b' - reacquiring it must transparently reopen it,
        // not throw or return a stale/closed channel.
        FileChannel reacquired = pool.acquire(a);
        try {
            ByteBuffer buf = ByteBuffer.allocate(3);
            reacquired.read(buf, 0);
            assertEquals(3, buf.position());
        } finally {
            pool.release(a);
        }
    }

    /** A path that's still in use (acquired but not yet released) must never be forced
     * closed just because the pool is at capacity - the pool briefly exceeds its nominal cap
     * instead, the same "small overshoot is acceptable" tradeoff TorrentSession's own
     * MAX_CONNECTIONS makes. */
    @Test
    void neverEvictsAPathThatIsCurrentlyInUse(@TempDir Path tempDir) throws IOException {
        Path a = createFile(tempDir, "a.bin");
        Path b = createFile(tempDir, "b.bin");
        FileHandlePool pool = new FileHandlePool(1);

        FileChannel channelA = pool.acquire(a); // not released - still "in use"
        FileChannel channelB = pool.acquire(b);

        assertEquals(2, pool.openCount(), "both channels are in use, so the pool must overshoot rather than close one out from under its caller");
        assertEquals(3, readAll(channelA).length);
        assertEquals(3, readAll(channelB).length);

        pool.release(a);
        pool.release(b);
    }

    @Test
    void unboundedPoolNeverEvicts(@TempDir Path tempDir) throws IOException {
        FileHandlePool pool = FileHandlePool.unbounded();
        for (int i = 0; i < 50; i++) {
            Path file = createFile(tempDir, "file" + i + ".bin");
            pool.acquire(file);
            pool.release(file);
        }

        assertEquals(50, pool.openCount());
    }

    private static byte[] readAll(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(3);
        channel.read(buf, 0);
        return buf.array();
    }
}
