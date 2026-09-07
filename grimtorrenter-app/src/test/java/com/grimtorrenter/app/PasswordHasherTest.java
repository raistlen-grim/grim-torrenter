package com.grimtorrenter.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void matchesTheCorrectPasswordAgainstItsOwnHash() {
        String hash = PasswordHasher.hash("correct horse battery staple");

        assertTrue(PasswordHasher.matches("correct horse battery staple", hash));
    }

    @Test
    void rejectsAnIncorrectPassword() {
        String hash = PasswordHasher.hash("correct horse battery staple");

        assertFalse(PasswordHasher.matches("wrong password", hash));
    }

    /** Salted - the same password hashed twice must not produce the same stored string, or
     * two GrimTorrenter instances with the same password would be trivially distinguishable/
     * attackable via a shared rainbow table. */
    @Test
    void hashingTheSamePasswordTwiceProducesDifferentSalts() {
        String first = PasswordHasher.hash("correct horse battery staple");
        String second = PasswordHasher.hash("correct horse battery staple");

        assertNotEquals(first, second);
        assertTrue(PasswordHasher.matches("correct horse battery staple", first));
        assertTrue(PasswordHasher.matches("correct horse battery staple", second));
    }
}
