package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.metainfo.InfoHash;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes/decodes BEP 5 KRPC messages - the bencoded query/response/error envelope every
 * DHT UDP packet uses. Queries decode to a fully typed {@link KrpcQuery} (their "q" field
 * unambiguously says which one); responses decode to a generic {@link KrpcResponse} - see
 * its Javadoc for why.
 */
public final class KrpcCodec {

    private static final String TRANSACTION_ID = "t";
    private static final String MESSAGE_TYPE = "y";
    private static final String QUERY_METHOD = "q";
    private static final String QUERY_ARGS = "a";
    private static final String RESPONSE_VALUES = "r";
    private static final String ERROR = "e";

    private static final String TYPE_QUERY = "q";
    private static final String TYPE_RESPONSE = "r";
    private static final String TYPE_ERROR = "e";

    private static final String METHOD_PING = "ping";
    private static final String METHOD_FIND_NODE = "find_node";
    private static final String METHOD_GET_PEERS = "get_peers";
    private static final String METHOD_ANNOUNCE_PEER = "announce_peer";

    private static final String ARG_ID = "id";
    private static final String ARG_TARGET = "target";
    private static final String ARG_INFO_HASH = "info_hash";
    private static final String ARG_IMPLIED_PORT = "implied_port";
    private static final String ARG_PORT = "port";
    private static final String ARG_TOKEN = "token";

    private KrpcCodec() {
    }

    public static byte[] encode(KrpcMessage message) {
        return BencodeEncoder.encode(toDictionary(message));
    }

    private static BDictionary toDictionary(KrpcMessage message) {
        return switch (message) {
            case Ping p -> query(p.transactionId(), METHOD_PING, Map.of(ARG_ID, idValue(p.id())));
            case FindNode f -> query(f.transactionId(), METHOD_FIND_NODE, Map.of(
                    ARG_ID, idValue(f.id()), ARG_TARGET, idValue(f.target())));
            case GetPeers g -> query(g.transactionId(), METHOD_GET_PEERS, Map.of(
                    ARG_ID, idValue(g.id()), ARG_INFO_HASH, infoHashValue(g.infoHash())));
            case AnnouncePeer a -> query(a.transactionId(), METHOD_ANNOUNCE_PEER, announceArgs(a));
            case KrpcResponse r ->
                    topLevel(r.transactionId(), TYPE_RESPONSE, Map.of(RESPONSE_VALUES, r.returnValues()));
            case KrpcError e -> topLevel(e.transactionId(), TYPE_ERROR, Map.of(ERROR, errorValue(e)));
        };
    }

    private static Map<String, BValue> announceArgs(AnnouncePeer a) {
        Map<String, BValue> args = new HashMap<>();
        args.put(ARG_ID, idValue(a.id()));
        args.put(ARG_INFO_HASH, infoHashValue(a.infoHash()));
        args.put(ARG_IMPLIED_PORT, new BInteger(a.impliedPort() ? 1 : 0));
        args.put(ARG_PORT, new BInteger(a.port()));
        args.put(ARG_TOKEN, a.token());
        return args;
    }

    private static BValue idValue(NodeId id) {
        return BString.of(id.bytes());
    }

    private static BValue infoHashValue(InfoHash infoHash) {
        return BString.of(infoHash.bytes());
    }

    private static BList errorValue(KrpcError e) {
        return new BList(List.of(new BInteger(e.code()), BString.of(e.message())));
    }

    private static BDictionary query(BString transactionId, String method, Map<String, BValue> args) {
        Map<BString, BValue> argsDict = new HashMap<>();
        args.forEach((key, value) -> argsDict.put(BString.of(key), value));
        return topLevel(transactionId, TYPE_QUERY,
                Map.of(QUERY_METHOD, BString.of(method), QUERY_ARGS, new BDictionary(argsDict)));
    }

    private static BDictionary topLevel(BString transactionId, String type, Map<String, BValue> extra) {
        Map<BString, BValue> entries = new HashMap<>();
        entries.put(BString.of(TRANSACTION_ID), transactionId);
        entries.put(BString.of(MESSAGE_TYPE), BString.of(type));
        extra.forEach((key, value) -> entries.put(BString.of(key), value));
        return new BDictionary(entries);
    }

