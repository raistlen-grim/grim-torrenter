package com.grimtorrenter.engine.peerwire;

import java.util.Arrays;

/**
 * One bit per piece, MSB-first within each byte, padded with zero bits in
 * the final byte if the piece count isn't a multiple of 8. byte[]-backed
 * (equals/hashCode overridden manually) since this is a sliceable bit
 * buffer indexed by piece number, not an identity value.
 */
public record Bitfield(byte[] bits) implements PeerMessage {

    public Bitfield {
        bits = bits.clone();
    }

    /** Out-of-range indices return false rather than throwing - a peer's
     * bitfield being shorter than expected shouldn't crash the connection. */
    public boolean hasPiece(int index) {
        int byteIndex = index / 8;
        if (index < 0 || byteIndex >= bits.length) {
            return false;
        }
        int bitPosition = 7 - (index % 8);
        return ((bits[byteIndex] >> bitPosition) & 1) == 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Bitfield other)) {
            return false;
        }
        return Arrays.equals(bits, other.bits);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bits);
    }
}
