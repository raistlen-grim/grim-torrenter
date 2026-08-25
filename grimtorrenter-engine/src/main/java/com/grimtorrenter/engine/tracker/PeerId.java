package com.grimtorrenter.engine.tracker;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * A 20-byte BitTorrent peer id (ours, or a remote peer's). Same
 * hex-string-backed shape as InfoHash for the same reason - correct
 * equals/hashCode without the records-plus-byte[] pitfall. Deliberately
 * not sharing an abstraction with InfoHash: exactly two occurrences of
 * this shape exist in the codebase, and they're semantically distinct
 * identities (a torrent's identity vs a client's) that should stay
 * type-distinct rather than be interchangeable.
 */
public record PeerId(String hex) {

    public static final int LENGTH_BYTES = 20;

    public PeerId {
        if (hex.length() != LENGTH_BYTES * 2) {
            throw new IllegalArgumentException("Peer id hex must be " + (LENGTH_BYTES * 2) + " chars");
        }
    }

    /** Azureus-style convention: a client identifying prefix followed by random bytes. */
    public static PeerId generate() {
        byte[] prefix = "-GT0100-".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[LENGTH_BYTES];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        byte[] random = new byte[LENGTH_BYTES - prefix.length];
        new SecureRandom().nextBytes(random);
        System.arraycopy(random, 0, bytes, prefix.length, random.length);
        return of(bytes);
    }

    public static PeerId of(byte[] bytes) {
        if (bytes.length != LENGTH_BYTES) {
            throw new IllegalArgumentException("Peer id must be " + LENGTH_BYTES + " bytes, got " + bytes.length);
        }
        return new PeerId(HexFormat.of().formatHex(bytes));
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(hex);
    }

    @Override
    public String toString() {
        return hex;
    }
}
