package com.grimtorrenter.engine.dht;

import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.tracker.PeerAddress;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactPeersTest {

    @Test
    void encodesAndDecodesASinglePeerAgainstHandBuiltBytes() throws UnknownHostException {
        PeerAddress peer = new PeerAddress(InetAddress.getByName("1.2.3.4"), 6881);
        byte[] expected = {1, 2, 3, 4, (byte) (6881 >> 8), (byte) 6881};

        BList encoded = CompactPeers.encode(List.of(peer));

        assertEquals(1, encoded.values().size());
        assertArrayEquals(expected, ((BString) encoded.values().get(0)).bytes());
        assertEquals(List.of(peer), CompactPeers.decode(new BList(List.of(BString.of(expected)))));
    }

    @Test
    void encodesAndDecodesMultiplePeers() throws UnknownHostException {
        PeerAddress a = new PeerAddress(InetAddress.getByName("10.0.0.1"), 1);
        PeerAddress b = new PeerAddress(InetAddress.getByName("10.0.0.2"), 2);

        BList encoded = CompactPeers.encode(List.of(a, b));

        assertEquals(List.of(a, b), CompactPeers.decode(encoded));
    }

    @Test
    void encodingEmptyListProducesEmptyList() {
        assertEquals(0, CompactPeers.encode(List.of()).values().size());
    }

    @Test
    void rejectsNonIPv4AddressOnEncode() throws UnknownHostException {
        PeerAddress ipv6Peer = new PeerAddress(InetAddress.getByName("::1"), 6881);
        assertThrows(KrpcException.class, () -> CompactPeers.encode(List.of(ipv6Peer)));
    }

    @Test
    void rejectsEntryLengthNotSix() {
        BList malformed = new BList(List.of(BString.of(new byte[5])));
        assertThrows(KrpcException.class, () -> CompactPeers.decode(malformed));
    }

    @Test
    void rejectsNonStringEntry() {
        BList malformed = new BList(List.of(new BInteger(1)));
        assertThrows(KrpcException.class, () -> CompactPeers.decode(malformed));
    }
}
