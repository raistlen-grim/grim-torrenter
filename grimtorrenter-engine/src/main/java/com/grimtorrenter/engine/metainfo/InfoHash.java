package com.grimtorrenter.engine.metainfo;

import java.util.HexFormat;

/**
 * The 20-byte SHA-1 hash identifying a torrent. Stored as a hex string
 * (not byte[]) for the same reason as BString: correct equals/hashCode for
 * free, since this value is used pervasively as an identity - tracker
 * requests, peer handshakes, session lookup keys.
 */
public record InfoHash(String hex) {

    public static final int LENGTH_BYTES = 20;

    public InfoHash {
        if (hex.length() != LENGTH_BYTES * 2) {
            throw new IllegalArgumentException("Info hash hex must be " + (LENGTH_BYTES * 2) + " chars");
        }
    }

    public static InfoHash of(byte[] bytes) {
        if (bytes.length != LENGTH_BYTES) {
            throw new IllegalArgumentException("Info hash must be " + LENGTH_BYTES + " bytes, got " + bytes.length);
        }
        return new InfoHash(HexFormat.of().formatHex(bytes));
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(hex);
    }

    @Override
    public String toString() {
        return hex;
    }
}
