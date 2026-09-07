package com.grimtorrenter.app;

/**
 * Whether GrimTorrenter's own management API has a password set, and verification/updates
 * against it - deliberately app-only (unlike SettingsStore, this has no
 * grimtorrenter-engine counterpart): nothing in the engine ever needs to know about it, this
 * only guards the REST/WebSocket layer. No username - a single shared password, since this
 * app has exactly one torrent list per deployment, not one per account. See design_docs/0061.
 */
interface AuthStore {

    boolean hasPassword();

    /** false whenever hasPassword() is false, regardless of what's passed - there's nothing
     * to match yet. */
    boolean verify(String password);

    void setPassword(String newPassword);
}
