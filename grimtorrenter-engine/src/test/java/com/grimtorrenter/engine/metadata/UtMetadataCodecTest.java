package com.grimtorrenter.engine.metadata;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtMetadataCodecTest {

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] fill(int length, int start) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }

    @Test
    void encodesRequestToExactBencodeBytes() {
        // {"msg_type": 0, "piece": 5}, keys sorted ("msg_type" < "piece")
        assertArrayEquals(ascii("d8:msg_typei0e5:piecei5ee"), UtMetadataCodec.encode(new MetadataRequest(5)));
    }

    @Test
    void encodesRejectToExactBencodeBytes() {
        assertArrayEquals(ascii("d8:msg_typei2e5:piecei7ee"), UtMetadataCodec.encode(new MetadataReject(7)));
    }

    @Test
    void encodesDataToExactBencodeBytesFollowedByRawBlock() {
        byte[] block = fill(4, 1);
        byte[] encoded = UtMetadataCodec.encode(new MetadataData(2, 100, block));

        byte[] expectedDict = ascii("d8:msg_typei1e5:piecei2e10:total_sizei100ee");
        assertArrayEquals(expectedDict, Arrays.copyOfRange(encoded, 0, expectedDict.length));
        assertArrayEquals(block, Arrays.copyOfRange(encoded, expectedDict.length, encoded.length));
    }

    @Test
    void decodesHandWrittenRequestBytes() {
        UtMetadataMessage message = UtMetadataCodec.decode(ascii("d8:msg_typei0e5:piecei5ee"));
        assertEquals(new MetadataRequest(5), message);
    }

    @Test
    void decodesHandWrittenRejectBytes() {
        UtMetadataMessage message = UtMetadataCodec.decode(ascii("d8:msg_typei2e5:piecei7ee"));
        assertEquals(new MetadataReject(7), message);
    }

    @Test
    void decodesHandWrittenDataBytesWithTrailingBlock() {
        byte[] dict = ascii("d8:msg_typei1e5:piecei2e10:total_sizei100ee");
        byte[] block = fill(4, 9);
        byte[] payload = new byte[dict.length + block.length];
        System.arraycopy(dict, 0, payload, 0, dict.length);
        System.arraycopy(block, 0, payload, dict.length, block.length);

        UtMetadataMessage message = UtMetadataCodec.decode(payload);

        assertTrue(message instanceof MetadataData);
        MetadataData data = (MetadataData) message;
        assertEquals(2, data.piece());
        assertEquals(100, data.totalSize());
        assertArrayEquals(block, data.block());
    }

    @Test
    void roundTripsAllThreeMessageTypes() {
        assertEquals(new MetadataRequest(1), UtMetadataCodec.decode(UtMetadataCodec.encode(new MetadataRequest(1))));
        assertEquals(new MetadataReject(1), UtMetadataCodec.decode(UtMetadataCodec.encode(new MetadataReject(1))));
        MetadataData data = new MetadataData(0, 16384, fill(16384, 3));
        assertEquals(data, UtMetadataCodec.decode(UtMetadataCodec.encode(data)));
    }

    @Test
    void rejectsNonDictionaryPayload() {
        assertThrows(UtMetadataException.class, () -> UtMetadataCodec.decode(ascii("i5e")));
    }

    @Test
    void rejectsMissingMsgType() {
        assertThrows(UtMetadataException.class, () -> UtMetadataCodec.decode(ascii("d5:piecei5ee")));
    }

    @Test
    void rejectsMissingTotalSizeOnDataMessage() {
        assertThrows(UtMetadataException.class,
                () -> UtMetadataCodec.decode(ascii("d8:msg_typei1e5:piecei2ee")));
    }

    @Test
    void rejectsUnknownMsgType() {
        assertThrows(UtMetadataException.class, () -> UtMetadataCodec.decode(ascii("d8:msg_typei9e5:piecei0ee")));
    }
}
