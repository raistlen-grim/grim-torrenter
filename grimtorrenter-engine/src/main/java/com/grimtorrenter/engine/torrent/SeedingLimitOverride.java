package com.grimtorrenter.engine.torrent;

/**
 * A single torrent's override of the global seeding-limit defaults (design_docs/0054) - one
 * sentinel-valued field per metric, matching Settings' own repeated "0 or negative means X"
 * idiom rather than a nullable wrapper type:
 *
 * <ul>
 *   <li>{@code < 0} - use the global default (Settings.seedRatioLimit()/seedTimeLimitMinutes(),
 *       gated by their own enabled flags)</li>
 *   <li>{@code 0} - explicitly no limit for this torrent, regardless of the global default</li>
 *   <li>{@code > 0} - a custom limit for this torrent only</li>
 * </ul>
 *
 * <p>ratioLimit and timeLimitMinutes are independent - a torrent can override one metric while
 * inheriting the other. See SeedingLimits for how this combines with Settings to produce the
 * effective limit actually checked.
 */
public record SeedingLimitOverride(double ratioLimit, long timeLimitMinutes) {

    /** Every metric inherits the global default - the state every torrent starts in until its
     * override is explicitly set. */
    public static final SeedingLimitOverride INHERIT = new SeedingLimitOverride(-1, -1);
}
