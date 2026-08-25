package com.grimtorrenter.engine.metainfo;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The concatenated SHA-1 piece hashes from the info dictionary's "pieces"
 * field. Kept as a raw byte[] (unlike BString/InfoHash) since this is a
 * binary buffer indexed and sliced by piece number, not a value compared
 * for identity as a whole - equals/hashCode are overridden manually
 * instead of using the String-backing trick.
 */
public record PieceHashes(byte[] concatenated) {

    public static final int HASH_LENGTH = 20;

    public PieceHashes {
        if (concatenated.length % HASH_LENGTH != 0) {
            throw new MetainfoException(
                    "'pieces' length " + concatenated.length + " is not a multiple of " + HASH_LENGTH);
        }
        concatenated = concatenated.clone();
    }

    public int count() {
        return concatenated.length / HASH_LENGTH;
    }

    public byte[] hashAt(int index) {
        if (index < 0 || index >= count()) {
            throw new IndexOutOfBoundsException("Piece index " + index + " out of range [0," + count() + ")");
        }
        int start = index * HASH_LENGTH;
        return Arrays.copyOfRange(concatenated, start, start + HASH_LENGTH);
    }

    public boolean matches(int index, byte[] candidateHash) {
        return MessageDigest.isEqual(hashAt(index), candidateHash);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PieceHashes other)) {
            return false;
        }
        return Arrays.equals(concatenated, other.concatenated);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(concatenated);
    }
}
