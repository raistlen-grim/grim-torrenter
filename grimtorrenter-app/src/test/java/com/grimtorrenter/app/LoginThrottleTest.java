package com.grimtorrenter.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginThrottleTest {

    @Test
    void notLockedOutBeforeAnyFailure() {
        assertFalse(new LoginThrottle().isLockedOut());
    }

    /** A single failure - or a couple - shouldn't cost any delay at all; an honest typo isn't
     * a guessing attack. See LoginThrottle's own FREE_FAILURES. */
    @Test
    void aFewFailuresInARowDoNotLockOut() {
        LoginThrottle throttle = new LoginThrottle();

        throttle.recordFailure();
        throttle.recordFailure();
        assertFalse(throttle.isLockedOut());
    }

    @Test
    void locksOutOnceFreeFailuresAreExhaustedAndClearsOnSuccess() {
        LoginThrottle throttle = new LoginThrottle();

        for (int i = 0; i < 10; i++) {
            throttle.recordFailure();
        }
        assertTrue(throttle.isLockedOut());

        throttle.recordSuccess();
        assertFalse(throttle.isLockedOut());
    }

    /** Only one attempt is verified at a time - a burst can't each get a guess in before the
     * first failure is recorded. See design_docs/0094. */
    @Test
    void aSecondAttemptIsRefusedWhileOneIsInProgress() {
        LoginThrottle throttle = new LoginThrottle();

        assertTrue(throttle.tryBegin());
        assertFalse(throttle.tryBegin());

        throttle.finish(false);
        assertTrue(throttle.tryBegin());
        throttle.finish(true);
    }

    @Test
    void noAttemptIsAllowedWhileLockedOut() {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < 10; i++) {
            throttle.recordFailure();
        }

        assertFalse(throttle.tryBegin());
        // Refusing must not hold on to the slot.
        throttle.recordSuccess();
        assertTrue(throttle.tryBegin());
        throttle.finish(true);
    }
}
