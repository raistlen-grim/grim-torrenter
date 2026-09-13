package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.settings.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TorrentLimitsTest {

    /** maxConnectionsPerTorrent isn't reachable from this shorter constructor (defaults to 0,
     * normalized to 30 by Settings' own compact constructor) - sufficient here since these
     * tests only need to confirm effectiveMaxConnections()'s three sentinel branches, not vary
     * the global default itself. */
    private static Settings settingsWithDefaultMaxConnections() {
        return new Settings(true, true, 0, 0, false, "23:00", "07:00", 0, 0,
                com.grimtorrenter.engine.mse.EncryptionMode.PREFERRED, 0, false, 2.0, false, 1440);
    }

    @Test
    void inheritedMaxConnectionsUsesTheGlobalDefault() {
        Settings settings = settingsWithDefaultMaxConnections();

        int effective = TorrentLimits.effectiveMaxConnections(settings, TorrentLimitOverride.INHERIT);

        assertEquals(30, effective);
    }

    @Test
    void aPositiveOverrideMaxConnectionsWinsRegardlessOfTheGlobalDefault() {
        Settings settings = settingsWithDefaultMaxConnections();
        TorrentLimitOverride override = new TorrentLimitOverride(-1, -1, 5);

        int effective = TorrentLimits.effectiveMaxConnections(settings, override);

        assertEquals(5, effective);
    }

    @Test
    void aZeroOverrideMaxConnectionsMeansUnlimited() {
        Settings settings = settingsWithDefaultMaxConnections();
        TorrentLimitOverride override = new TorrentLimitOverride(-1, -1, 0);

        int effective = TorrentLimits.effectiveMaxConnections(settings, override);

        assertEquals(Integer.MAX_VALUE, effective);
    }
}
