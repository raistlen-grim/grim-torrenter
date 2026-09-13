package com.grimtorrenter.engine.utp;

import java.io.IOException;
import java.io.InputStream;

/**
 * Adapts UtpSocket.receive()'s chunk-at-a-time delivery to the ordinary InputStream contract a
 * generic byte-stream consumer (peerwire.PeerWireCodec, via peer.PeerConnection - see
 * design_docs/0074's slice 2) expects - mirrors mse's own Rc4InputStream/Rc4OutputStream as
 * separate top-level classes rather than nested, the existing precedent for a stream adapter in
 * this codebase.
 *
 * <p>Buffers a cursor into the current chunk so a caller reading fewer bytes than one whole
 * chunk (the normal case - InputStream.read() is never obligated to fill the caller's buffer)
 * doesn't lose the remainder; the next read() call resumes from where the last one left off
 * before pulling a fresh chunk from receive().
 */
final class UtpInputStream extends InputStream {

    private final UtpSocket socket;
    private byte[] currentChunk = new byte[0];
    private int chunkOffset;
    private boolean eof;

    UtpInputStream(UtpSocket socket) {
        this.socket = socket;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (eof) {
            return -1;
        }
        while (chunkOffset >= currentChunk.length) {
            byte[] next;
            try {
                next = socket.receive();
            } catch (UtpException e) {
                throw new IOException(e.getMessage(), e);
            }
            if (next == null) {
                eof = true;
                return -1;
            }
            currentChunk = next;
            chunkOffset = 0;
        }
        int n = Math.min(len, currentChunk.length - chunkOffset);
        System.arraycopy(currentChunk, chunkOffset, b, off, n);
        chunkOffset += n;
        return n;
    }
}
