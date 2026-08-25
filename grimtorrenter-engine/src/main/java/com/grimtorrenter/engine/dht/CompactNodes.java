package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * BEP 5's compact node info format: each node is a 26-byte chunk (20-byte id + 4-byte
 * IPv4 address + 2-byte port), concatenated with no delimiter into one bencode string.
 * IPv4-only, matching every other compact-address format already in this codebase
 * (tracker peer lists, see design_docs/0023) - BEP 32's IPv6 "nodes6" isn't supported.
 * Package-private: only DhtNode needs this.
 */
final class CompactNodes {

    private static final int ENTRY_LENGTH = NodeId.LENGTH_BYTES + 4 + 2;

    private CompactNodes() {
    }

    static BString encode(List<NodeInfo> nodes) {
        byte[] out = new byte[nodes.size() * ENTRY_LENGTH];
        int offset = 0;
        for (NodeInfo node : nodes) {
            offset = writeEntry(out, offset, node);
        }
        return BString.of(out);
    }

    private static int writeEntry(byte[] out, int offset, NodeInfo node) {
        System.arraycopy(node.id().bytes(), 0, out, offset, NodeId.LENGTH_BYTES);
        offset += NodeId.LENGTH_BYTES;
        System.arraycopy(requireIPv4(node.address()), 0, out, offset, 4);
        offset += 4;
        out[offset++] = (byte) (node.port() >> 8);
        out[offset++] = (byte) node.port();
        return offset;
    }

    static List<NodeInfo> decode(BString compact) {
        byte[] data = compact.bytes();
        if (data.length % ENTRY_LENGTH != 0) {
            throw new KrpcException(
                    "Compact node info length " + data.length + " is not a multiple of " + ENTRY_LENGTH);
        }
        List<NodeInfo> nodes = new ArrayList<>(data.length / ENTRY_LENGTH);
        for (int offset = 0; offset < data.length; offset += ENTRY_LENGTH) {
            nodes.add(readEntry(data, offset));
        }
        return nodes;
    }

    private static NodeInfo readEntry(byte[] data, int offset) {
        byte[] id = Arrays.copyOfRange(data, offset, offset + NodeId.LENGTH_BYTES);
        byte[] address = Arrays.copyOfRange(
                data, offset + NodeId.LENGTH_BYTES, offset + NodeId.LENGTH_BYTES + 4);
        int portOffset = offset + NodeId.LENGTH_BYTES + 4;
        int port = ((data[portOffset] & 0xFF) << 8) | (data[portOffset + 1] & 0xFF);
        try {
            return new NodeInfo(NodeId.of(id), InetAddress.getByAddress(address), port);
        } catch (UnknownHostException e) {
            throw new KrpcException("Invalid address in compact node info");
        }
    }

    private static byte[] requireIPv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            throw new KrpcException("Only IPv4 DHT nodes are supported, got " + address);
        }
        return address.getAddress();
    }
}
