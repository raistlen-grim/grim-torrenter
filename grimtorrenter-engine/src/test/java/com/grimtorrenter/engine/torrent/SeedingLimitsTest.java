package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.settings.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedingLimitsTest {

    private static Settings settingsWith(boolean ratioEnabled, double ratio, boolean timeEnabled, long timeMinutes) {
        return new Settings(true, true, 0, 0, false, "23:00", "07:00", 0, 0,
                com.grimtorrenter.engine.mse.EncryptionMode.PREFERRED, 0, ratioEnabled, ratio, timeEnabled, timeMinutes);
    }

    @Test
    void inheritedRatioUsesTheGlobalDefaultWhenEnabled() {
        Settings settings = settingsWith(true, 2.5, false, 0);

        var effective = SeedingLimits.effectiveRatioLimit(settings, SeedingLimitOverride.INHERIT);

        assertTrue(effective.isPresent());
        assertEquals(2.5, effective.getAsDouble());
    }

    @Test
    void inheritedRatioIsEmptyWhenTheGlobalDefaultIsDisabled() {
        Settings settings = settingsWith(false, 2.5, false, 0);

        assertFalse(SeedingLimits.effectiveRatioLimit(settings, SeedingLimitOverride.INHERIT).isPresent());
    }

    @Test
    void aPositiveOverrideRatioWinsRegardlessOfTheGlobalDefault() {
        Settings settings = settingsWith(false, 2.5, false, 0);

        var effective = SeedingLimits.effectiveRatioLimit(settings, new SeedingLimitOverride(1.0, -1));

        assertTrue(effective.isPresent());
        assertEquals(1.0, effective.getAsDouble());
    }

    @Test
    void aZeroOverrideRatioExplicitlyDisablesItEvenWhenTheGlobalDefaultIsEnabled() {
        Settings settings = settingsWith(true, 2.5, false, 0);

        assertFalse(SeedingLimits.effectiveRatioLimit(settings, new SeedingLimitOverride(0, -1)).isPresent());
    }

    @Test
    void inheritedTimeLimitUsesTheGlobalDefaultWhenEnabled() {
        Settings settings = settingsWith(false, 0, true, 120);

        var effective = SeedingLimits.effectiveTimeLimitMinutes(settings, SeedingLimitOverride.INHERIT);

        assertTrue(effective.isPresent());
        assertEquals(120, effective.getAsLong());
    }

    @Test
    void inheritedTimeLimitIsEmptyWhenTheGlobalDefaultIsDisabled() {
        Settings settings = settingsWith(false, 0, false, 120);

        assertFalse(SeedingLimits.effectiveTimeLimitMinutes(settings, SeedingLimitOverride.INHERIT).isPresent());
    }

    @Test
    void aPositiveOverrideTimeLimitWinsRegardlessOfTheGlobalDefault() {
        Settings settings = settingsWith(false, 0, false, 120);

        var effective = SeedingLimits.effectiveTimeLimitMinutes(settings, new SeedingLimitOverride(-1, 60));

        assertTrue(effective.isPresent());
        assertEquals(60, effective.getAsLong());
    }

    @Test
    void aZeroOverrideTimeLimitExplicitlyDisablesItEvenWhenTheGlobalDefaultIsEnabled() {
        Settings settings = settingsWith(false, 0, true, 120);

        assertFalse(SeedingLimits.effectiveTimeLimitMinutes(settings, new SeedingLimitOverride(-1, 0)).isPresent());
    }
}
