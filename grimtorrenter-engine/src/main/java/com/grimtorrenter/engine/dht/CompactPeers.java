package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.tracker.PeerAddress;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * BEP 5's compact peer info format for a get_peers response's "values" - unlike compact
 * node info, each peer is its own separate 6-byte bencode string (4-byte IPv4 address +
 * 2-byte port) inside a list, not one concatenated blob. IPv4-only, same as CompactNodes
 * and every other compact-address format already in this codebase. Package-private: only
 * DhtNode/PeerLookup need this.
 */
final class CompactPeers {

    private CompactPeers() {
    }

    static BList encode(List<PeerAddress> peers) {
        List<BValue> values = new ArrayList<>(peers.size());
        for (PeerAddress peer : peers) {
            values.add(encodeOne(peer));
        }
        return new BList(values);
    }

    private static BString encodeOne(PeerAddress peer) {
        byte[] address = requireIPv4(peer.address());
        byte[] out = new byte[6];
        System.arraycopy(address, 0, out, 0, 4);
        out[4] = (byte) (peer.port() >> 8);
        out[5] = (byte) peer.port();
        return BString.of(out);
    }

    private static byte[] requireIPv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            throw new KrpcException("Only IPv4 peers are supported, got " + address);
        }
        return address.getAddress();
    }

    static List<PeerAddress> decode(BList compact) {
        List<PeerAddress> peers = new ArrayList<>(compact.values().size());
        for (BValue value : compact.values()) {
            if (!(value instanceof BString entry)) {
                throw new KrpcException("Compact peer info entry is not a string");
            }
            peers.add(decodeOne(entry));
        }
        return peers;
    }

    private static PeerAddress decodeOne(BString entry) {
        byte[] data = entry.bytes();
        if (data.length != 6) {
            throw new KrpcException("Compact peer info entry length " + data.length + " is not 6");
        }
        byte[] address = Arrays.copyOfRange(data, 0, 4);
        int port = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        try {
            return new PeerAddress(InetAddress.getByAddress(address), port);
        } catch (UnknownHostException e) {
            throw new KrpcException("Invalid address in compact peer info");
        }
    }
}
