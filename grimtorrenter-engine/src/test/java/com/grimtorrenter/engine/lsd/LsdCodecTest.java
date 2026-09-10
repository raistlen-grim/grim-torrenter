package com.grimtorrenter.engine.lsd;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LsdCodecTest {

    private static InfoHash infoHashOf(int seed) {
        byte[] bytes = new byte[20];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return InfoHash.of(bytes);
    }

    @Test
    void encodesAndDecodesRoundTrip() {
        LsdMessage original = new LsdMessage(infoHashOf(1), 51413, "abc123cookie");

        LsdMessage decoded = LsdCodec.decode(LsdCodec.encode(original));

        assertEquals(original, decoded);
    }

    @Test
    void decodingNormalizesUppercaseInfoHashToTheSameLowercaseCanonicalForm() {
        InfoHash canonical = infoHashOf(1);
        byte[] payload = ("BT-SEARCH * HTTP/1.1\r\n"
                + "Host: 239.192.152.143:6771\r\n"
                + "Port: 6881\r\n"
                + "Infohash: " + canonical.hex().toUpperCase() + "\r\n"
                + "cookie: xyz\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        LsdMessage decoded = LsdCodec.decode(payload);

        // Must equal the lowercase-keyed InfoHash real sessions are stored under, not merely
        // "some InfoHash with the same characters, differently cased" - see LsdCodec.decode's
        // own comment on why this routes through InfoHash.of(byte[]).
        assertEquals(canonical, decoded.infoHash());
    }

    @Test
    void decodingToleratesMixedHeaderCasing() {
        byte[] payload = ("BT-SEARCH * HTTP/1.1\r\n"
                + "host: 239.192.152.143:6771\r\n"
                + "PORT: 6881\r\n"
                + "infoHASH: " + infoHashOf(1).hex() + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        LsdMessage decoded = LsdCodec.decode(payload);

        assertEquals(6881, decoded.port());
        assertEquals(infoHashOf(1), decoded.infoHash());
        assertEquals("", decoded.cookie());
    }

    @Test
    void decodingSomethingNotStartingWithTheRequestLineThrows() {
        byte[] payload = "GARBAGE\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        assertThrows(LsdException.class, () -> LsdCodec.decode(payload));
    }

    @Test
    void decodingWithoutAPortHeaderThrows() {
        byte[] payload = ("BT-SEARCH * HTTP/1.1\r\n"
                + "Infohash: " + infoHashOf(1).hex() + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        assertThrows(LsdException.class, () -> LsdCodec.decode(payload));
    }

    @Test
    void decodingWithoutAnInfohashHeaderThrows() {
        byte[] payload = ("BT-SEARCH * HTTP/1.1\r\n"
                + "Port: 6881\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        assertThrows(LsdException.class, () -> LsdCodec.decode(payload));
    }

    @Test
    void decodingANonHexInfohashThrows() {
        byte[] payload = ("BT-SEARCH * HTTP/1.1\r\n"
                + "Port: 6881\r\n"
                + "Infohash: not-hex-at-all-zzzzzzzzzzzzzzzzzzzz\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        assertThrows(LsdException.class, () -> LsdCodec.decode(payload));
    }

    @Test
    void decodingANonNumericPortThrows() {
        byte[] payload = ("BT-SEARCH * HTTP/1.1\r\n"
                + "Port: not-a-number\r\n"
                + "Infohash: " + infoHashOf(1).hex() + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        assertThrows(LsdException.class, () -> LsdCodec.decode(payload));
    }
}
