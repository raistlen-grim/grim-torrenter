package com.grimtorrenter.engine.storage;

import com.grimtorrenter.engine.metainfo.MultiFileTorrent;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentFile;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a torrent's overall byte-offset space onto one or more files on
 * disk. Deliberately knows nothing about "pieces" - see design_docs/0016
 * for why this and PieceManager are independent siblings rather than one
 * depending on the other.
 *
 * <p>Doesn't hold its own open FileChannels - every read()/write() borrows one from a shared
 * FileHandlePool for the duration of that single call, rather than opening every file once at
 * construction and holding it for the torrent's whole lifetime (see design_docs/0047).
 * Concurrent read()/write() calls from multiple threads (e.g. multiple peer connections
 * delivering blocks at once) stay safe under this: FileChannel's positional read/write
 * overloads (the ones used below, not the stream-style ones) are safe for concurrent use per
 * the FileChannel contract even when two callers are borrowing the same pooled channel at
 * once, and the slice list itself is built once at construction and never mutated after.
 */
public final class TorrentStorage implements Closeable {

    private final List<FileSlice> slices;
    private final FileHandlePool pool;

    private TorrentStorage(List<FileSlice> slices, FileHandlePool pool) {
        this.slices = slices;
        this.pool = pool;
    }

    /** For every caller that doesn't want a bounded file-handle budget - tests, mainly. See
     * FileHandlePool.unbounded(). */
    public static TorrentStorage create(TorrentMetadata metadata, Path baseDirectory) throws IOException {
        return create(metadata, baseDirectory, FileHandlePool.unbounded());
    }

    public static TorrentStorage create(TorrentMetadata metadata, Path baseDirectory, FileHandlePool pool)
            throws IOException {
        List<FileSlice> slices = new ArrayList<>();
        switch (metadata) {
            case SingleFileTorrent single -> {
                Files.createDirectories(baseDirectory);
                Path filePath = baseDirectory.resolve(single.name());
                slices.add(preallocate(filePath, 0, single.length()));
            }
            case MultiFileTorrent multi -> {
                Path torrentDir = baseDirectory.resolve(multi.name());
                long offset = 0;
                for (TorrentFile file : multi.files()) {
                    Path filePath = torrentDir;
                    for (String segment : file.pathSegments()) {
                        filePath = filePath.resolve(segment);
                    }
                    Files.createDirectories(filePath.getParent());
                    slices.add(preallocate(filePath, offset, file.length()));
                    offset += file.length();
                }
            }
        }
        return new TorrentStorage(slices, pool);
    }

    /** setLength pre-allocates (sparse on most filesystems) so writes at any offset are valid
     * immediately - blocks arrive out of order from different peers, not sequentially. Opens
     * its own short-lived RandomAccessFile rather than going through the pool - this runs
     * once at construction, before there's anything worth caching, and has no reason to leave
     * anything open afterward. */
    private static FileSlice preallocate(Path path, long startOffset, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(length);
        }
        return new FileSlice(startOffset, length, path);
    }

    public void write(long offset, byte[] data) throws IOException {
        int written = 0;
        long current = offset;
        while (written < data.length) {
            FileSlice slice = findSliceContaining(current);
            long withinFile = current - slice.startOffset();
            int available = (int) Math.min(slice.length() - withinFile, data.length - written);
            FileChannel channel = pool.acquire(slice.path());
            try {
                channel.write(ByteBuffer.wrap(data, written, available), withinFile);
            } finally {
                pool.release(slice.path());
            }
            written += available;
            current += available;
        }
    }

    public byte[] read(long offset, int length) throws IOException {
        byte[] result = new byte[length];
        int readSoFar = 0;
        long current = offset;
        while (readSoFar < length) {
            FileSlice slice = findSliceContaining(current);
            long withinFile = current - slice.startOffset();
            int toRead = (int) Math.min(slice.length() - withinFile, length - readSoFar);
            ByteBuffer buf = ByteBuffer.wrap(result, readSoFar, toRead);
            long filePosition = withinFile;
            FileChannel channel = pool.acquire(slice.path());
            try {
                while (buf.hasRemaining()) {
                    int n = channel.read(buf, filePosition);
                    if (n < 0) {
                        throw new IOException("Unexpected end of file in " + slice.path() + " at offset " + filePosition);
                    }
                    filePosition += n;
                }
            } finally {
                pool.release(slice.path());
            }
            readSoFar += toRead;
            current += toRead;
        }
        return result;
    }

    private FileSlice findSliceContaining(long offset) {
        for (FileSlice slice : slices) {
            if (offset >= slice.startOffset() && offset < slice.startOffset() + slice.length()) {
                return slice;
            }
        }
        throw new StorageException("Offset " + offset + " is outside the torrent's byte range");
    }

    /** Evicts this torrent's files from the shared pool, if idle - frees their slots/fds
     * immediately rather than waiting for some other torrent's access to trigger LRU
     * pressure (particularly worth doing before data is deleted, on some platforms an open
     * handle blocks deletion). Unlike the old design this replaces, this isn't "permanently
     * closed, no reopen path" (the exact bug design_docs/0030 fixed) - a read()/write() call
     * after this, which shouldn't happen once a session is closed but isn't unsafe if it did,
     * would simply reopen through the pool like any other cache miss. See design_docs/0047. */
    @Override
    public void close() {
        for (FileSlice slice : slices) {
            pool.evict(slice.path());
        }
    }

    private record FileSlice(long startOffset, long length, Path path) {
    }
}
