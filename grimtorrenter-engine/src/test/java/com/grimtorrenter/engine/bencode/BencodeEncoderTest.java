package com.grimtorrenter.engine.bencode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BencodeEncoderTest {

    private static byte[] bytes(String ascii) {
        return ascii.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void encodesInteger() {
        assertArrayEquals(bytes("i42e"), BencodeEncoder.encode(new BInteger(42)));
    }

    @Test
    void encodesNegativeInteger() {
        assertArrayEquals(bytes("i-42e"), BencodeEncoder.encode(new BInteger(-42)));
    }

    @Test
    void encodesString() {
        assertArrayEquals(bytes("4:spam"), BencodeEncoder.encode(BString.of("spam")));
    }

    @Test
    void encodesList() {
        byte[] encoded = BencodeEncoder.encode(new BList(List.of(BString.of("spam"), new BInteger(42))));
        assertArrayEquals(bytes("l4:spami42ee"), encoded);
    }

    @Test
    void encodesDictionaryInSortedKeyOrderRegardlessOfInputOrder() {
        // Built with "foo" before "bar" - output must still be sorted.
        BDictionary dict = new BDictionary(Map.of(
                BString.of("foo"), new BInteger(1),
                BString.of("bar"), new BInteger(2)));

        byte[] encoded = BencodeEncoder.encode(dict);

        assertArrayEquals(bytes("d3:bari2e3:fooi1ee"), encoded);
    }

    @Test
    void roundTripsThroughDecoder() {
        byte[] original = bytes("d3:bar4:spam3:fooli1ei2eee");
        BValue decoded = BencodeDecoder.decode(original);
        byte[] reencoded = BencodeEncoder.encode(decoded);
        assertArrayEquals(original, reencoded);
    }
}
