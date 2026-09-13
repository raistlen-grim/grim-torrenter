package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.settings.Settings;

/**
 * Resolves the effective max-connections value (design_docs/0072) from the global Settings
 * default and a torrent's own TorrentLimitOverride - a pure function, no state, same shape as
 * SeedingLimits' own resolvers.
 *
 * <p>The bandwidth fields of TorrentLimitOverride need no equivalent resolver here -
 * RateLimiters.forTorrent() reads uploadBytesPerSecOverride()/downloadBytesPerSecOverride()
 * directly, since a dedicated per-torrent RateLimiter is only ever consulted while the override
 * is already known to be non-negative (see that method's own comment).
 */
public final class TorrentLimits {

    private TorrentLimits() {
    }

    /** Integer.MAX_VALUE means "no cap" - the same "effectively unbounded" convention
     * TorrentSession.create()'s own lower-arity overload already uses for
     * pieceVerificationLimiter, reused here so a Semaphore sized from this value behaves as
     * unlimited without needing a separate unbounded-Semaphore code path. */
    public static int effectiveMaxConnections(Settings settings, TorrentLimitOverride override) {
        if (override.maxConnectionsOverride() == 0) {
            return Integer.MAX_VALUE;
        }
        if (override.maxConnectionsOverride() > 0) {
            return override.maxConnectionsOverride();
        }
        return settings.maxConnectionsPerTorrent();
    }
}
