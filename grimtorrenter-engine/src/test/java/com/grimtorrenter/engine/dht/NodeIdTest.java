package com.grimtorrenter.engine.dht;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeIdTest {

    private static byte[] zeros() {
        return new byte[20];
    }

    @Test
    void ofAndBytesRoundTrip() {
        byte[] bytes = new byte[20];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        assertArrayEquals(bytes, NodeId.of(bytes).bytes());
    }

    @Test
    void rejectsWrongLengthBytes() {
        assertThrows(IllegalArgumentException.class, () -> NodeId.of(new byte[19]));
        assertThrows(IllegalArgumentException.class, () -> NodeId.of(new byte[21]));
    }

    @Test
    void rejectsWrongLengthHex() {
        assertThrows(IllegalArgumentException.class, () -> new NodeId("ab"));
    }

    @Test
    void distanceToSelfIsZero() {
        NodeId id = NodeId.of(zeros());
        assertEquals(BigInteger.ZERO, id.distanceTo(id));
    }

    @Test
    void distanceIsSymmetric() {
        byte[] aBytes = zeros();
        aBytes[19] = 0x0F;
        byte[] bBytes = zeros();
        bBytes[0] = (byte) 0x80;
        NodeId a = NodeId.of(aBytes);
        NodeId b = NodeId.of(bBytes);

        assertEquals(a.distanceTo(b), b.distanceTo(a));
    }

    @Test
    void distanceIsXorOfLeastSignificantBytes() {
        NodeId zero = NodeId.of(zeros());
        byte[] oneBytes = zeros();
        oneBytes[19] = 0x01;
        NodeId one = NodeId.of(oneBytes);

        assertEquals(BigInteger.ONE, zero.distanceTo(one));
    }

    @Test
    void distanceIsXorOfMostSignificantBit() {
        NodeId zero = NodeId.of(zeros());
        byte[] highBytes = zeros();
        highBytes[0] = (byte) 0x80;
        NodeId high = NodeId.of(highBytes);

        assertEquals(BigInteger.TWO.pow(159), zero.distanceTo(high));
    }
}
