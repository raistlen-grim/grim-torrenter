package com.grimtorrenter.app;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2WithHmacSHA256 password hashing - JDK built-in, no new dependency, same "hand-roll
 * rather than pull in a library" reasoning design_docs/0052 used for MSE. Encodes iterations
 * and salt alongside the hash in one self-describing string (iterations:salt:hash, all
 * base64) so AuthStore only ever has to persist a single field. See design_docs/0061.
 */
final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /** OWASP's 2023 minimum recommendation for PBKDF2-HMAC-SHA256. */
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private PasswordHasher() {
    }

    static String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        return ITERATIONS + ":" + encode(salt) + ":" + encode(hash);
    }

    /** Constant-time comparison (MessageDigest.isEqual) against the recomputed hash, not the
     * stored one directly - a naive equals() would let a timing attack narrow down the hash
     * byte by byte. */
    static boolean matches(String password, String stored) {
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) {
            return false;
        }
        int iterations = Integer.parseInt(parts[0]);
        byte[] salt = decode(parts[1]);
        byte[] expected = decode(parts[2]);
        byte[] actual = pbkdf2(password, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " should always be available on the JDK", e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
