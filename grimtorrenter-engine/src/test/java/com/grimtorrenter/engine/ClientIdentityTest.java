package com.grimtorrenter.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0084. */
class ClientIdentityTest {

    @Test
    void peerIdPrefixEncodesOneCharacterPerVersionComponent() {
        assertEquals("-GM0100-", ClientIdentity.peerIdPrefixFor("0.1.0-SNAPSHOT"));
        assertEquals("-GM1230-", ClientIdentity.peerIdPrefixFor("1.2.3"));
        assertEquals("-GM1C30-", ClientIdentity.peerIdPrefixFor("1.12.3"));
        assertEquals("-GMZ000-", ClientIdentity.peerIdPrefixFor("99.0.0"));
    }

    @Test
    void peerIdPrefixIsAlwaysEightCharactersWhateverTheVersionLooksLike() {
        for (String version : new String[]{"", "1", "1.2", "2.0.0-rc1", "garbage", "1.2.3.4.5", ClientIdentity.UNKNOWN_VERSION}) {
            assertEquals(8, ClientIdentity.peerIdPrefixFor(version).length(), version);
        }
    }

    @Test
    void numericVersionDropsTheQualifier() {
        assertEquals("0.1.0", ClientIdentity.numericVersionOf("0.1.0-SNAPSHOT"));
        assertEquals("2.0.0", ClientIdentity.numericVersionOf("2.0.0-rc1"));
        assertEquals("1.2.0", ClientIdentity.numericVersionOf("1.2"));
    }

    /** Proves Maven's resource filtering actually ran for this build - without it every string
     * below would carry the "not a real build" placeholder. */
    @Test
    void versionComesFromTheBuild() {
        assertNotEquals(ClientIdentity.UNKNOWN_VERSION, ClientIdentity.version());
        assertTrue(ClientIdentity.version().startsWith(ClientIdentity.numericVersion()));
        assertEquals("GrimTorrenter/" + ClientIdentity.numericVersion(), ClientIdentity.userAgent());
        assertEquals("GrimTorrenter " + ClientIdentity.numericVersion(), ClientIdentity.displayName());
    }
}
