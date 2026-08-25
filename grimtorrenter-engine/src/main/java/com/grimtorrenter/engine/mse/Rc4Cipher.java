package com.grimtorrenter.engine.mse;

/**
 * RC4 stream cipher (key scheduling + pseudo-random generation) - Message Stream Encryption's
 * one fixed cipher choice, see design_docs/0052. Hand-rolled rather than javax.crypto: this
 * codebase has zero other crypto-provider dependencies, RC4's only job here is protocol
 * obfuscation rather than real confidentiality (MSE was never designed to withstand a
 * determined adversary), and RC4/ARCFOUR is flagged as a legacy algorithm on some JDK
 * distributions' security policy, so relying on the platform provider risks working on one
 * JVM and not another.
 *
 * <p>Symmetric - the same {@link #process} operation both encrypts and decrypts, since it
 * simply XORs the keystream against the data.
 */
public final class Rc4Cipher {

    /**
     * RC4's first keystream bytes are statistically distinguishable from random - MSE's spec
     * requires discarding this many bytes of output before any of it is actually used. See
     * {@link #discard(int)}.
     */
    public static final int DISCARD_BYTES = 1024;

    private final int[] s = new int[256];
    private int i;
    private int j;

    public Rc4Cipher(byte[] key) {
        if (key.length == 0) {
            throw new IllegalArgumentException("RC4 key must not be empty");
        }
        for (int idx = 0; idx < 256; idx++) {
            s[idx] = idx;
        }
        int keyScheduleJ = 0;
        for (int idx = 0; idx < 256; idx++) {
            keyScheduleJ = (keyScheduleJ + s[idx] + (key[idx % key.length] & 0xFF)) & 0xFF;
            swap(idx, keyScheduleJ);
        }
    }

    /** XORs the keystream into data[off, off+len) in place. */
    public void process(byte[] data, int off, int len) {
        for (int idx = off; idx < off + len; idx++) {
            data[idx] = (byte) (data[idx] ^ nextKeystreamByte());
        }
    }

    /** XORs the keystream into the whole array in place, returning it for convenience. */
    public byte[] process(byte[] data) {
        process(data, 0, data.length);
        return data;
    }

    /** Advances the keystream by count bytes without using the output - see DISCARD_BYTES. */
    public void discard(int count) {
        for (int n = 0; n < count; n++) {
            nextKeystreamByte();
        }
    }

    private int nextKeystreamByte() {
        i = (i + 1) & 0xFF;
        j = (j + s[i]) & 0xFF;
        swap(i, j);
        return s[(s[i] + s[j]) & 0xFF];
    }

    private void swap(int a, int b) {
        int tmp = s[a];
        s[a] = s[b];
        s[b] = tmp;
    }
}
