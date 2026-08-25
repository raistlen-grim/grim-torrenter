package com.grimtorrenter.engine.mse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decrypts every byte read from the underlying stream through an {@link Rc4Cipher} - see
 * design_docs/0052. Bound to one direction's cipher/key; once MSE negotiation selects RC4,
 * PeerConnection wraps its socket InputStream in one of these, after which PeerWireCodec's
 * ordinary blocking reads work unmodified against it.
 */
public final class Rc4InputStream extends FilterInputStream {

    private final Rc4Cipher cipher;

    public Rc4InputStream(InputStream in, Rc4Cipher cipher) {
        super(in);
        this.cipher = cipher;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b < 0) {
            return b;
        }
        byte[] single = { (byte) b };
        cipher.process(single);
        return single[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) {
            cipher.process(b, off, n);
        }
        return n;
    }
}
