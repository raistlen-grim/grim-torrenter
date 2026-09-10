package com.grimtorrenter.engine.lsd;

import com.grimtorrenter.engine.metainfo.InfoHash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encodes/decodes BEP 14's {@code BT-SEARCH} announcement - a small HTTP-request-shaped text
 * message sent over UDP multicast, not bencode like every other wire format in this codebase
 * (DHT's KRPC, PEX's ut_pex payload). IPv4 only - MULTICAST_GROUP/MULTICAST_PORT are the well-
 * known BEP 14 rendezvous point (239.192.152.143:6771); the IPv6 group is a deliberate,
 * documented omission (see design_docs/0062), matching PexCodec.requireIPv4's existing
 * project-wide IPv4-only scope.
 */
public final class LsdCodec {

    public static final String MULTICAST_GROUP = "239.192.152.143";
    public static final int MULTICAST_PORT = 6771;

    private static final String REQUEST_LINE = "BT-SEARCH * HTTP/1.1";
    private static final String HOST_HEADER = "Host";
    private static final String PORT_HEADER = "Port";
    private static final String INFOHASH_HEADER = "Infohash";
    private static final String COOKIE_HEADER = "cookie";
    private static final String CRLF = "\r\n";

    private LsdCodec() {
    }

    public static byte[] encode(LsdMessage message) {
        String text = REQUEST_LINE + CRLF
                + HOST_HEADER + ": " + MULTICAST_GROUP + ":" + MULTICAST_PORT + CRLF
                + PORT_HEADER + ": " + message.port() + CRLF
                + INFOHASH_HEADER + ": " + message.infoHash().hex() + CRLF
                + COOKIE_HEADER + ": " + message.cookie() + CRLF
                + CRLF;
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** Header names are matched case-insensitively (real-world LSD implementations disagree on
     * casing) via a TreeMap keyed by String.CASE_INSENSITIVE_ORDER. Only the first line and the
     * headers up to the first blank line are read - anything after (there shouldn't be
     * anything) is ignored rather than rejected, the same "tolerate trailing noise" leniency
     * PexCodec's own missing-key handling applies elsewhere in this codebase. */
    public static LsdMessage decode(byte[] payload) {
        String text = new String(payload, StandardCharsets.US_ASCII);
        String[] lines = text.split("\r\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals(REQUEST_LINE)) {
            throw new LsdException("Not a BT-SEARCH announcement");
        }

        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }

        String portValue = headers.get(PORT_HEADER);
        String infoHashValue = headers.get(INFOHASH_HEADER);
        if (portValue == null || infoHashValue == null) {
            throw new LsdException("BT-SEARCH announcement missing Port or Infohash header");
        }

        int port;
        try {
            port = Integer.parseInt(portValue);
        } catch (NumberFormatException e) {
            throw new LsdException("BT-SEARCH announcement has a non-numeric Port header");
        }

        InfoHash infoHash;
        try {
            // Route through InfoHash.of(byte[]), not new InfoHash(infoHashValue) directly -
            // HexFormat.formatHex() canonicalizes to lowercase, matching how every InfoHash
            // elsewhere in this codebase is minted. A peer sending uppercase hex would
            // otherwise produce an InfoHash that fails equals()/hashCode() lookups against our
            // own lowercase-keyed session map.
            infoHash = InfoHash.of(HexFormat.of().parseHex(infoHashValue.toLowerCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new LsdException("BT-SEARCH announcement has an invalid Infohash header");
        }

        String cookie = headers.getOrDefault(COOKIE_HEADER, "");
        return new LsdMessage(infoHash, port, cookie);
    }
}
