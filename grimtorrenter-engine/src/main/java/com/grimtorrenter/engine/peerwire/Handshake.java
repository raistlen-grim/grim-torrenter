package com.grimtorrenter.engine.peerwire;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerId;

import java.util.Arrays;
import java.util.Objects;

/**
 * The one-time connection preamble - a fixed 68-byte structure, not part
 * of the length-prefixed PeerMessage stream that follows it. Modeled
 * separately from PeerMessage for that reason.
 */
public record Handshake(byte[] reserved, InfoHash infoHash, PeerId peerId) {

    public static final String PROTOCOL_NAME = "BitTorrent protocol";
    public static final int RESERVED_LENGTH = 8;

    public Handshake {
        if (reserved.length != RESERVED_LENGTH) {
            throw new IllegalArgumentException(
                    "reserved must be " + RESERVED_LENGTH + " bytes, got " + reserved.length);
        }
        reserved = reserved.clone();
    }

    /** BEP 10's bit: the 20th bit from the right of the 8 reserved bytes, per that BEP's
     * own numbering - byte index 5 (0-indexed from the left), mask 0x10. */
    private static final int EXTENSION_PROTOCOL_BYTE_INDEX = 5;
    private static final byte EXTENSION_PROTOCOL_BIT = 0x10;

    /** Reserved bytes all zero - no extension protocol bits set. */
    public static Handshake of(InfoHash infoHash, PeerId peerId) {
        return new Handshake(new byte[RESERVED_LENGTH], infoHash, peerId);
    }

    /** Advertises BEP 10 support - see design_docs/0028. */
    public static Handshake withExtensionProtocol(InfoHash infoHash, PeerId peerId) {
        byte[] reserved = new byte[RESERVED_LENGTH];
        reserved[EXTENSION_PROTOCOL_BYTE_INDEX] = EXTENSION_PROTOCOL_BIT;
        return new Handshake(reserved, infoHash, peerId);
    }

    public boolean supportsExtensionProtocol() {
        return (reserved[EXTENSION_PROTOCOL_BYTE_INDEX] & EXTENSION_PROTOCOL_BIT) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Handshake other)) {
            return false;
        }
        return Arrays.equals(reserved, other.reserved)
                && infoHash.equals(other.infoHash)
                && peerId.equals(other.peerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(reserved), infoHash, peerId);
    }
}
