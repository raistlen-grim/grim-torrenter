package com.grimtorrenter.engine.metadata;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peer.PeerConnection;
import com.grimtorrenter.engine.peer.PeerConnectionListener;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.PeerMessage;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fetches and verifies a torrent's info-dict bytes from a single peer via BEP 9's
 * ut_metadata extension (built on the BEP 10 extension protocol - see PeerConnection
 * and design_docs/0028). Blocking - callers run this on their own thread (a virtual
 * thread, matching this engine's usual pattern for peer I/O elsewhere).
 *
 * <p>Deliberately single-peer, single-attempt: trying multiple peers and deciding
 * when to give up is TorrentEngine's job once magnet links are wired into it, not
 * this class's - same separation of concerns as PeerConnection only ever knowing
 * about the one connection it is.
 *
 * <p>Uses {@code PeerConnection.connect()}'s full-arg overload directly (passing
 * {@code RateLimiters.unlimited()} - a one-shot metadata exchange never transfers real piece
 * data, so rate limiting was never applicable here) rather than a shorter convenience
 * overload - that overload chain silently defaulted encryption to {@code DISABLED}
 * regardless of the engine's actual configured mode, a real gap found and fixed
 * 2026-08-30 (design_docs/0052's own addendum): magnet metadata fetches were always
 * connecting in plaintext even with MSE set to PREFERRED/REQUIRED elsewhere.
 */
public final class MetadataFetcher {

    private static final int BLOCK_SIZE = 16 * 1024;
    private static final String EXTENSION_NAME = "ut_metadata";
    private static final int OUR_EXTENSION_ID = 1;
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final long PIECE_TIMEOUT_MS = 10_000;

    private MetadataFetcher() {
    }

    public static byte[] fetch(PeerAddress address, InfoHash infoHash, PeerId ourPeerId,
                                EncryptionMode encryptionMode) throws IOException {
        FetchListener listener = new FetchListener();
        try (PeerConnection connection = PeerConnection.connect(address, infoHash, ourPeerId, listener,
                Map.of(EXTENSION_NAME, OUR_EXTENSION_ID), RateLimiters.unlimited(), encryptionMode)) {

            if (!listener.awaitHandshake(HANDSHAKE_TIMEOUT_MS)) {
                throw new MetadataFetchException("Peer " + address + " never sent an extended handshake");
            }
            int theirExtensionId = connection.remoteExtensionId(EXTENSION_NAME)
                    .orElseThrow(() -> new MetadataFetchException(
                            "Peer " + address + " does not support ut_metadata"));
            int totalSize = connection.remoteMetadataSize()
                    .orElseThrow(() -> new MetadataFetchException(
                            "Peer " + address + " did not report a metadata size"));

            byte[] assembled = fetchAllPieces(connection, listener, address, theirExtensionId, totalSize);

            InfoHash actual = InfoHash.of(sha1(assembled));
            if (!actual.equals(infoHash)) {
                throw new MetadataFetchException(
                        "Peer " + address + " sent metadata that doesn't match the requested info hash");
            }
            return assembled;
        }
    }

    private static byte[] fetchAllPieces(PeerConnection connection, FetchListener listener, PeerAddress address,
                                          int theirExtensionId, int totalSize) {
        int pieceCount = (totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE;
        byte[] assembled = new byte[totalSize];
        for (int piece = 0; piece < pieceCount; piece++) {
            connection.sendExtended(theirExtensionId, UtMetadataCodec.encode(new MetadataRequest(piece)));
            UtMetadataMessage response = listener.awaitPiece(piece, PIECE_TIMEOUT_MS);
            if (response == null) {
                throw new MetadataFetchException(
                        "Peer " + address + " did not answer metadata piece " + piece + " in time");
            }
            if (response instanceof MetadataReject) {
                throw new MetadataFetchException("Peer " + address + " rejected metadata piece " + piece);
            }
            MetadataData data = (MetadataData) response;
            if (data.totalSize() != totalSize) {
                throw new MetadataFetchException(
                        "Peer " + address + " reported an inconsistent metadata size on piece " + piece);
            }
            int offset = piece * BLOCK_SIZE;
            int expectedLength = Math.min(BLOCK_SIZE, totalSize - offset);
            if (data.block().length != expectedLength) {
                throw new MetadataFetchException("Peer " + address + " sent a wrong-sized metadata piece " + piece);
            }
            System.arraycopy(data.block(), 0, assembled, offset, expectedLength);
        }
        return assembled;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available on this JVM", e);
        }
    }

    /**
     * Requests are sent one at a time, waiting for each response before requesting the
     * next - no pipelining, unlike regular block requests in TorrentSession. Metadata
     * exchanges are small (at most a few hundred KiB even for huge multi-file torrents)
     * and one-shot, so the pipelining that matters for a whole download isn't worth the
     * complexity here.
     */
    private static final class FetchListener implements PeerConnectionListener {
        private final CountDownLatch handshakeLatch = new CountDownLatch(1);
        private final BlockingQueue<UtMetadataMessage> incoming = new LinkedBlockingQueue<>();

        boolean awaitHandshake(long timeoutMs) {
            try {
                return handshakeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /** Drops any response for a different piece than requested (a stale/duplicate
         * answer to an earlier request) rather than treating it as the answer - a
         * disconnect mid-wait isn't specially short-circuited, it's just left to resolve
         * as an ordinary timeout once the queue stays empty for the rest of timeoutMs. */
        UtMetadataMessage awaitPiece(int piece, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                UtMetadataMessage message;
                try {
                    message = incoming.poll(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (message == null) {
                    return null;
                }
                if (message.piece() == piece) {
                    return message;
                }
            }
        }

        @Override
        public void onMessage(PeerConnection connection, PeerMessage message) {
            if (!(message instanceof Extended extended)) {
                return;
            }
            if (extended.extendedMessageId() == 0) {
                handshakeLatch.countDown();
                return;
            }
            if (extended.extendedMessageId() != OUR_EXTENSION_ID) {
                return;
            }
            try {
                incoming.add(UtMetadataCodec.decode(extended.payload()));
            } catch (RuntimeException ignored) {
                // Malformed ut_metadata message - drop it and let the fetch loop time
                // out and fail cleanly, rather than propagating a decode error out of
                // this listener callback (which runs on the connection's read thread).
            }
        }

        @Override
        public void onDisconnected(PeerConnection connection, Throwable cause) {
            // Unblocks an in-progress awaitHandshake() rather than making it wait out
            // the full timeout after the connection is already known to be dead.
            handshakeLatch.countDown();
        }
    }
}
