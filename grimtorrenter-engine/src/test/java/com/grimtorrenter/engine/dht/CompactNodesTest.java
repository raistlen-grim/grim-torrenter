package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BString;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactNodesTest {

    private static NodeId idWithLastByte(int value) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) value;
        return NodeId.of(bytes);
    }

    @Test
    void encodesAndDecodesASingleNodeAgainstHandBuiltBytes() throws UnknownHostException {
        NodeId id = idWithLastByte(1);
        NodeInfo node = new NodeInfo(id, InetAddress.getByName("1.2.3.4"), 6881);

        byte[] expected = new byte[26];
        System.arraycopy(id.bytes(), 0, expected, 0, 20);
        expected[20] = 1;
        expected[21] = 2;
        expected[22] = 3;
        expected[23] = 4;
        expected[24] = (byte) (6881 >> 8);
        expected[25] = (byte) 6881;

        BString encoded = CompactNodes.encode(List.of(node));

        assertArrayEquals(expected, encoded.bytes());
        assertEquals(List.of(node), CompactNodes.decode(BString.of(expected)));
    }

    @Test
    void encodesAndDecodesMultipleNodes() throws UnknownHostException {
        NodeInfo a = new NodeInfo(idWithLastByte(1), InetAddress.getByName("10.0.0.1"), 1);
        NodeInfo b = new NodeInfo(idWithLastByte(2), InetAddress.getByName("10.0.0.2"), 2);

        BString encoded = CompactNodes.encode(List.of(a, b));

        assertEquals(List.of(a, b), CompactNodes.decode(encoded));
    }

    @Test
    void encodingEmptyListProducesEmptyString() {
        assertEquals(0, CompactNodes.encode(List.of()).length());
    }

    @Test
    void rejectsNonIPv4AddressOnEncode() throws UnknownHostException {
        NodeInfo ipv6Node = new NodeInfo(idWithLastByte(1), InetAddress.getByName("::1"), 6881);
        assertThrows(KrpcException.class, () -> CompactNodes.encode(List.of(ipv6Node)));
    }

    @Test
    void rejectsLengthNotAMultipleOfTwentySix() {
        assertThrows(KrpcException.class, () -> CompactNodes.decode(BString.of(new byte[25])));
    }
}
