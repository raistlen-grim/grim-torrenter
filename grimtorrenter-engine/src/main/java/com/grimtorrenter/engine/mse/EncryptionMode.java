package com.grimtorrenter.engine.mse;

/**
 * User-configurable policy for Message Stream Encryption (design_docs/0052) - global, not
 * per-torrent (matches design_docs/0042's rate-limiting scoping precedent), and read live on
 * every connection attempt/accept rather than baked in at startup, since there's no long-lived
 * object whose construction would need to bake it in (unlike dhtEnabled/acceptIncomingConnections
 * - see design_docs/0041).
 */
public enum EncryptionMode {
    /** Never attempt or accept MSE negotiation - every connection is the plain BT handshake,
     * exactly as before this feature existed. */
    DISABLED,
    /** Attempt MSE first; fall back to a plain connection if the peer doesn't support it.
     * Accepts either an MSE-negotiating or a plaintext-handshake inbound connection. */
    PREFERRED,
    /** Only ever attempt/accept MSE - no fallback. A peer that can't do MSE simply can't
     * connect. */
    REQUIRED
}
