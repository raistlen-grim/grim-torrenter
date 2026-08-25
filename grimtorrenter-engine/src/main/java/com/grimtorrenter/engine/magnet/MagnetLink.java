package com.grimtorrenter.engine.magnet;

import com.grimtorrenter.engine.metainfo.InfoHash;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parsed magnet: URI. The magnet URI format itself isn't a numbered BEP -
 * BEP 9 covers the metadata exchange this feeds into, and BEP 10 the
 * extension protocol that exchange runs over (see design_docs/0028). Only
 * the parameters needed to bootstrap that exchange are kept: "xt" (exact
 * topic, must resolve to a v1 info hash via urn:btih:), "dn" (display
 * name), and "tr" (tracker, repeatable). Other known params ("xl", "as",
 * "xs", "kt", a v2 "xt=urn:btmh:...", ...) are accepted but ignored rather
 * than rejected - an unrecognized param doesn't make the link unusable.
 */
public record MagnetLink(InfoHash infoHash, String displayName, List<String> trackers) {

    private static final String SCHEME_PREFIX = "magnet:?";
    private static final String BTIH_PREFIX = "urn:btih:";

    public static MagnetLink parse(String uri) {
        if (uri == null || !uri.toLowerCase(Locale.ROOT).startsWith(SCHEME_PREFIX)) {
            throw new MagnetLinkException("Not a magnet URI: " + uri);
        }
        String query = uri.substring(SCHEME_PREFIX.length());

        InfoHash infoHash = null;
        String displayName = null;
        List<String> trackers = new ArrayList<>();

        for (String rawParam : query.split("&")) {
            if (rawParam.isEmpty()) {
                continue;
            }
            int equals = rawParam.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = rawParam.substring(0, equals);
            String value = decode(rawParam.substring(equals + 1));
            switch (key) {
                case "xt" -> {
                    // A magnet link may carry multiple xt params (e.g. a v2 urn:btmh: hybrid
                    // link alongside a v1 urn:btih:) - the first usable btih wins rather than
                    // letting an unsupported later xt null out one already found.
                    if (infoHash == null) {
                        infoHash = parseExactTopicAsInfoHash(value);
                    }
                }
                case "dn" -> displayName = value;
                case "tr" -> trackers.add(value);
                default -> {
                    // Ignored - see class Javadoc.
                }
            }
        }

        if (infoHash == null) {
            throw new MagnetLinkException("Magnet URI has no usable 'xt=urn:btih:' parameter: " + uri);
        }
        return new MagnetLink(infoHash, displayName, List.copyOf(trackers));
    }

    /** Returns null (not a failure) for an xt namespace this can't use, e.g. urn:btmh: -
     * the caller treats "never found a usable xt" as the actual error. */
    private static InfoHash parseExactTopicAsInfoHash(String value) {
        if (!value.regionMatches(true, 0, BTIH_PREFIX, 0, BTIH_PREFIX.length())) {
            return null;
        }
        String hash = value.substring(BTIH_PREFIX.length());
        return switch (hash.length()) {
            case 40 -> new InfoHash(hash.toLowerCase(Locale.ROOT));
            case 32 -> InfoHash.of(Base32.decode(hash));
            default -> throw new MagnetLinkException("Unsupported info hash length in xt param: " + hash);
        };
    }

    /** URLDecoder (application/x-www-form-urlencoded) rather than a bare percent-decoder -
     * treats '+' as space, matching how magnet links are generated/consumed in practice. */
    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
