package com.grimtorrenter.engine.magnet;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base32Test {

    @Test
    void decodesAllZeroBytes() {
        assertArrayEquals(new byte[20], Base32.decode("A".repeat(32)));
    }

    @Test
    void decodesAll0xffBytes() {
        byte[] expected = new byte[20];
        Arrays.fill(expected, (byte) 0xFF);
        assertArrayEquals(expected, Base32.decode("7".repeat(32)));
    }

    @Test
    void isCaseInsensitive() {
        assertArrayEquals(Base32.decode("A".repeat(32)), Base32.decode("a".repeat(32)));
    }

    @Test
    void decodesAKnownMixedValue() {
        // I=8 (01000), E=4 (00100) -> the first 10 bits are 0100000100: byte 0 is the
        // top 8 (01000001 = 0x41), leaving 2 leftover zero bits that merge with the
        // remaining 30 zero-value 'A' characters, so every following byte is 0x00.
        byte[] decoded = Base32.decode("IE" + "A".repeat(30));
        assertArrayEquals(HexFormat.of().parseHex("41" + "00".repeat(19)), decoded);
    }

    @Test
    void rejectsInvalidCharacters() {
        assertThrows(MagnetLinkException.class, () -> Base32.decode("1".repeat(32)));
    }
}
