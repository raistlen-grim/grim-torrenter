package com.grimtorrenter.app;

import com.grimtorrenter.engine.settings.SettingsStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer-token sessions for GrimTorrenter's own management API - opaque random tokens
 * (SecureRandom), not JWTs: no signing-key management, and trivially revocable by just
 * removing the map entry. Held only in memory - a restart means logging in again, an accepted
 * tradeoff for a personal app, same category as the existing non-persisted upload/download
 * byte counters ([[0054-seeding-limits]]). See design_docs/0061.
 *
 * <p>TTL is sliding (refreshed in validate() on every successful use), not fixed from issue -
 * a personal server shouldn't force a re-login just because a session has been open a while,
 * only because it's genuinely gone unused for Settings.authTokenTtlDays. Read fresh from
 * SettingsStore on every issue()/validate() call (live, like the rate limits/encryptionMode -
 * see design_docs/0061's own addendum), not cached, so a change takes effect immediately -
 * including for tokens already issued, since the expiry stored per-token is an absolute
 * Instant computed from whatever the TTL was at that moment, not a reference back to the
 * setting.
 *
 * <p>Failed-login backoff lives in LoginThrottle.
 */
@ApplicationScoped
public class SessionTokenStore {

    private static final int TOKEN_BYTES = 32;

    @Inject
    SettingsStore settingsStore;

    private final Map<String, Instant> expiryByToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        expiryByToken.put(token, Instant.now().plus(currentTtl()));
        return token;
    }

    /** Atomic per-key via ConcurrentHashMap.computeIfPresent - either refreshes the sliding
     * expiry and returns true, or (an expired or unknown token) removes any stale entry and
     * returns false. No separate read-then-write race, and the remapping function has no side
     * effect beyond the value it returns, so re-invocation under contention is harmless. */
    boolean validate(String token) {
        if (token == null) {
            return false;
        }
        Duration ttl = currentTtl();
        Instant refreshed = expiryByToken.computeIfPresent(token,
                (t, expiry) -> expiry.isAfter(Instant.now()) ? Instant.now().plus(ttl) : null);
        return refreshed != null;
    }

    /** Like validate(), but without refreshing the sliding expiry - for TorrentWebSocket's
     * periodic re-check of open sockets, which mustn't keep a forgotten tab's token alive just by
     * being open. See design_docs/0094. */
    boolean isValid(String token) {
        if (token == null) {
            return false;
        }
        Instant expiry = expiryByToken.get(token);
        return expiry != null && expiry.isAfter(Instant.now());
    }

    private Duration currentTtl() {
        return Duration.ofDays(settingsStore.current().authTokenTtlDays());
    }

    void revoke(String token) {
        if (token != null) {
            expiryByToken.remove(token);
        }
    }

    /** Every token except {@code keep} (which may be null, revoking all) - a password change
     * ends every other session, so a leaked token doesn't survive the obvious remedy. The caller's
     * own session is kept so the browser that made the change isn't logged out mid-request. See
     * design_docs/0094. */
    void revokeAllExcept(String keep) {
        expiryByToken.keySet().removeIf(token -> !token.equals(keep));
    }

    /** Sweeps tokens whose sliding expiry has already lapsed - without this, a long-running
     * server accumulates one dead map entry per past login forever (validate() alone only ever
     * touches tokens actually still being used, never the abandoned ones). Not a correctness
     * fix - a lapsed entry is already inert, validate() treats it as absent - purely bounding
     * memory growth. See design_docs/0061's stability section. */
    @Scheduled(every = "1h")
    void sweepExpiredTokens() {
        Instant now = Instant.now();
        expiryByToken.values().removeIf(expiry -> expiry.isBefore(now));
    }
}
