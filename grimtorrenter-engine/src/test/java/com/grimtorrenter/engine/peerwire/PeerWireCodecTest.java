package com.grimtorrenter.engine.peerwire;

import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.tracker.PeerId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerWireCodecTest {

    private static byte[] fill(int length, int start) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }

    private static InfoHash fakeInfoHash() {
        return InfoHash.of(fill(20, 0));
    }

    private static PeerId fakePeerId() {
        return PeerId.of(fill(20, 100));
    }

    private static PeerMessage roundTrip(PeerMessage message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PeerWireCodec.writeMessage(out, message);
        return PeerWireCodec.readMessage(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void roundTripsHandshake() throws IOException {
        Handshake handshake = Handshake.of(fakeInfoHash(), fakePeerId());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        PeerWireCodec.writeHandshake(out, handshake);
        byte[] bytes = out.toByteArray();
        assertEquals(68, bytes.length);

        Handshake decoded = PeerWireCodec.readHandshake(new ByteArrayInputStream(bytes));
        assertEquals(handshake, decoded);
    }

    @Test
    void rejectsWrongProtocolName() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] wrongName = "Not BitTorrent".getBytes();
        out.write(wrongName.length);
        out.write(wrongName);
        out.write(new byte[Handshake.RESERVED_LENGTH]);
        out.write(fakeInfoHash().bytes());
        out.write(fakePeerId().bytes());

        assertThrows(PeerWireException.class,
                () -> PeerWireCodec.readHandshake(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void rejectsTruncatedHandshake() {
        // Correct pstrlen (19) but too few bytes follow to actually contain
        // the protocol name - must fail on EOF, not a name mismatch.
        byte[] tooShort = {19, 'B', 'i', 't'};
        assertThrows(EOFException.class,
                () -> PeerWireCodec.readHandshake(new ByteArrayInputStream(tooShort)));
    }

    @Test
    void roundTripsKeepAlive() throws IOException {
        assertEquals(new KeepAlive(), roundTrip(new KeepAlive()));
    }

    @Test
    void roundTripsFixedLengthMessages() throws IOException {
        assertEquals(new Choke(), roundTrip(new Choke()));
        assertEquals(new Unchoke(), roundTrip(new Unchoke()));
        assertEquals(new Interested(), roundTrip(new Interested()));
        assertEquals(new NotInterested(), roundTrip(new NotInterested()));
    }

    @Test
    void roundTripsHave() throws IOException {
        assertEquals(new Have(42), roundTrip(new Have(42)));
    }

    @Test
    void roundTripsBitfieldAndChecksHasPiece() throws IOException {
        // 0b10100000 -> piece 0 and piece 2 set
        Bitfield bitfield = new Bitfield(new byte[]{(byte) 0b10100000});
        Bitfield decoded = (Bitfield) roundTrip(bitfield);

        assertEquals(bitfield, decoded);
        assertTrue(decoded.hasPiece(0));
        assertTrue(!decoded.hasPiece(1));
        assertTrue(decoded.hasPiece(2));
        assertTrue(!decoded.hasPiece(100)); // out of range -> false, not an exception
    }

    @Test
    void roundTripsRequest() throws IOException {
        assertEquals(new Request(1, 16384, 16384), roundTrip(new Request(1, 16384, 16384)));
    }

    @Test
    void roundTripsPiece() throws IOException {
        Piece piece = new Piece(3, 0, fill(16384, 7));
        assertEquals(piece, roundTrip(piece));
    }

    @Test
    void roundTripsCancel() throws IOException {
        assertEquals(new Cancel(1, 0, 16384), roundTrip(new Cancel(1, 0, 16384)));
    }

    @Test
    void roundTripsPort() throws IOException {
        assertEquals(new Port(6881), roundTrip(new Port(6881)));
    }

    @Test
    void roundTripsExtendedHandshakeId() throws IOException {
        // The codec treats the payload as opaque bytes - decoding it as bencode is
        // PeerConnection's concern (see PeerConnectionTest), not this layer's.
        Extended extended = new Extended(0, fill(10, 5));
        assertEquals(extended, roundTrip(extended));
    }

    @Test
    void roundTripsExtendedWithNonHandshakeId() throws IOException {
        Extended extended = new Extended(3, fill(20, 1));
        assertEquals(extended, roundTrip(extended));
    }

    @Test
    void rejectsEmptyExtendedPayload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(1); // length = 1 (just the message id byte, no extended-message-id follows)
        out.write(20); // extended message id

        assertThrows(PeerWireException.class,
                () -> PeerWireCodec.readMessage(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void rejectsUnknownMessageId() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(1); // length = 1
        out.write(99); // unknown message id

        assertThrows(PeerWireException.class,
                () -> PeerWireCodec.readMessage(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void rejectsWrongPayloadLengthForFixedMessage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(2); // length = 2 (id + 1 extra byte)
        out.write(0); // choke id
        out.write(5); // unexpected extra payload byte

        assertThrows(PeerWireException.class,
                () -> PeerWireCodec.readMessage(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void rejectsTruncatedMessage() {
        byte[] tooShort = {0, 0, 0, 5, 4}; // claims length 5 (have) but no payload follows
        assertThrows(EOFException.class,
                () -> PeerWireCodec.readMessage(new ByteArrayInputStream(tooShort)));
    }
}
