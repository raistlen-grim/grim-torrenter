package com.grimtorrenter.engine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-1, which BitTorrent uses for info hashes, piece hashes, MSE keys and DHT tokens. Every
 * Java runtime is required to provide it, so its absence is a broken JVM, not an I/O error. */
public final class Hashing {

    private Hashing() {
    }

    /** The SHA-1 of the parts, concatenated. */
    public static byte[] sha1(byte[]... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available on this JVM", e);
        }
        for (byte[] part : parts) {
            digest.update(part);
        }
        return digest.digest();
    }
}
