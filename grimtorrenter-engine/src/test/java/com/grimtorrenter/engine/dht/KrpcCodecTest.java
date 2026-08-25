package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KrpcCodecTest {

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

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static final BString TRANSACTION_ID = BString.of(new byte[]{0x01});
    private static final NodeId ID = NodeId.of(fill(20, 0));
    private static final NodeId TARGET = NodeId.of(fill(20, 20));
    private static final InfoHash INFO_HASH = InfoHash.of(fill(20, 40));

    // Each "RoundTripsAgainstHandBuiltBytes" test below builds the expected wire bytes by
    // hand (not via KrpcCodec.encode) and checks both directions against it independently -
    // matching design_docs/0028's "both directions, not just round-trips" convention, so a
    // mistake in one direction can't cancel out an identical mistake in the other.

    @Test
    void pingRoundTripsAgainstHandBuiltBytes() {
        Ping message = new Ping(TRANSACTION_ID, ID);
        byte[] expected = concat(
                ascii("d"),
                ascii("1:a"), ascii("d2:id20:"), ID.bytes(), ascii("e"),
                ascii("1:q4:ping"),
                ascii("1:t1:"), TRANSACTION_ID.bytes(),
                ascii("1:y1:q"),
                ascii("e"));

        assertArrayEquals(expected, KrpcCodec.encode(message));
        assertEquals(message, KrpcCodec.decode(expected));
    }

    @Test
    void findNodeRoundTripsAgainstHandBuiltBytes() {
        FindNode message = new FindNode(TRANSACTION_ID, ID, TARGET);
        byte[] expected = concat(
                ascii("d"),
                ascii("1:a"), ascii("d2:id20:"), ID.bytes(), ascii("6:target20:"), TARGET.bytes(), ascii("e"),
                ascii("1:q9:find_node"),
                ascii("1:t1:"), TRANSACTION_ID.bytes(),
                ascii("1:y1:q"),
                ascii("e"));

        assertArrayEquals(expected, KrpcCodec.encode(message));
        assertEquals(message, KrpcCodec.decode(expected));
    }

    @Test
    void getPeersRoundTripsAgainstHandBuiltBytes() {
        GetPeers message = new GetPeers(TRANSACTION_ID, ID, INFO_HASH);
        byte[] expected = concat(
                ascii("d"),
                ascii("1:a"), ascii("d2:id20:"), ID.bytes(), ascii("9:info_hash20:"), INFO_HASH.bytes(), ascii("e"),
                ascii("1:q9:get_peers"),
                ascii("1:t1:"), TRANSACTION_ID.bytes(),
                ascii("1:y1:q"),
                ascii("e"));

        assertArrayEquals(expected, KrpcCodec.encode(message));
        assertEquals(message, KrpcCodec.decode(expected));
    }

    @Test
    void announcePeerRoundTripsAgainstHandBuiltBytes() {
        BString token = BString.of(new byte[]{0x02, 0x03});
        AnnouncePeer message = new AnnouncePeer(TRANSACTION_ID, ID, INFO_HASH, true, 6881, token);
        byte[] expected = concat(
                ascii("d"),
                ascii("1:a"), ascii("d2:id20:"), ID.bytes(),
                ascii("12:implied_porti1e"),
                ascii("9:info_hash20:"), INFO_HASH.bytes(),
                ascii("4:porti6881e"),
                ascii("5:token2:"), token.bytes(),
                ascii("e"),
                ascii("1:q13:announce_peer"),
                ascii("1:t1:"), TRANSACTION_ID.bytes(),
                ascii("1:y1:q"),
                ascii("e"));

        assertArrayEquals(expected, KrpcCodec.encode(message));
        assertEquals(message, KrpcCodec.decode(expected));
    }

    @Test
    void announcePeerOmittedImpliedPortDecodesToFalse() {
        byte[] bytes = concat(
                ascii("d"),
                ascii("1:a"), ascii("d2:id20:"), ID.bytes(),
                ascii("9:info_hash20:"), INFO_HASH.bytes(),
                ascii("4:porti6881e"),
                ascii("5:token1:"), new byte[]{0x02},
                ascii("e"),
                ascii("1:q13:announce_peer"),
                ascii("1:t1:"), TRANSACTION_ID.bytes(),
                ascii("1:y1:q"),
                ascii("e"));

        AnnouncePeer message = (AnnouncePeer) KrpcCodec.decode(bytes);
        assertFalse(message.impliedPort());
    }

    @Test
    void responseRoundTripsAgainstHandBuiltBytes() {
        BDictionary returnValues = new BDictionary(Map.of(BString.of("id"), BString.of(ID.bytes())));
        KrpcResponse message = new KrpcResponse(TRANSACTION_ID, returnValues);
        byte[] expected = concat(
                ascii("d"),
                ascii("1:r"), ascii("d2:id20:"), ID.bytes(), ascii("e"),
                ascii("1:t1:"), TRANSACTION_ID.bytes(),
                ascii("1:y1:r"),
                ascii("e"));

        assertArrayEquals(expected, KrpcCodec.encode(message));
        assertEquals(message, KrpcCodec.decode(expected));
    }

    @Test
    void errorRoundTripsAgainstHandBuiltBytes() {
        KrpcError message = new KrpcError(TRANSACTION_ID, 201, "boom");
        byte[] expected = concat(
                ascii("d"),
                ascii("1:e"), ascii("li201e4:boome"),
                ascii("1:t1:"), TRANSACTION_ID.bytes(),
                ascii("1:y1:e"),
                ascii("e"));

        assertArrayEquals(expected, KrpcCodec.encode(message));
        assertEquals(message, KrpcCodec.decode(expected));
    }

    // Malformed-input cases are built via BDictionary/BencodeEncoder (a different, already
    // separately-tested class) rather than hand-typed bencode literals - safer than getting
    // length prefixes right by eye, and just as independent of KrpcCodec's own encoding logic.

    @Test
    void rejectsNonDictionaryPayload() {
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(new BInteger(5))));
    }

    @Test
    void rejectsMissingTransactionId() {
        BDictionary dict = new BDictionary(Map.of(BString.of("y"), BString.of("q")));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsMissingMessageType() {
        BDictionary dict = new BDictionary(Map.of(BString.of("t"), TRANSACTION_ID));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsUnknownMessageType() {
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("z")));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsQueryMissingMethod() {
        BDictionary args = new BDictionary(Map.of(BString.of("id"), BString.of(ID.bytes())));
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("q"),
                BString.of("a"), args));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsQueryMissingArgs() {
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("q"),
                BString.of("q"), BString.of("ping")));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsUnknownQueryMethod() {
        BDictionary args = new BDictionary(Map.of(BString.of("id"), BString.of(ID.bytes())));
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("q"),
                BString.of("q"), BString.of("unknown"),
                BString.of("a"), args));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsPingMissingId() {
        BDictionary args = new BDictionary(Map.of());
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("q"),
                BString.of("q"), BString.of("ping"),
                BString.of("a"), args));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsWrongLengthNodeId() {
        BDictionary args = new BDictionary(Map.of(BString.of("id"), BString.of(fill(19, 0))));
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("q"),
                BString.of("q"), BString.of("ping"),
                BString.of("a"), args));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsGetPeersMissingInfoHash() {
        BDictionary args = new BDictionary(Map.of(BString.of("id"), BString.of(ID.bytes())));
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("q"),
                BString.of("q"), BString.of("get_peers"),
                BString.of("a"), args));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsResponseMissingReturnValues() {
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("r")));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsMalformedErrorNotTwoElementList() {
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("e"),
                BString.of("e"), new BList(List.of(new BInteger(201)))));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }

    @Test
    void rejectsMalformedErrorWrongElementTypes() {
        BDictionary dict = new BDictionary(Map.of(
                BString.of("t"), TRANSACTION_ID,
                BString.of("y"), BString.of("e"),
                BString.of("e"), new BList(List.of(BString.of("boom"), new BInteger(201)))));
        assertThrows(KrpcException.class, () -> KrpcCodec.decode(BencodeEncoder.encode(dict)));
    }
}
