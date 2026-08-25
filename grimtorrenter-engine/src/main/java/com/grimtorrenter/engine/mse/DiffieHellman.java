package com.grimtorrenter.engine.mse;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Diffie-Hellman key exchange over Message Stream Encryption's fixed 768-bit prime/generator
 * (design_docs/0052) - the same RFC 2409 Oakley Group 1 prime the original MSE spec itself
 * specifies, not a value this project chose. Uses {@link BigInteger#modPow}, a JDK primitive,
 * for the actual modular exponentiation - no external crypto dependency needed, see
 * design_docs/0052's rationale.
 */
public final class DiffieHellman {

    /** RFC 2409 Oakley Group 1's 768-bit MODP prime - MSE's specified P. Verified against
     * RFC 2409 section 6.1 directly (a first attempt at this constant accidentally pasted
     * RFC 3526 Group 14's 2048-bit prime past a shared prefix - the two primes share a long
     * common prefix by construction, which is exactly why that mistake wasn't visually
     * obvious - so this value's bit length is asserted in DiffieHellmanTest rather than
     * trusted on sight). */
    public static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A0"
            + "8798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63"
            + "A3620FFFFFFFFFFFFFFFF",
            16);

    /** MSE's specified generator. */
    public static final BigInteger G = BigInteger.valueOf(2);

    /** P is 768 bits = 96 bytes - every public key is sent/parsed as exactly this many bytes,
     * zero-padded, so the receiving side can read a fixed-length field rather than needing a
     * length prefix. */
    public static final int PUBLIC_KEY_LENGTH_BYTES = 96;

    /** Matches the private-key length real MSE implementations commonly use - large enough
     * relative to P's own (dated, spec-fixed) strength that a bigger private exponent buys
     * nothing, small enough to keep modPow cheap (design_docs/0052's stability note: this
     * cost is bounded per-connection, not a scaling concern). */
    private static final int PRIVATE_KEY_BITS = 160;

    private final BigInteger privateKey;
    private final BigInteger publicKey;

    public DiffieHellman(SecureRandom random) {
        this.privateKey = new BigInteger(PRIVATE_KEY_BITS, random);
        this.publicKey = G.modPow(privateKey, P);
    }

    /** Our public key (Ya/Yb in the spec's notation), as a fixed-length big-endian byte array
     * ready to send to the peer. */
    public byte[] publicKeyBytes() {
        return toFixedLengthBytes(publicKey);
    }

    /** Combines the peer's public key with our own private key to derive the shared secret S -
     * both sides compute the same value without either ever exposing their private key. */
    public BigInteger sharedSecret(BigInteger peerPublicKey) {
        return peerPublicKey.modPow(privateKey, P);
    }

    /** The shared secret S, as the same fixed-length big-endian encoding {@link #publicKeyBytes()}
     * uses - MSE's HASH derivations (req1/req2/req3/keyA/keyB) all concatenate S in this form. */
    public byte[] sharedSecretBytes(BigInteger peerPublicKey) {
        return toFixedLengthBytes(sharedSecret(peerPublicKey));
    }

    /** Parses a peer's public key from the fixed-length wire format {@link #publicKeyBytes()}
     * produces - always non-negative (there is no sign bit on the wire). */
    public static BigInteger publicKeyFromBytes(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

    private static byte[] toFixedLengthBytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[PUBLIC_KEY_LENGTH_BYTES];
        int rawStart = Math.max(0, raw.length - PUBLIC_KEY_LENGTH_BYTES);
        int rawLength = raw.length - rawStart;
        System.arraycopy(raw, rawStart, fixed, PUBLIC_KEY_LENGTH_BYTES - rawLength, rawLength);
        return fixed;
    }
}
