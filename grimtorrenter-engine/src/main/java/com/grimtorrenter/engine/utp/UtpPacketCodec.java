package com.grimtorrenter.engine.utp;

import java.util.Arrays;

/**
 * BEP 29 wire format encode/decode - the 20-byte fixed header described in design_docs/0074's
 * "Wire format" section, plus payload. Manual big-endian shift/mask packing, matching
 * dht/CompactPeers.java's and dht/CompactNodes.java's existing convention for fixed-width binary
 * sub-fields in this codebase, rather than java.nio.ByteBuffer.
 */
public final class UtpPacketCodec {

    private static final int VERSION = 1;

    private static final int TYPE_VERSION_OFFSET = 0;
    private static final int EXTENSION_OFFSET = 1;
    private static final int CONNECTION_ID_OFFSET = 2;
    private static final int TIMESTAMP_OFFSET = 4;
    private static final int TIMESTAMP_DIFFERENCE_OFFSET = 8;
    private static final int WINDOW_SIZE_OFFSET = 12;
    private static final int SEQ_NR_OFFSET = 16;
    private static final int ACK_NR_OFFSET = 18;
    private static final int HEADER_LENGTH = 20;

    private static final long UINT32_MAX = 0xFFFFFFFFL;
    private static final int UINT16_MAX = 0xFFFF;

    private UtpPacketCodec() {
    }

    public static byte[] encode(UtpPacket packet) {
        requireUint16(packet.connectionId(), "connectionId");
        requireUint16(packet.seqNr(), "seqNr");
        requireUint16(packet.ackNr(), "ackNr");
        requireUint32(packet.timestampMicros(), "timestampMicros");
        requireUint32(packet.timestampDifferenceMicros(), "timestampDifferenceMicros");
        requireUint32(packet.windowSize(), "windowSize");

        byte[] out = new byte[HEADER_LENGTH + packet.payload().length];
        out[TYPE_VERSION_OFFSET] = (byte) ((packet.type().value() << 4) | VERSION);
        out[EXTENSION_OFFSET] = 0;
        writeUint16(out, CONNECTION_ID_OFFSET, packet.connectionId());
        writeUint32(out, TIMESTAMP_OFFSET, packet.timestampMicros());
        writeUint32(out, TIMESTAMP_DIFFERENCE_OFFSET, packet.timestampDifferenceMicros());
        writeUint32(out, WINDOW_SIZE_OFFSET, packet.windowSize());
        writeUint16(out, SEQ_NR_OFFSET, packet.seqNr());
        writeUint16(out, ACK_NR_OFFSET, packet.ackNr());
        System.arraycopy(packet.payload(), 0, out, HEADER_LENGTH, packet.payload().length);
        return out;
    }

    /** Walks and discards any extension chain present purely to find the correct payload
     * offset - this side never acts on extension data (no selective-ack support yet, see
     * design_docs/0074's own slice 5), but a real peer may still send one (many real
     * implementations always attach selective-ack to their ST_STATE packets), and skipping it
     * incorrectly would misread every field after it as payload. */
    public static UtpPacket decode(byte[] data) {
        if (data.length < HEADER_LENGTH) {
            throw new UtpException("uTP packet too short: " + data.length + " bytes, need at least " + HEADER_LENGTH);
        }
        int version = data[TYPE_VERSION_OFFSET] & 0x0F;
        if (version != VERSION) {
            throw new UtpException("Unsupported uTP version: " + version);
        }
        UtpPacketType type = UtpPacketType.fromValue((data[TYPE_VERSION_OFFSET] >> 4) & 0x0F);
        int connectionId = readUint16(data, CONNECTION_ID_OFFSET);
        long timestampMicros = readUint32(data, TIMESTAMP_OFFSET);
        long timestampDifferenceMicros = readUint32(data, TIMESTAMP_DIFFERENCE_OFFSET);
        long windowSize = readUint32(data, WINDOW_SIZE_OFFSET);
        int seqNr = readUint16(data, SEQ_NR_OFFSET);
        int ackNr = readUint16(data, ACK_NR_OFFSET);

        int offset = HEADER_LENGTH;
        int extensionType = data[EXTENSION_OFFSET] & 0xFF;
        while (extensionType != 0) {
            if (offset + 2 > data.length) {
                throw new UtpException("uTP extension header (type " + extensionType + ") runs past the end of the packet");
            }
            int nextExtensionType = data[offset] & 0xFF;
            int length = data[offset + 1] & 0xFF;
            offset += 2;
            if (offset + length > data.length) {
                throw new UtpException("uTP extension (type " + extensionType + ") declares length " + length
                        + " but only " + (data.length - offset) + " bytes remain");
            }
            offset += length;
            extensionType = nextExtensionType;
        }

        byte[] payload = Arrays.copyOfRange(data, offset, data.length);
        return new UtpPacket(type, connectionId, timestampMicros, timestampDifferenceMicros, windowSize, seqNr, ackNr,
                payload);
    }

    /** DhtNode's own first-line test (design_docs/0074's slice 3) for demuxing a shared UDP
     * socket's incoming datagrams before deciding whether to hand one to KrpcCodec.decode() or
     * this class instead. A positive match on both header nibbles - version is exactly VERSION,
     * type is a value UtpPacketType actually defines - rather than merely "isn't bencode's
     * leading 'd' (0x64)", so a malformed/foreign datagram on this socket is equally unlikely to
     * be misrouted either way. */
    public static boolean looksLikeUtpPacket(byte[] data) {
        if (data.length < HEADER_LENGTH) {
            return false;
        }
        int version = data[TYPE_VERSION_OFFSET] & 0x0F;
        if (version != VERSION) {
            return false;
        }
        int typeValue = (data[TYPE_VERSION_OFFSET] >> 4) & 0x0F;
        try {
            UtpPacketType.fromValue(typeValue);
            return true;
        } catch (UtpException e) {
            return false;
        }
    }

    private static void requireUint16(int value, String field) {
        if (value < 0 || value > UINT16_MAX) {
            throw new UtpException("uTP " + field + " out of uint16 range: " + value);
        }
    }

    private static void requireUint32(long value, String field) {
        if (value < 0 || value > UINT32_MAX) {
            throw new UtpException("uTP " + field + " out of uint32 range: " + value);
        }
    }

    private static void writeUint16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >> 8);
        out[offset + 1] = (byte) value;
    }

    private static void writeUint32(byte[] out, int offset, long value) {
        out[offset] = (byte) (value >> 24);
        out[offset + 1] = (byte) (value >> 16);
        out[offset + 2] = (byte) (value >> 8);
        out[offset + 3] = (byte) value;
    }

    private static int readUint16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static long readUint32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
