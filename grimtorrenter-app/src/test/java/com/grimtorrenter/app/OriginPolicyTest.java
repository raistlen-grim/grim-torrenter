package com.grimtorrenter.app;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which WebSocket handshakes OriginPolicy lets through - see design_docs/0093. */
class OriginPolicyTest {

    private static OriginPolicy policy(String... allowedOrigins) {
        OriginPolicy policy = new OriginPolicy();
        policy.allowedOrigins = allowedOrigins.length == 0 ? Optional.empty() : Optional.of(List.of(allowedOrigins));
        return policy;
    }

    @Test
    void noOriginIsNotABrowserAndIsAllowed() {
        assertTrue(policy().allows(null, "nas:8080", null));
    }

    @Test
    void sameHostAndPortIsAllowed() {
        assertTrue(policy().allows("http://nas:8080", "nas:8080", null));
        assertTrue(policy().allows("http://NAS:8080", "nas:8080", null));
    }

    /** Behind a TLS-terminating proxy that keeps Host: the Host header has no port. */
    @Test
    void hostWithoutAPortMatchesOnTheNameAlone() {
        assertTrue(policy().allows("https://torrents.example.com", "torrents.example.com", null));
    }

    @Test
    void anotherSiteIsRefused() {
        assertFalse(policy().allows("https://evil.example", "nas:8080", null));
    }

    @Test
    void sameHostOnAnotherPortIsRefusedWhenHostNamesAPort() {
        assertFalse(policy().allows("http://nas:9000", "nas:8080", null));
    }

    @Test
    void opaqueOriginsAreRefused() {
        assertFalse(policy().allows("null", "nas:8080", null));
    }

    /** nginx's default proxy_pass rewrites Host to the upstream; X-Forwarded-Host (which a
     * browser page can't set on a handshake) or the allow-list then says what the browser used. */
    @Test
    void forwardedHostOrTheAllowListCoverAProxyThatRewritesHost() {
        assertTrue(policy().allows("https://torrents.example.com", "grimtorrenter:8080",
                "torrents.example.com, proxy2.internal"));
        assertTrue(policy("https://torrents.example.com/").allows("https://torrents.example.com",
                "grimtorrenter:8080", null));
        assertFalse(policy().allows("https://torrents.example.com", "grimtorrenter:8080", null));
    }
}
