package com.grimtorrenter.engine.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The text format of the per-torrent marker files: one key=value entry per line, split at the
 * first '='. A key may repeat (a pending magnet's trackers). Reading skips any line without an
 * '=', and callers ignore keys they don't know, so a marker written by a newer version still
 * loads. See design_docs/0099.
 */
final class KeyValueFile {

    record Entry(String key, String value) {
    }

    private KeyValueFile() {
    }

    static Entry entry(String key, Object value) {
        return new Entry(key, String.valueOf(value));
    }

    static List<Entry> read(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            int equals = line.indexOf('=');
            if (equals >= 0) {
                entries.add(new Entry(line.substring(0, equals), line.substring(equals + 1)));
            }
        }
        return entries;
    }

    static String format(List<Entry> entries) {
        StringBuilder content = new StringBuilder();
        for (Entry entry : entries) {
            content.append(entry.key()).append('=').append(entry.value()).append('\n');
        }
        return content.toString();
    }
}
