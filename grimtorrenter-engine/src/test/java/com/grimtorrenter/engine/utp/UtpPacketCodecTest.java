package com.grimtorrenter.engine.utp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** design_docs/0028's own "both directions, not just round-trips" convention (see
 * dht/KrpcCodecTest.java) - each happy-path case builds the expected wire bytes by hand and
 * asserts encode() and decode() independently, rather than only checking that encode(decode(x))
 * or decode(encode(x)) is a no-op (which a bug in both directions could pass by canceling out). */
class UtpPacketCodecTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    @Test
    void roundTripsADataPacketAgainstHandBuiltBytes() {
        UtpPacket packet = new UtpPacket(UtpPacketType.DATA, 0x1234, 0x01020304L, 0x0A0B0C0DL, 0x00010000L, 0x0005,
                0x0009, bytes(0x41, 0x42, 0x43));

        byte[] expected = bytes(
                0x01, 0x00,             // type=DATA(0)<<4 | version=1, extension=0
                0x12, 0x34,             // connection_id
                0x01, 0x02, 0x03, 0x04, // timestamp_microseconds
                0x0A, 0x0B, 0x0C, 0x0D, // timestamp_difference_microseconds
                0x00, 0x01, 0x00, 0x00, // wnd_size
                0x00, 0x05,             // seq_nr
                0x00, 0x09,             // ack_nr
                0x41, 0x42, 0x43);      // payload "ABC"

        assertArrayEquals(expected, UtpPacketCodec.encode(packet));
        assertEquals(packet, UtpPacketCodec.decode(expected));
    }

    @Test
    void roundTripsASynPacketWithNoPayloadAgainstHandBuiltBytes() {
        UtpPacket packet = new UtpPacket(UtpPacketType.SYN, 0xBEEF, 0, 0, 0, 0x0001, 0, new byte[0]);

        byte[] expected = bytes(
                0x41, 0x00,             // type=SYN(4)<<4 | version=1, extension=0
                0xBE, 0xEF,             // connection_id
                0x00, 0x00, 0x00, 0x00, // timestamp_microseconds
                0x00, 0x00, 0x00, 0x00, // timestamp_difference_microseconds
                0x00, 0x00, 0x00, 0x00, // wnd_size
                0x00, 0x01,             // seq_nr
                0x00, 0x00);            // ack_nr

        assertArrayEquals(expected, UtpPacketCodec.encode(packet));
        assertEquals(packet, UtpPacketCodec.decode(expected));
    }

    /** Confirms decode() walks and discards an extension chain to find the correct payload
     * offset, even though this codec never writes one itself (design_docs/0074's slice 5 -
     * selective ack) - decode-only, since encode() has nothing to round-trip against here. */
    @Test
    void decodeSkipsAnIgnoredSelectiveAckExtensionToFindThePayload() {
        byte[] wireBytes = concat(
                bytes(
                        0x21, 0x01,             // type=STATE(2)<<4 | version=1, extension=1 (first ext is type 1)
                        0x00, 0x02,             // connection_id
                        0x00, 0x00, 0x00, 0x00, // timestamp_microseconds
                        0x00, 0x00, 0x00, 0x00, // timestamp_difference_microseconds
                        0x00, 0x00, 0x00, 0x00, // wnd_size
                        0x00, 0x0A,             // seq_nr
                        0x00, 0x0B),            // ack_nr
                bytes(0x00, 0x04),              // extension block: next_extension=0, len=4
                bytes(0xFF, 0xFF, 0xFF, 0xFF),  // extension data (a bitmask, ignored)
                bytes(0x41, 0x42));              // real payload "AB"

        UtpPacket decoded = UtpPacketCodec.decode(wireBytes);

        assertEquals(UtpPacketType.STATE, decoded.type());
        assertEquals(2, decoded.connectionId());
        assertEquals(10, decoded.seqNr());
        assertEquals(11, decoded.ackNr());
        assertArrayEquals(bytes(0x41, 0x42), decoded.payload());
    }

    @Test
    void rejectsAPacketShorterThanTheFixedHeader() {
        byte[] tooShort = new byte[19];

        assertThrows(UtpException.class, () -> UtpPacketCodec.decode(tooShort));
    }

    @Test
    void rejectsAnUnknownPacketType() {
        byte[] data = new byte[20];
        data[0] = (byte) 0xF1; // type=15 (unknown), version=1

        assertThrows(UtpException.class, () -> UtpPacketCodec.decode(data));
    }

    @Test
    void rejectsAnUnsupportedVersion() {
        byte[] data = new byte[20];
        data[0] = 0x02; // type=DATA(0), version=2

        assertThrows(UtpException.class, () -> UtpPacketCodec.decode(data));
    }

    @Test
    void rejectsAnExtensionHeaderThatRunsPastTheEndOfThePacket() {
        byte[] data = new byte[20];
        data[1] = 0x01; // extension = 1, but no extension block bytes follow

        assertThrows(UtpException.class, () -> UtpPacketCodec.decode(data));
    }

    @Test
    void rejectsAnExtensionDeclaringMoreDataThanRemainsInThePacket() {
        byte[] data = concat(new byte[20], bytes(0x00, 0x05)); // extension chain: next=0, len=5, but 0 bytes follow
        data[1] = 0x01;

        assertThrows(UtpException.class, () -> UtpPacketCodec.decode(data));
    }

    @Test
    void encodeRejectsAConnectionIdOutsideUint16Range() {
        UtpPacket packet = new UtpPacket(UtpPacketType.STATE, 0x10000, 0, 0, 0, 0, 0, new byte[0]);

        assertThrows(UtpException.class, () -> UtpPacketCodec.encode(packet));
    }
}
