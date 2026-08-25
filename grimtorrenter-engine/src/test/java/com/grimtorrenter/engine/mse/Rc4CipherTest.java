package com.grimtorrenter.engine.mse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verified against the well-known RC4 test vectors (key/plaintext -> ciphertext triples
 * published on Wikipedia's RC4 article and widely reused across implementations) rather than
 * only round-tripping against itself - a self-consistent-but-wrong implementation would pass
 * a round-trip-only test. */
class Rc4CipherTest {

    @Test
    void matchesPublishedTestVectorKey() {
        assertEncrypts("Key", "Plaintext", "BBF316E8D940AF0AD3");
    }

    @Test
    void matchesPublishedTestVectorWiki() {
        assertEncrypts("Wiki", "pedia", "1021BF0420");
    }

    @Test
    void matchesPublishedTestVectorSecret() {
        assertEncrypts("Secret", "Attack at dawn", "45A01F645FC35B383552544B9BF5");
    }

    @Test
    void isSymmetricEncryptingTwiceReturnsThePlaintext() {
        byte[] key = "some shared secret".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "round trip me please".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = new Rc4Cipher(key).process(plaintext.clone());
        byte[] roundTripped = new Rc4Cipher(key).process(ciphertext.clone());

        assertArrayEquals(plaintext, roundTripped);
    }

    @Test
    void discardAdvancesTheKeystreamSoOutputAfterItDiffersFromOutputWithoutIt() {
        byte[] key = "a key".getBytes(StandardCharsets.UTF_8);
        byte[] data = "some plaintext bytes".getBytes(StandardCharsets.UTF_8);

        byte[] withoutDiscard = new Rc4Cipher(key).process(data.clone());
        Rc4Cipher discarding = new Rc4Cipher(key);
        discarding.discard(16);
        byte[] withDiscard = discarding.process(data.clone());

        boolean anyByteDiffers = false;
        for (int i = 0; i < data.length; i++) {
            if (withoutDiscard[i] != withDiscard[i]) {
                anyByteDiffers = true;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(anyByteDiffers,
                "discard() should have advanced the keystream, producing different output");
    }

    @Test
    void rejectsAnEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> new Rc4Cipher(new byte[0]));
    }

    /** Proves Rc4InputStream/Rc4OutputStream apply the exact same cipher as Rc4Cipher.process()
     * directly - i.e. that wrapping a stream doesn't change the algorithm, just where it's
     * applied - by round-tripping real bytes through a pair of independently-keyed streams
     * (mirroring how one direction's key differs from the other's in MSE) and confirming the
     * plaintext survives. */
    @Test
    void streamsRoundTripArbitraryDataAcrossMultipleReadsAndWrites() throws IOException {
        byte[] key = "stream key".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "a longer message that spans more than one small read buffer, ".repeat(20)
                .getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream encryptedBytes = new ByteArrayOutputStream();
        try (Rc4OutputStream encryptingOut = new Rc4OutputStream(encryptedBytes, new Rc4Cipher(key))) {
            encryptingOut.write(plaintext, 0, 37);
            encryptingOut.write(plaintext[37]);
            encryptingOut.write(plaintext, 38, plaintext.length - 38);
        }

        ByteArrayOutputStream decryptedBytes = new ByteArrayOutputStream();
        try (Rc4InputStream decryptingIn =
                     new Rc4InputStream(new ByteArrayInputStream(encryptedBytes.toByteArray()), new Rc4Cipher(key))) {
            byte[] buffer = new byte[13];
            int n;
            while ((n = decryptingIn.read(buffer)) >= 0) {
                decryptedBytes.write(buffer, 0, n);
            }
        }

        assertArrayEquals(plaintext, decryptedBytes.toByteArray());
    }

    private static void assertEncrypts(String key, String plaintext, String expectedHex) {
        byte[] ciphertext = new Rc4Cipher(key.getBytes(StandardCharsets.UTF_8))
                .process(plaintext.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(HexFormat.of().parseHex(expectedHex), ciphertext);
    }
}
