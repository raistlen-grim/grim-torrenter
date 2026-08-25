package com.grimtorrenter.engine.bencode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BencodeDecoderTest {

    private static byte[] bytes(String ascii) {
        return ascii.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void decodesPositiveInteger() {
        assertEquals(new BInteger(42), BencodeDecoder.decode(bytes("i42e")));
    }

    @Test
    void decodesNegativeInteger() {
        assertEquals(new BInteger(-42), BencodeDecoder.decode(bytes("i-42e")));
    }

    @Test
    void decodesZero() {
        assertEquals(new BInteger(0), BencodeDecoder.decode(bytes("i0e")));
    }

    @Test
    void isLenientAboutNonCanonicalLeadingZero() {
        assertEquals(new BInteger(42), BencodeDecoder.decode(bytes("i042e")));
    }

    @Test
    void decodesEmptyString() {
        assertEquals(BString.of(""), BencodeDecoder.decode(bytes("0:")));
    }

    @Test
    void decodesAsciiString() {
        assertEquals(BString.of("spam"), BencodeDecoder.decode(bytes("4:spam")));
    }

    @Test
    void roundTripsArbitraryBinaryBytes() {
        byte[] hash = new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0x80, 'x'};
        byte[] encoded = new byte[bytes("6:").length + hash.length];
        System.arraycopy(bytes("6:"), 0, encoded, 0, bytes("6:").length);
        System.arraycopy(hash, 0, encoded, bytes("6:").length, hash.length);

        BValue decoded = BencodeDecoder.decode(encoded);
        assertArrayEquals(hash, ((BString) decoded).bytes());
    }

    @Test
    void decodesEmptyList() {
        assertEquals(new BList(List.of()), BencodeDecoder.decode(bytes("le")));
    }

    @Test
    void decodesNestedList() {
        BValue decoded = BencodeDecoder.decode(bytes("l4:spami42ee"));
        assertEquals(new BList(List.of(BString.of("spam"), new BInteger(42))), decoded);
    }

    @Test
    void decodesDictionary() {
        BValue decoded = BencodeDecoder.decode(bytes("d3:bar4:spam3:fooi42ee"));
        BDictionary expected = new BDictionary(Map.of(
                BString.of("bar"), BString.of("spam"),
                BString.of("foo"), new BInteger(42)));
        assertEquals(expected, decoded);
    }

    @Test
    void dictionaryIteratesInSortedKeyOrderRegardlessOfInputOrder() {
        // "foo" sorts after "bar" - input already gives them in that order,
        // but BDictionary must guarantee it even if a source encoded them
        // out of order.
        BDictionary decoded = (BDictionary) BencodeDecoder.decode(bytes("d3:fooi1e3:bari2ee"));
        assertEquals(List.of(BString.of("bar"), BString.of("foo")),
                List.copyOf(decoded.entries().keySet()));
    }

    @Test
    void rejectsTrailingData() {
        assertThrows(BencodeException.class, () -> BencodeDecoder.decode(bytes("i1ei2e")));
    }

    @Test
    void decodePrefixStopsAfterOneValueAndReportsBytesConsumed() {
        byte[] data = bytes("i1ei2e");
        BencodeDecoder.Prefix prefix = BencodeDecoder.decodePrefix(data);

        assertEquals(new BInteger(1), prefix.value());
        assertEquals(3, prefix.bytesConsumed());
    }

    @Test
    void decodePrefixOnDictFollowedByRawTrailingBytes() {
        // Mirrors BEP 9's ut_metadata "data" message shape: a dict immediately followed
        // by raw (non-bencoded) bytes with no delimiter between them.
        byte[] dict = bytes("d3:fooi1ee");
        byte[] raw = {1, 2, 3, 4};
        byte[] data = new byte[dict.length + raw.length];
        System.arraycopy(dict, 0, data, 0, dict.length);
        System.arraycopy(raw, 0, data, dict.length, raw.length);

        BencodeDecoder.Prefix prefix = BencodeDecoder.decodePrefix(data);

        assertEquals(dict.length, prefix.bytesConsumed());
        assertArrayEquals(raw, Arrays.copyOfRange(data, prefix.bytesConsumed(), data.length));
    }

    @Test
    void rejectsTruncatedString() {
        assertThrows(BencodeException.class, () -> BencodeDecoder.decode(bytes("10:short")));
    }

    @Test
    void rejectsUnterminatedInteger() {
        assertThrows(BencodeException.class, () -> BencodeDecoder.decode(bytes("i42")));
    }

    @Test
    void rejectsMalformedInteger() {
        assertThrows(BencodeException.class, () -> BencodeDecoder.decode(bytes("iabce")));
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(BencodeException.class, () -> BencodeDecoder.decode(new byte[0]));
    }

    @Test
    void rejectsUnknownMarker() {
        assertThrows(BencodeException.class, () -> BencodeDecoder.decode(bytes("x")));
    }
}