    public static KrpcMessage decode(byte[] data) {
        if (!(BencodeDecoder.decode(data) instanceof BDictionary dict)) {
            throw new KrpcException("KRPC message is not a dictionary");
        }
        BString transactionId = requireString(dict, TRANSACTION_ID);
        String type = requireUtf8(dict, MESSAGE_TYPE);
        return switch (type) {
            case TYPE_QUERY -> decodeQuery(transactionId, dict);
            case TYPE_RESPONSE -> new KrpcResponse(transactionId, requireDictionary(dict, RESPONSE_VALUES));
            case TYPE_ERROR -> decodeError(transactionId, dict);
            default -> throw new KrpcException("Unknown KRPC message type '" + type + "'");
        };
    }

    private static KrpcQuery decodeQuery(BString transactionId, BDictionary dict) {
        String method = requireUtf8(dict, QUERY_METHOD);
        BDictionary args = requireDictionary(dict, QUERY_ARGS);
        return switch (method) {
            case METHOD_PING -> new Ping(transactionId, requireNodeId(args, ARG_ID));
            case METHOD_FIND_NODE -> new FindNode(
                    transactionId, requireNodeId(args, ARG_ID), requireNodeId(args, ARG_TARGET));
            case METHOD_GET_PEERS -> new GetPeers(
                    transactionId, requireNodeId(args, ARG_ID), requireInfoHash(args, ARG_INFO_HASH));
            case METHOD_ANNOUNCE_PEER -> new AnnouncePeer(transactionId, requireNodeId(args, ARG_ID),
                    requireInfoHash(args, ARG_INFO_HASH), impliedPort(args), requireInt(args, ARG_PORT),
                    requireString(args, ARG_TOKEN));
            default -> throw new KrpcException("Unknown KRPC query method '" + method + "'");
        };
    }

    /** Optional per BEP 5 - absent (or any value other than 1) means false. */
    private static boolean impliedPort(BDictionary args) {
        return args.get(ARG_IMPLIED_PORT) instanceof BInteger i && i.value() == 1;
    }

    private static KrpcError decodeError(BString transactionId, BDictionary dict) {
        if (!(dict.get(ERROR) instanceof BList list) || list.values().size() != 2) {
            throw new KrpcException("Malformed KRPC error - 'e' must be a 2-element list");
        }
        if (!(list.values().get(0) instanceof BInteger code) || !(list.values().get(1) instanceof BString message)) {
            throw new KrpcException("Malformed KRPC error - 'e' must be [integer code, string message]");
        }
        return new KrpcError(transactionId, code.value(), message.utf8());
    }

    private static NodeId requireNodeId(BDictionary dict, String key) {
        try {
            return NodeId.of(requireString(dict, key).bytes());
        } catch (IllegalArgumentException e) {
            throw new KrpcException("Malformed '" + key + "' field: " + e.getMessage());
        }
    }

    private static InfoHash requireInfoHash(BDictionary dict, String key) {
        try {
            return InfoHash.of(requireString(dict, key).bytes());
        } catch (IllegalArgumentException e) {
            throw new KrpcException("Malformed '" + key + "' field: " + e.getMessage());
        }
    }

    private static int requireInt(BDictionary dict, String key) {
        if (!(dict.get(key) instanceof BInteger i)) {
            throw new KrpcException("KRPC message missing integer field '" + key + "'");
        }
        return Math.toIntExact(i.value());
    }

    private static BString requireString(BDictionary dict, String key) {
        if (!(dict.get(key) instanceof BString s)) {
            throw new KrpcException("KRPC message missing string field '" + key + "'");
        }
        return s;
    }

    private static String requireUtf8(BDictionary dict, String key) {
        return requireString(dict, key).utf8();
    }

    private static BDictionary requireDictionary(BDictionary dict, String key) {
        if (!(dict.get(key) instanceof BDictionary d)) {
            throw new KrpcException("KRPC message missing dictionary field '" + key + "'");
        }
        return d;
    }
}
