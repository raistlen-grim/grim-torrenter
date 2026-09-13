package com.grimtorrenter.engine.torrent;

/**
 * A single torrent's override of the global bandwidth/connection-count defaults
 * (design_docs/0072) - one sentinel-valued field per metric, the same convention
 * SeedingLimitOverride already established:
 *
 * <ul>
 *   <li>{@code < 0} - use the global default (Settings.uploadRateLimitBytesPerSec()/
 *       downloadRateLimitBytesPerSec()/maxConnectionsPerTorrent())</li>
 *   <li>{@code 0} - explicitly no limit for this torrent, regardless of the global default</li>
 *   <li>{@code > 0} - a custom limit for this torrent only</li>
 * </ul>
 *
 * <p>All three fields are independent - a torrent can override one metric while inheriting the
 * others. Unlike SeedingLimitOverride, bandwidth here doesn't merely widen a shared cap: an
 * overridden direction draws from a dedicated per-torrent token bucket that never touches the
 * global one at all (RateLimiters.forTorrent()) - see design_docs/0072's own note on why this
 * departs from design_docs/0042's original global-cap framing. maxConnectionsOverride, unlike
 * the bandwidth fields, is only ever resolved once, at TorrentSession construction/restore time
 * - see TorrentLimits.effectiveMaxConnections() and design_docs/0072's own scope note on why a
 * Semaphore can't be live-resized the way a RateLimiter can.
 */
public record TorrentLimitOverride(long uploadBytesPerSecOverride, long downloadBytesPerSecOverride,
                                    int maxConnectionsOverride) {

    /** Every metric inherits the global default - the state every torrent starts in until its
     * override is explicitly set. */
    public static final TorrentLimitOverride INHERIT = new TorrentLimitOverride(-1, -1, -1);
}
