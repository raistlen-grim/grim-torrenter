package com.grimtorrenter.engine.mse;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DiffieHellmanTest {

    private final SecureRandom random = new SecureRandom();

    /** Guards against exactly the mistake DiffieHellman.P's Javadoc describes: RFC 2409 Group
     * 1 and RFC 3526 Group 14 share a long common hex prefix, so a wrong-prime typo is not
     * visually obvious - asserting the bit length directly would have caught it immediately
     * instead of surfacing as a confusing shared-secret mismatch two tests down. */
    @Test
    void primeIsExactlySevenSixtyEightBits() {
        assertEquals(768, DiffieHellman.P.bitLength());
    }

    @Test
    void bothSidesAgreeOnTheSameSharedSecret() {
        DiffieHellman a = new DiffieHellman(random);
        DiffieHellman b = new DiffieHellman(random);

        BigInteger secretFromA = a.sharedSecret(DiffieHellman.publicKeyFromBytes(b.publicKeyBytes()));
        BigInteger secretFromB = b.sharedSecret(DiffieHellman.publicKeyFromBytes(a.publicKeyBytes()));

        assertEquals(secretFromA, secretFromB);
    }

    @Test
    void twoDifferentKeyPairsProduceDifferentSharedSecrets() {
        DiffieHellman a = new DiffieHellman(random);
        DiffieHellman b = new DiffieHellman(random);
        DiffieHellman c = new DiffieHellman(random);

        BigInteger secretAB = a.sharedSecret(DiffieHellman.publicKeyFromBytes(b.publicKeyBytes()));
        BigInteger secretAC = a.sharedSecret(DiffieHellman.publicKeyFromBytes(c.publicKeyBytes()));

        assertNotEquals(secretAB, secretAC);
    }

    @Test
    void publicKeyBytesAreAlwaysExactlyTheFixedLength() {
        for (int i = 0; i < 20; i++) {
            DiffieHellman dh = new DiffieHellman(random);
            assertEquals(DiffieHellman.PUBLIC_KEY_LENGTH_BYTES, dh.publicKeyBytes().length);
        }
    }

    @Test
    void publicKeyRoundTripsThroughTheWireFormat() {
        DiffieHellman dh = new DiffieHellman(random);

        BigInteger roundTripped = DiffieHellman.publicKeyFromBytes(dh.publicKeyBytes());

        BigInteger reconstructedSharedSecret = new DiffieHellman(random).sharedSecret(roundTripped);
        assertNotEquals(BigInteger.ZERO, reconstructedSharedSecret);
    }
}
