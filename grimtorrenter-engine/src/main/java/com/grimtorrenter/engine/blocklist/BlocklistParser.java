package com.grimtorrenter.engine.blocklist;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Parses an IP blocklist into an {@link IpRangeSet}. Formats are auto-detected line by line:
 * PeerGuardian {@code .p2p} ({@code Name:1.2.3.4-1.2.3.9}), eMule {@code .dat}
 * ({@code 1.2.3.4 - 1.2.3.9 , 100 , Name}), plain {@code a.b.c.d-a.b.c.d}, CIDR
 * ({@code 1.2.3.0/24}) and single addresses. {@code #}/{@code //} comments and blank lines are
 * ignored; a line that matches none is counted as skipped rather than failing the list. A gzip
 * stream is decompressed transparently. IPv4 only. See design_docs/0078.
 */
public final class BlocklistParser {

    public static final int MAX_ENTRIES = 1_000_000;
    public static final long MAX_DECOMPRESSED_BYTES = 64L << 20;
    private static final int MAX_LINE_LENGTH = 4096;

    private static final String IP = "(\\d{1,3}(?:\\.\\d{1,3}){3})";
    /** Covers both eMule ".dat" (trailing ", level, name") and a plain "a - b" range. */
    private static final Pattern RANGE = Pattern.compile("^" + IP + "\\s*-\\s*" + IP + "(?:\\s*,.*)?$");
    private static final Pattern CIDR = Pattern.compile("^" + IP + "/(\\d{1,2})$");
    private static final Pattern SINGLE = Pattern.compile("^" + IP + "$");
    /** The name may itself contain colons - the greedy prefix lands on the last one, and an IP
     * range can't contain a colon. */
    private static final Pattern P2P = Pattern.compile("^.*:\\s*" + IP + "\\s*-\\s*" + IP + "$");

    /** @param lines non-blank, non-comment lines seen
     * @param skipped how many of those matched no known format */
    public record Result(IpRangeSet ranges, int lines, int skipped) {
    }

    private BlocklistParser() {
    }

    public static Result parse(InputStream raw) throws IOException {
        return parse(raw, MAX_ENTRIES, MAX_DECOMPRESSED_BYTES);
    }

    /** @throws IOException for an unreadable stream, more than maxEntries entries, or more than
     * maxBytes of (decompressed) input - a list that big is refused whole rather than truncated,
     * so a caller never quietly runs on a partial list. */
    static Result parse(InputStream raw, int maxEntries, long maxBytes) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(raw);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        InputStream decoded = (first == 0x1f && second == 0x8b) ? new GZIPInputStream(buffered) : buffered;

        IpRangeSet.Builder builder = IpRangeSet.builder();
        int lines = 0;
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new LimitedInputStream(decoded, maxBytes, "The blocklist"), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.startsWith("//")) {
                    continue;
                }
                lines++;
                if (!parseLine(trimmed, builder)) {
                    skipped++;
                } else if (builder.count() > maxEntries) {
                    throw new IOException("The blocklist has more than " + maxEntries + " entries");
                }
            }
        }
        return new Result(builder.build(), lines, skipped);
    }

    private static boolean parseLine(String line, IpRangeSet.Builder builder) {
        if (line.length() > MAX_LINE_LENGTH) {
            return false;
        }
        Matcher m;
        if ((m = RANGE.matcher(line)).matches() || (m = P2P.matcher(line)).matches()) {
            long start = ipv4(m.group(1));
            long end = ipv4(m.group(2));
            if (start < 0 || end < 0) {
                return false;
            }
            builder.add(start, end);
            return true;
        }
        if ((m = CIDR.matcher(line)).matches()) {
            long base = ipv4(m.group(1));
            int prefix = Integer.parseInt(m.group(2));
            if (base < 0 || prefix > 32) {
                return false;
            }
            long size = 1L << (32 - prefix);
            long start = base & ~(size - 1);
            builder.add(start, start + size - 1);
            return true;
        }
        if ((m = SINGLE.matcher(line)).matches()) {
            long ip = ipv4(m.group(1));
            if (ip < 0) {
                return false;
            }
            builder.add(ip, ip);
            return true;
        }
        return false;
    }

    /** -1 for an octet above 255. Leading zeros are decimal ("001" is 1), as .dat lists write them. */
    private static long ipv4(String text) {
        String[] octets = text.split("\\.");
        long value = 0;
        for (String octet : octets) {
            int n = Integer.parseInt(octet);
            if (n > 255) {
                return -1;
            }
            value = (value << 8) | n;
        }
        return value;
    }
}
