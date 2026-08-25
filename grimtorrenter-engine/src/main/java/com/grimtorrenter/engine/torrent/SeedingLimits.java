package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.settings.Settings;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Resolves the effective seeding-limit values (design_docs/0054) from the global Settings
 * defaults and a torrent's own SeedingLimitOverride - pure functions, no state, same shape as
 * RateLimitSchedule's own effectiveUploadLimit/effectiveDownloadLimit resolvers.
 */
public final class SeedingLimits {

    private SeedingLimits() {
    }

    /** Empty means "no ratio limit applies" - either the override explicitly disables it, or
     * it inherits the global default and that default is itself disabled. */
    public static OptionalDouble effectiveRatioLimit(Settings settings, SeedingLimitOverride override) {
        if (override.ratioLimit() == 0) {
            return OptionalDouble.empty();
        }
        if (override.ratioLimit() > 0) {
            return OptionalDouble.of(override.ratioLimit());
        }
        return settings.seedRatioLimitEnabled() ? OptionalDouble.of(settings.seedRatioLimit()) : OptionalDouble.empty();
    }

    /** Same resolution logic as effectiveRatioLimit, for the time-seeded metric. */
    public static OptionalLong effectiveTimeLimitMinutes(Settings settings, SeedingLimitOverride override) {
        if (override.timeLimitMinutes() == 0) {
            return OptionalLong.empty();
        }
        if (override.timeLimitMinutes() > 0) {
            return OptionalLong.of(override.timeLimitMinutes());
        }
        return settings.seedTimeLimitEnabled() ? OptionalLong.of(settings.seedTimeLimitMinutes()) : OptionalLong.empty();
    }
}
