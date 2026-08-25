package com.grimtorrenter.engine.magnet;

import com.grimtorrenter.engine.metainfo.InfoHash;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagnetLinkTest {

    private static final String HEX_HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void parsesHexInfoHashDisplayNameAndTrackers() {
        String uri = "magnet:?xt=urn:btih:" + HEX_HASH
                + "&dn=Some+Torrent+Name"
                + "&tr=http%3A%2F%2Ftracker.example%2Fannounce"
                + "&tr=udp%3A%2F%2Ftracker2.example%3A6969%2Fannounce";

        MagnetLink link = MagnetLink.parse(uri);

        assertEquals(new InfoHash(HEX_HASH), link.infoHash());
        assertEquals("Some Torrent Name", link.displayName());
        assertEquals(
                List.of("http://tracker.example/announce", "udp://tracker2.example:6969/announce"),
                link.trackers());
    }

    @Test
    void upperCaseHexInfoHashIsNormalizedToLowercase() {
        MagnetLink link = MagnetLink.parse("magnet:?xt=urn:btih:" + HEX_HASH.toUpperCase());

        assertEquals(HEX_HASH, link.infoHash().hex());
    }

    @Test
    void parsesBase32InfoHash() {
        // "A" x32 in Base32 decodes to 20 zero bytes - see Base32Test for the algorithm
        // itself; this only confirms MagnetLink wires the 32-char case through correctly.
        MagnetLink link = MagnetLink.parse("magnet:?xt=urn:btih:" + "A".repeat(32));

        assertEquals("0".repeat(40), link.infoHash().hex());
    }

    @Test
    void displayNameAndTrackersAreOptional() {
        MagnetLink link = MagnetLink.parse("magnet:?xt=urn:btih:" + HEX_HASH);

        assertNull(link.displayName());
        assertTrue(link.trackers().isEmpty());
    }

    @Test
    void unknownParamsAreIgnoredNotRejected() {
        MagnetLink link = MagnetLink.parse(
                "magnet:?xt=urn:btih:" + HEX_HASH + "&xl=123456&kt=some+keywords");

        assertEquals(HEX_HASH, link.infoHash().hex());
    }

    @Test
    void firstUsableBtihWinsOverAnUnsupportedXtNamespace() {
        MagnetLink link = MagnetLink.parse(
                "magnet:?xt=urn:btih:" + HEX_HASH + "&xt=urn:btmh:1220" + "ab".repeat(32));

        assertEquals(HEX_HASH, link.infoHash().hex());
    }

    @Test
    void rejectsNonMagnetUri() {
        assertThrows(MagnetLinkException.class, () -> MagnetLink.parse("http://example.com/not-a-magnet"));
    }

    @Test
    void rejectsMissingExactTopic() {
        assertThrows(MagnetLinkException.class, () -> MagnetLink.parse("magnet:?dn=No+Info+Hash+Here"));
    }

    @Test
    void rejectsExactTopicWithUnsupportedNamespaceOnly() {
        assertThrows(MagnetLinkException.class,
                () -> MagnetLink.parse("magnet:?xt=urn:btmh:1220" + "ab".repeat(32)));
    }

    @Test
    void rejectsWrongLengthInfoHash() {
        assertThrows(MagnetLinkException.class, () -> MagnetLink.parse("magnet:?xt=urn:btih:tooshort"));
    }
}
