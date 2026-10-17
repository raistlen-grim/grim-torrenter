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
    void isValidDoesNotNeedRefreshingAndRejectsRevokedTokens() {
        SessionTokenStore store = createStore();

        String token = store.issue();

        assertTrue(store.isValid(token));
        assertFalse(store.isValid(null));
        store.revoke(token);
        assertFalse(store.isValid(token));
    }

    /** A password change ends every session but the caller's. See design_docs/0094. */
    @Test
    void revokeAllExceptKeepsOnlyTheGivenToken() {
        SessionTokenStore store = createStore();
        String caller = store.issue();
        String other = store.issue();

        store.revokeAllExcept(caller);

        assertTrue(store.validate(caller));
        assertFalse(store.validate(other));

        store.revokeAllExcept(null);
        assertFalse(store.validate(caller));
    }
}
