package com.grimtorrenter.engine.bencode;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A bencode byte string. Bencode strings are raw bytes, not necessarily
 * valid UTF-8 (e.g. piece hashes, peer IDs) - {@code raw} stores those bytes
 * losslessly via ISO-8859-1, which maps every byte value 0-255 to exactly
 * one char, giving correct equals/hashCode/compareTo (byte-order) for free
 * from String's own implementation. See design_docs/0011.
 */
public record BString(String raw) implements BValue, Comparable<BString> {

    public BString {
        Objects.requireNonNull(raw, "raw");
    }

    public static BString of(byte[] bytes) {
        return new BString(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    public static BString of(String utf8Text) {
        Objects.requireNonNull(utf8Text, "utf8Text");
        return of(utf8Text.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        return raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    public String utf8() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    public int length() {
        return raw.length();
    }

    @Override
    public int compareTo(BString other) {
        return raw.compareTo(other.raw);
    }
}
