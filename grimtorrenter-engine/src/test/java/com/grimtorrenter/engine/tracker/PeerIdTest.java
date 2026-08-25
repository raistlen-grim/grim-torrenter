package com.grimtorrenter.engine.tracker;

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
        assertEquals("-GT0100-", new String(prefix, StandardCharsets.US_ASCII));
    }

    @Test
    void generateProducesDifferentIdsEachCall() {
        assertNotEquals(PeerId.generate(), PeerId.generate());
    }
}
