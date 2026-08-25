package com.grimtorrenter.engine.bencode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Encodes a BValue to canonical bencode bytes. BDictionary already iterates
 * in sorted key order (TreeMap-backed), so encoding is always canonical
 * regardless of the order a caller built the dictionary in.
 */
public final class BencodeEncoder {

    private BencodeEncoder() {
    }

    public static byte[] encode(BValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(value, out);
        return out.toByteArray();
    }

    private static void write(BValue value, ByteArrayOutputStream out) {
        switch (value) {
            case BInteger i -> writeInteger(i, out);
            case BString s -> writeString(s, out);
            case BList l -> writeList(l, out);
            case BDictionary d -> writeDictionary(d, out);
        }
    }

    private static void writeInteger(BInteger i, ByteArrayOutputStream out) {
        writeAscii("i" + i.value() + "e", out);
    }

    private static void writeString(BString s, ByteArrayOutputStream out) {
        byte[] bytes = s.bytes();
        writeAscii(bytes.length + ":", out);
        out.writeBytes(bytes);
    }

    private static void writeList(BList l, ByteArrayOutputStream out) {
        out.write('l');
        for (BValue v : l.values()) {
            write(v, out);
        }
        out.write('e');
    }

    private static void writeDictionary(BDictionary d, ByteArrayOutputStream out) {
        out.write('d');
        for (Map.Entry<BString, BValue> entry : d.entries().entrySet()) {
            writeString(entry.getKey(), out);
            write(entry.getValue(), out);
        }
        out.write('e');
    }

    private static void writeAscii(String s, ByteArrayOutputStream out) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
