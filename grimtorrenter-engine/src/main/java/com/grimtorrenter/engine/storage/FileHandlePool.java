package com.grimtorrenter.engine.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A shared, engine-wide cache of open FileChannels, bounded to at most maxOpenFiles entries
 * open at once - evicting the least-recently-used, currently-idle entry when a new file needs
 * opening past that cap. Exists so the engine's total OS file-descriptor usage for torrent
 * data stays bounded regardless of how many torrents exist or how many files each has,
 * replacing the old "open every file once, hold it open until the torrent is fully removed"
 * behavior - a paused (or simply idle) torrent no longer keeps every file handle open
 * forever, and it doesn't matter how many torrents are paused/idle/running at once. See
 * design_docs/0047.
 *
 * <p>Guarded by a single ReentrantLock, not synchronized - see design_docs/0007's virtual-
 * thread pinning warning; a monitor (synchronized) can pin a parked virtual thread to its
 * carrier, a ReentrantLock cannot. Held across the occasional file open/close syscall this
 * causes, not just the map bookkeeping - simpler to reason about than moving I/O outside the
 * lock, and file opens are rare once a torrent's working set of files has been touched once,
 * so serializing them isn't the bottleneck this project needs to worry about (see
 * design_docs/0007's own "not chasing the concurrency ceiling a competitive client would
 * need" framing).
 */
public final class FileHandlePool {

    private final int maxOpenFiles;
    private final ReentrantLock lock = new ReentrantLock();
    /** accessOrder=true - iteration order is least-recently-used to most-recently-used, so
     * the eviction scan below always finds the true LRU candidate first. */
    private final Map<Path, Entry> open = new LinkedHashMap<>(16, 0.75f, true);

    public FileHandlePool(int maxOpenFiles) {
        if (maxOpenFiles < 1) {
            throw new IllegalArgumentException("maxOpenFiles must be at least 1, was " + maxOpenFiles);
        }
        this.maxOpenFiles = maxOpenFiles;
    }

    /** For every caller that doesn't want a bounded budget - tests, mainly, and any
     * lower-arity TorrentEngine/TorrentSession constructor that predates this pool and so
     * doesn't pass one explicitly. Correct as long as such a caller genuinely doesn't open
     * enough files at once to matter (true for every existing test). */
    public static FileHandlePool unbounded() {
        return new FileHandlePool(Integer.MAX_VALUE);
    }

    /** Borrows a channel for path, opening it (evicting the LRU idle entry first if the pool
     * is already at capacity) if it isn't already open. Must be paired with a call to
     * release(path) once the caller is done with it - TorrentStorage.read()/write() acquire
     * and release around a single call, never holding one across two. The file itself must
     * already exist (TorrentStorage.create() pre-creates and pre-allocates every file up
     * front) - this never creates one. */
    public FileChannel acquire(Path path) throws IOException {
        lock.lock();
        try {
            Entry entry = open.get(path);
            if (entry != null) {
                entry.refCount++;
                return entry.channel;
            }
            evictOneIdleEntryIfAtCapacity();
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            open.put(path, new Entry(channel));
            return channel;
        } finally {
            lock.unlock();
        }
    }

    /** Decrements path's reference count - once it reaches zero the channel becomes eligible
     * for eviction, but stays open (and reusable without reopening) until pool pressure
     * actually needs the slot back. */
    public void release(Path path) {
        lock.lock();
        try {
            Entry entry = open.get(path);
            if (entry != null) {
                entry.refCount--;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Drops path from the pool now, if it's currently idle (refCount 0) - used when a
     * torrent is permanently done with a file (session close, or its data being deleted) so
     * the slot/fd is freed immediately rather than waiting for some other torrent's access to
     * trigger LRU pressure. A path that's still mid-use (refCount > 0) is left alone rather
     * than forced closed out from under whichever thread is using it - safe to skip, since a
     * session that's actually closing shouldn't have in-flight I/O against it; eventual LRU
     * pressure would reclaim it anyway if this is ever wrong. */
    public void evict(Path path) {
        lock.lock();
        try {
            Entry entry = open.get(path);
            if (entry != null && entry.refCount <= 0) {
                open.remove(path);
                closeQuietly(entry.channel);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Caller already holds lock. If every open entry is currently in use (refCount > 0
     * everywhere), this is a no-op and the pool briefly exceeds maxOpenFiles rather than
     * blocking or failing the caller - the same "a small overshoot is an acceptable
     * imprecision" tradeoff TorrentSession's own MAX_CONNECTIONS cap already makes. */
    private void evictOneIdleEntryIfAtCapacity() {
        if (open.size() < maxOpenFiles) {
            return;
        }
        Path victim = null;
        for (Map.Entry<Path, Entry> candidate : open.entrySet()) {
            if (candidate.getValue().refCount <= 0) {
                victim = candidate.getKey();
                break;
            }
        }
        if (victim != null) {
            closeQuietly(open.remove(victim).channel);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best-effort - if this somehow matters, the file just gets reopened on next use
        }
    }

    /** How many channels are currently open - public for diagnostics/tests (e.g. a load
     * test sampling this while many torrents restore concurrently, see
     * design_docs/0049). Not itself part of any hot path. */
    public int openCount() {
        lock.lock();
        try {
            return open.size();
        } finally {
            lock.unlock();
        }
    }

    private static final class Entry {
        final FileChannel channel;
        int refCount = 1;

        Entry(FileChannel channel) {
            this.channel = channel;
        }
    }
}
