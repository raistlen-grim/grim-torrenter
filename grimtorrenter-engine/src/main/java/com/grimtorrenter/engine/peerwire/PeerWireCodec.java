package com.grimtorrenter.engine.peerwire;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerId;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads/writes handshakes and messages against InputStream/OutputStream -
 * not java.net.Socket directly, so this stays testable with plain byte
 * streams and reusable by the peer layer wrapping a real socket later.
 *
 * <p>I/O failures (connection reset, stream closed) propagate as the
 * underlying IOException unchanged - callers need to distinguish transient
 * I/O problems (worth retrying) from protocol corruption. Malformed-but-
 * successfully-read data (bad protocol name, unknown message id, wrong
 * payload length) throws the unchecked PeerWireException instead, matching
 * how bencode/metainfo/tracker treat malformed input elsewhere in this
 * codebase - the two exception styles here mean different things, not an
 * inconsistency.
 */
public final class PeerWireCodec {

    private PeerWireCodec() {
    }

    public static void writeHandshake(OutputStream out, Handshake handshake) throws IOException {
        byte[] pstrBytes = Handshake.PROTOCOL_NAME.getBytes(StandardCharsets.US_ASCII);
        out.write(pstrBytes.length);
        out.write(pstrBytes);
        out.write(handshake.reserved());
        out.write(handshake.infoHash().bytes());
        out.write(handshake.peerId().bytes());
        out.flush();
    }

    public static Handshake readHandshake(InputStream in) throws IOException {
        int pstrlen = readByteOrThrow(in);
        String pstr = new String(readFully(in, pstrlen), StandardCharsets.US_ASCII);
        if (!Handshake.PROTOCOL_NAME.equals(pstr)) {
            throw new PeerWireException("Unexpected protocol name: '" + pstr + "'");
        }
        byte[] reserved = readFully(in, Handshake.RESERVED_LENGTH);
        InfoHash infoHash = InfoHash.of(readFully(in, InfoHash.LENGTH_BYTES));
        PeerId peerId = PeerId.of(readFully(in, PeerId.LENGTH_BYTES));
        return new Handshake(reserved, infoHash, peerId);
    }

    public static void writeMessage(OutputStream out, PeerMessage message) throws IOException {
        switch (message) {
            case KeepAlive ignored -> out.write(intToBytes(0));
            case Choke ignored -> writeFixed(out, 0);
            case Unchoke ignored -> writeFixed(out, 1);
            case Interested ignored -> writeFixed(out, 2);
            case NotInterested ignored -> writeFixed(out, 3);
            case Have h -> writeWithPayload(out, 4, intToBytes(h.pieceIndex()));
            case Bitfield b -> writeWithPayload(out, 5, b.bits());
            case Request r -> writeWithPayload(out, 6,
                    concat(intToBytes(r.index()), intToBytes(r.begin()), intToBytes(r.length())));
            case Piece p -> writeWithPayload(out, 7,
                    concat(intToBytes(p.index()), intToBytes(p.begin()), p.block()));
            case Cancel c -> writeWithPayload(out, 8,
                    concat(intToBytes(c.index()), intToBytes(c.begin()), intToBytes(c.length())));
            case Port p -> writeWithPayload(out, 9,
                    new byte[]{(byte) (p.listenPort() >> 8), (byte) p.listenPort()});
            case Extended e -> writeWithPayload(out, 20,
                    concat(new byte[]{(byte) e.extendedMessageId()}, e.payload()));
        }
        out.flush();
    }

    public static PeerMessage readMessage(InputStream in) throws IOException {
        int length = ByteBuffer.wrap(readFully(in, 4)).getInt();
        if (length == 0) {
            return new KeepAlive();
        }
        int messageId = readByteOrThrow(in);
        byte[] payload = readFully(in, length - 1);
        return decodePayload(messageId, payload);
    }

    private static PeerMessage decodePayload(int messageId, byte[] payload) {
        return switch (messageId) {
            case 0 -> {
                requireLength(payload, 0, "choke");
                yield new Choke();
            }
            case 1 -> {
                requireLength(payload, 0, "unchoke");
                yield new Unchoke();
            }
            case 2 -> {
                requireLength(payload, 0, "interested");
                yield new Interested();
            }
            case 3 -> {
                requireLength(payload, 0, "not interested");
                yield new NotInterested();
            }
            case 4 -> {
                requireLength(payload, 4, "have");
                yield new Have(ByteBuffer.wrap(payload).getInt());
            }
            case 5 -> new Bitfield(payload);
            case 6 -> {
                requireLength(payload, 12, "request");
                ByteBuffer buf = ByteBuffer.wrap(payload);
                yield new Request(buf.getInt(), buf.getInt(), buf.getInt());
            }
            case 7 -> decodePiece(payload);
            case 8 -> {
                requireLength(payload, 12, "cancel");
                ByteBuffer buf = ByteBuffer.wrap(payload);
                yield new Cancel(buf.getInt(), buf.getInt(), buf.getInt());
            }
            case 9 -> {
                requireLength(payload, 2, "port");
                yield new Port(ByteBuffer.wrap(payload).getShort() & 0xFFFF);
            }
            case 20 -> decodeExtended(payload);
            default -> throw new PeerWireException("Unknown message id " + messageId);
        };
    }

    private static Extended decodeExtended(byte[] payload) {
        if (payload.length < 1) {
            throw new PeerWireException("extended payload too short: " + payload.length + " bytes");
        }
        int extendedMessageId = payload[0] & 0xFF;
        return new Extended(extendedMessageId, Arrays.copyOfRange(payload, 1, payload.length));
    }

    private static Piece decodePiece(byte[] payload) {
        if (payload.length < 8) {
            throw new PeerWireException("piece payload too short: " + payload.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int index = buf.getInt();
        int begin = buf.getInt();
        byte[] block = new byte[buf.remaining()];
        buf.get(block);
        return new Piece(index, begin, block);
    }

    private static void requireLength(byte[] payload, int expected, String messageName) {
        if (payload.length != expected) {
            throw new PeerWireException(
                    messageName + " payload must be " + expected + " bytes, got " + payload.length);
        }
    }

    private static void writeFixed(OutputStream out, int messageId) throws IOException {
        out.write(intToBytes(1));
        out.write(messageId);
    }

    private static void writeWithPayload(OutputStream out, int messageId, byte[] payload) throws IOException {
        out.write(intToBytes(1 + payload.length));
        out.write(messageId);
        out.write(payload);
    }

    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static int readByteOrThrow(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Unexpected end of stream");
        }
        return b;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new EOFException("Expected " + length + " bytes, got " + data.length);
        }
        return data;
    }
}
