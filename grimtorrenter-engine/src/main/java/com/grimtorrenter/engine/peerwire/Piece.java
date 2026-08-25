package com.grimtorrenter.engine.peerwire;

import java.util.Arrays;
import java.util.Objects;

public record Piece(int index, int begin, byte[] block) implements PeerMessage {

    public Piece {
        block = block.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Piece other)) {
            return false;
        }
        return index == other.index && begin == other.begin && Arrays.equals(block, other.block);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, begin, Arrays.hashCode(block));
    }
}
