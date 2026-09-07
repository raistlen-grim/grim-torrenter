package com.grimtorrenter.app;

import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain JUnit, not @QuarkusTest - same reasoning as JsonSettingsStoreTest/JsonAuthStoreTest:
 * this store's own logic doesn't need a running container, only a directly-constructed
 * instance with its @Inject field set by hand (package-private, same as those). */
class SessionTokenStoreTest {

    private static SessionTokenStore createStore() {
        SessionTokenStore store = new SessionTokenStore();
        store.settingsStore = new InMemorySettingsStore();
        return store;
    }

    @Test
    void issuedTokenValidatesUntilRevoked() {
        SessionTokenStore store = createStore();

        String token = store.issue();

        assertTrue(store.validate(token));
        store.revoke(token);
        assertFalse(store.validate(token));
    }

    @Test
    void rejectsAnUnknownOrNullToken() {
        SessionTokenStore store = createStore();

        assertFalse(store.validate("not-a-real-token"));
        assertFalse(store.validate(null));
    }

    @Test
    void twoIssuedTokensAreDifferent() {
        SessionTokenStore store = createStore();

        assertNotEquals(store.issue(), store.issue());
    }

    @Test
    void notLockedOutBeforeAnyFailure() {
        SessionTokenStore store = createStore();

        assertFalse(store.isLockedOut());
    }

    /** A single failure - or a couple - shouldn't cost any delay at all; an honest typo isn't
     * a guessing attack. See SessionTokenStore's own FREE_FAILURES. */
    @Test
    void aFewFailuresInARowDoNotLockOut() {
        SessionTokenStore store = createStore();

        store.recordFailure();
        store.recordFailure();
        assertFalse(store.isLockedOut());
    }

    @Test
    void locksOutOnceFreeFailuresAreExhaustedAndClearsOnSuccess() {
        SessionTokenStore store = createStore();

        for (int i = 0; i < 10; i++) {
            store.recordFailure();
        }
        assertTrue(store.isLockedOut());

        store.recordSuccess();
        assertFalse(store.isLockedOut());
    }
}
