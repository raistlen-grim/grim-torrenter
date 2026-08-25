package com.grimtorrenter.engine.metadata;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.bencode.BencodeEncoder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Encodes/decodes {@link UtMetadataMessage}s to/from the bytes carried inside a BEP 10
 * {@code Extended} message's payload - this is the layer above PeerWireCodec that
 * actually understands what those opaque bytes mean for this one extension. See
 * design_docs/0028.
 */
public final class UtMetadataCodec {

    private static final String MSG_TYPE = "msg_type";
    private static final String PIECE = "piece";
    private static final String TOTAL_SIZE = "total_size";

    private static final int MSG_TYPE_REQUEST = 0;
    private static final int MSG_TYPE_DATA = 1;
    private static final int MSG_TYPE_REJECT = 2;

    private UtMetadataCodec() {
    }

    public static byte[] encode(UtMetadataMessage message) {
        return switch (message) {
            case MetadataRequest r -> BencodeEncoder.encode(dict(MSG_TYPE_REQUEST, r.piece(), -1));
            case MetadataReject r -> BencodeEncoder.encode(dict(MSG_TYPE_REJECT, r.piece(), -1));
            case MetadataData d -> concat(
                    BencodeEncoder.encode(dict(MSG_TYPE_DATA, d.piece(), d.totalSize())), d.block());
        };
    }

    /** totalSize of -1 means "omit the total_size field" (request/reject don't carry one). */
    private static BDictionary dict(int msgType, int piece, int totalSize) {
        Map<BString, BValue> entries = new HashMap<>();
        entries.put(BString.of(MSG_TYPE), new BInteger(msgType));
        entries.put(BString.of(PIECE), new BInteger(piece));
        if (totalSize >= 0) {
            entries.put(BString.of(TOTAL_SIZE), new BInteger(totalSize));
        }
        return new BDictionary(entries);
    }

    public static UtMetadataMessage decode(byte[] payload) {
        BencodeDecoder.Prefix prefix = BencodeDecoder.decodePrefix(payload);
        if (!(prefix.value() instanceof BDictionary dict)) {
            throw new UtMetadataException("ut_metadata message is not a dictionary");
        }
        int msgType = requireInt(dict, MSG_TYPE);
        int piece = requireInt(dict, PIECE);
        return switch (msgType) {
            case MSG_TYPE_REQUEST -> new MetadataRequest(piece);
            case MSG_TYPE_REJECT -> new MetadataReject(piece);
            case MSG_TYPE_DATA -> new MetadataData(
                    piece, requireInt(dict, TOTAL_SIZE), Arrays.copyOfRange(payload, prefix.bytesConsumed(), payload.length));
            default -> throw new UtMetadataException("Unknown ut_metadata msg_type " + msgType);
        };
    }

    private static int requireInt(BDictionary dict, String key) {
        if (!(dict.get(key) instanceof BInteger i)) {
            throw new UtMetadataException("ut_metadata message missing integer field '" + key + "'");
        }
        return (int) i.value();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
