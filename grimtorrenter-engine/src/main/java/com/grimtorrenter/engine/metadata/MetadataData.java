package com.grimtorrenter.engine.metadata;

import java.util.Arrays;
import java.util.Objects;

/**
 * msg_type 1 - the requested piece's bytes. {@code totalSize} is the sender's claimed
 * total metadata size, repeated on every "data" message (not just once) - real clients
 * re-check it every time rather than trusting the first value seen, and so does
 * MetadataFetcher.
 */
public record MetadataData(int piece, int totalSize, byte[] block) implements UtMetadataMessage {

    public MetadataData {
        block = block.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MetadataData other)) {
            return false;
        }
        return piece == other.piece && totalSize == other.totalSize && Arrays.equals(block, other.block);
    }

    @Override
    public int hashCode() {
        return Objects.hash(piece, totalSize, Arrays.hashCode(block));
    }
}
