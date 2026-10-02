package com.grimtorrenter.engine.tracker;

import com.grimtorrenter.engine.ClientIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PeerIdTest {

    @Test
    void generateProducesTwentyByteIdWithClientPrefix() {
        PeerId id = PeerId.generate();

        assertEquals(20, id.bytes().length);
        byte[] prefix = new byte[8];
        System.arraycopy(id.bytes(), 0, prefix, 0, 8);
        assertEquals(ClientIdentity.peerIdPrefix(), new String(prefix, StandardCharsets.US_ASCII));
        assertEquals("-GT", ClientIdentity.peerIdPrefix().substring(0, 3));
    }

    @Test
    void generateProducesDifferentIdsEachCall() {
        assertNotEquals(PeerId.generate(), PeerId.generate());
    }
}
