package com.grimtorrenter.engine.bencode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes a single bencoded value from a whole byte array.
 *
 * <p>Deliberately lenient on non-canonical but unambiguous input (e.g. an
 * integer with a leading zero) - real-world torrent files occasionally
 * deviate from strict canonical form, and rejecting them outright would
 * make otherwise-usable torrents unopenable. Canonical output is instead
 * the encoder's responsibility. See design_docs/0011.
 */
public final class BencodeDecoder {

    private BencodeDecoder() {
    }

    public static BValue decode(byte[] data) {
        Prefix prefix = decodePrefix(data);
        if (prefix.bytesConsumed() != data.length) {
            throw new BencodeException("Trailing data after top-level value at offset " + prefix.bytesConsumed());
        }
        return prefix.value();
    }

    /**
     * Decodes a single bencoded value starting at offset 0, without requiring it to
     * consume the whole array - some wire formats append raw bytes after one bencoded
     * value with no delimiter (e.g. BEP 9's ut_metadata "data" message: a dict followed
     * immediately by the raw metadata piece bytes). {@code decode} above is the strict
     * whole-array form every other caller in this codebase wants.
     */
    public static Prefix decodePrefix(byte[] data) {
        Cursor cursor = new Cursor(data);
        BValue value = cursor.readValue();
        return new Prefix(value, cursor.position);
    }

    public record Prefix(BValue value, int bytesConsumed) {
    }

    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        BValue readValue() {
            byte marker = peek();
            if (marker == 'i') {
                return readInteger();
            }
            if (marker == 'l') {
                return readList();
            }
            if (marker == 'd') {
                return readDictionary();
            }
            if (marker >= '0' && marker <= '9') {
                return readString();
            }
            throw new BencodeException("Unexpected character '" + (char) marker + "' at offset " + position);
        }

        BInteger readInteger() {
            expect('i');
            int start = position;
            int end = indexOf('e', start);
            String digits = new String(data, start, end - start, StandardCharsets.US_ASCII);
            position = end + 1;
            try {
                return new BInteger(Long.parseLong(digits));
            } catch (NumberFormatException e) {
                throw new BencodeException("Malformed integer '" + digits + "' at offset " + start);
            }
        }

        BString readString() {
            int start = position;
            int colon = indexOf(':', start);
            String lengthDigits = new String(data, start, colon - start, StandardCharsets.US_ASCII);
            int length;
            try {
                length = Integer.parseInt(lengthDigits);
            } catch (NumberFormatException e) {
                throw new BencodeException("Malformed string length '" + lengthDigits + "' at offset " + start);
            }
            if (length < 0) {
                throw new BencodeException("Negative string length at offset " + start);
            }
            int contentStart = colon + 1;
            int contentEnd = contentStart + length;
            if (contentEnd > data.length) {
                throw new BencodeException(
                        "String length " + length + " exceeds available data at offset " + start);
            }
            BString result = BString.of(Arrays.copyOfRange(data, contentStart, contentEnd));
            position = contentEnd;
            return result;
        }

        BList readList() {
            expect('l');
            List<BValue> values = new ArrayList<>();
            while (peek() != 'e') {
                values.add(readValue());
            }
            position++;
            return new BList(values);
        }

        BDictionary readDictionary() {
            expect('d');
            Map<BString, BValue> entries = new LinkedHashMap<>();
            while (peek() != 'e') {
                BString key = readString();
                BValue value = readValue();
                entries.put(key, value);
            }
            position++;
            return new BDictionary(entries);
        }

        private void expect(char marker) {
            if (peek() != marker) {
                throw new BencodeException("Expected '" + marker + "' at offset " + position);
            }
            position++;
        }

        private byte peek() {
            if (position >= data.length) {
                throw new BencodeException("Unexpected end of input at offset " + position);
            }
            return data[position];
        }

        private int indexOf(char target, int from) {
            for (int i = from; i < data.length; i++) {
                if (data[i] == target) {
                    return i;
                }
            }
            throw new BencodeException("Expected '" + target + "' not found starting at offset " + from);
        }
    }
}
