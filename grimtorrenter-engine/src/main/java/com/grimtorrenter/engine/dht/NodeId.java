package com.grimtorrenter.engine.dht;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The 160-bit id identifying a node in the DHT's key space. Same hex-string-backed shape
 * as InfoHash/PeerId, for the same reason - correct equals/hashCode for free, and a
 * human-readable form for logging. Deliberately not sharing an abstraction with those:
 * a node id is a distinct identity (a DHT participant, not a torrent or a peer-wire
 * client) that should stay type-distinct rather than interchangeable.
 */
public record NodeId(String hex) {

    public static final int LENGTH_BYTES = 20;

    public NodeId {
        if (hex.length() != LENGTH_BYTES * 2) {
            throw new IllegalArgumentException("Node id hex must be " + (LENGTH_BYTES * 2) + " chars");
        }
    }

    public static NodeId of(byte[] bytes) {
        if (bytes.length != LENGTH_BYTES) {
            throw new IllegalArgumentException("Node id must be " + LENGTH_BYTES + " bytes, got " + bytes.length);
        }
        return new NodeId(HexFormat.of().formatHex(bytes));
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(hex);
    }

    /** A fresh random node id - same shape as PeerId.generate(), just without a fixed
     * client-identifying prefix (a DHT node id has no such convention to follow). */
    public static NodeId random() {
        byte[] bytes = new byte[LENGTH_BYTES];
        new SecureRandom().nextBytes(bytes);
        return of(bytes);
    }

    /** BEP 5's XOR distance metric, as a plain non-negative magnitude - BigInteger gives
     * correct comparison for free, and bitLength() conveniently doubles as "the routing
     * table bucket index this distance falls into" (see RoutingTable). */
    public BigInteger distanceTo(NodeId other) {
        byte[] a = bytes();
        byte[] b = other.bytes();
        byte[] xor = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            xor[i] = (byte) (a[i] ^ b[i]);
        }
        return new BigInteger(1, xor);
    }

    @Override
    public String toString() {
        return hex;
    }
}
