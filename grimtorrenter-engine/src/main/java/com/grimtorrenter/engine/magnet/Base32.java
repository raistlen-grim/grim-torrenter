package com.grimtorrenter.engine.magnet;

import java.util.Locale;

/**
 * RFC 4648 Base32 decoding for the 32-character info-hash form some magnet
 * links use instead of 40-character hex. Not needed anywhere else in the
 * engine, so this stays package-private and minimal (decode only, no
 * padding handling - a 20-byte info hash encodes to exactly 32 Base32
 * characters with no padding needed).
 */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    static byte[] decode(String input) {
        String normalized = input.toUpperCase(Locale.ROOT);
        byte[] output = new byte[normalized.length() * 5 / 8];
        long buffer = 0;
        int bitsInBuffer = 0;
        int outputIndex = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = ALPHABET.indexOf(normalized.charAt(i));
            if (value < 0) {
                throw new MagnetLinkException("Invalid Base32 character in info hash: " + normalized.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8;
                output[outputIndex++] = (byte) ((buffer >> bitsInBuffer) & 0xFF);
            }
        }
        return output;
    }
}
