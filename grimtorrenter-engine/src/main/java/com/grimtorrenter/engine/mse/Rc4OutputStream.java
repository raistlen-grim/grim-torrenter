package com.grimtorrenter.engine.mse;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Encrypts every byte written to the underlying stream through an {@link Rc4Cipher} - see
 * design_docs/0052. Bound to one direction's cipher/key, the mirror image of
 * {@link Rc4InputStream}.
 */
public final class Rc4OutputStream extends FilterOutputStream {

    private final Rc4Cipher cipher;

    public Rc4OutputStream(OutputStream out, Rc4Cipher cipher) {
        super(out);
        this.cipher = cipher;
    }

    @Override
    public void write(int b) throws IOException {
        byte[] single = { (byte) b };
        cipher.process(single);
        out.write(single[0] & 0xFF);
    }

    /** Overridden - FilterOutputStream's default forwards to write(int) one byte at a time,
     * which would still be correct here but needlessly slow. Encrypts into a fresh copy
     * rather than the caller's own buffer, since mutating a caller-supplied array in place
     * would be a surprising side effect for an OutputStream.write() call. */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        byte[] encrypted = Arrays.copyOfRange(b, off, off + len);
        cipher.process(encrypted);
        out.write(encrypted);
    }
}
