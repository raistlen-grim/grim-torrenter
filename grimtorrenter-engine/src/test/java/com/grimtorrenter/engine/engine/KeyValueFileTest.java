package com.grimtorrenter.engine.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** design_docs/0099. */
class KeyValueFileTest {

    @Test
    void roundTripsRepeatedKeysAndValuesContainingEquals(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marker");
        List<KeyValueFile.Entry> entries = List.of(
                KeyValueFile.entry("tracker", "http://a/announce?x=1"),
                KeyValueFile.entry("tracker", "udp://b:6969"),
                KeyValueFile.entry("count", 3));

        Files.writeString(file, KeyValueFile.format(entries));

        assertEquals(entries, KeyValueFile.read(file));
    }

    @Test
    void skipsLinesWithoutAnEqualsSign(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("marker");
        Files.writeString(file, "garbage\nkey=value\n\n");

        assertEquals(List.of(new KeyValueFile.Entry("key", "value")), KeyValueFile.read(file));
    }
}
