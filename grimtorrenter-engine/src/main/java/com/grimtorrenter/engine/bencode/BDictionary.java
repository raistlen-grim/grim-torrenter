package com.grimtorrenter.engine.bencode;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Backed by a TreeMap so iteration order always matches bencode's required
 * canonical key ordering (sorted by raw byte value), regardless of the
 * order keys were supplied or decoded in. See design_docs/0011.
 */
public record BDictionary(Map<BString, BValue> entries) implements BValue {

    public BDictionary {
        entries = Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    public BValue get(String key) {
        return entries.get(BString.of(key));
    }
}
