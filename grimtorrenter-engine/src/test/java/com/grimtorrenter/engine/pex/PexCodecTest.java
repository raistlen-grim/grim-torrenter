package com.grimtorrenter.engine.pex;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.tracker.PeerAddress;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PexCodecTest {

    private static PeerAddress peer(int a, int b, int c, int d, int port) {
        try {
            return new PeerAddress(InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d}), port);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void encodesAndDecodesAddedAndDroppedRoundTrip() {
        List<PeerAddress> added = List.of(peer(10, 0, 0, 1, 6881), peer(192, 168, 1, 5, 51413));
        List<PeerAddress> dropped = List.of(peer(203, 0, 113, 9, 6969));
        PexMessage original = new PexMessage(added, dropped);

        PexMessage decoded = PexCodec.decode(PexCodec.encode(original));

        assertEquals(added, decoded.added());
        assertEquals(dropped, decoded.dropped());
    }

    @Test
    void encodesEmptyListsAsEmptyRatherThanOmittingTheKeys() {
        PexMessage decoded = PexCodec.decode(PexCodec.encode(new PexMessage(List.of(), List.of())));

        assertEquals(List.of(), decoded.added());
        assertEquals(List.of(), decoded.dropped());
    }

    @Test
    void decodingAMissingKeyDefaultsToAnEmptyList() {
        // A peer that has nothing to report in one direction is expected to just omit
        // that key entirely - matches PexCodec.decode's own documented tolerance.
        byte[] payload = BencodeEncoder.encode(
                new BDictionary(Map.of(BString.of("added"), BString.of(new byte[0]))));

        PexMessage decoded = PexCodec.decode(payload);

        assertEquals(List.of(), decoded.added());
        assertEquals(List.of(), decoded.dropped());
    }

    @Test
    void decodingAnAddedLengthNotAMultipleOfSixThrows() {
        byte[] payload = BencodeEncoder.encode(
                new BDictionary(Map.of(BString.of("added"), BString.of(new byte[]{1, 2, 3}))));

        assertThrows(PexException.class, () -> PexCodec.decode(payload));
    }

    @Test
    void decodingSomethingThatIsNotADictionaryThrows() {
        byte[] notADictionary = BencodeEncoder.encode(BString.of("not a dict"));

        assertThrows(PexException.class, () -> PexCodec.decode(notADictionary));
    }
}
