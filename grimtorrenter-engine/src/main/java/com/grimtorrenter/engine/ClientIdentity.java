package com.grimtorrenter.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * How this client names itself to everything it talks to - the one source for the peer id
 * prefix, the HTTP User-Agent, the BEP 10 extended handshake's "v" field, and the version the
 * UI shows. The version comes from the Maven project version, written into
 * client-identity.properties by resource filtering at build time, so a release only has to
 * change the pom. See design_docs/0084.
 */
public final class ClientIdentity {

    public static final String NAME = "GrimTorrenter";

    /** BEP 20 Azureus-style two-letter client code. Chosen without a registry check - see
     * design_docs/0084's open item before treating it as settled. */
    static final String PEER_ID_CLIENT_CODE = "GT";

    /** Used when the resource is missing or was never filtered (some IDE run configurations
     * copy resources without running Maven's filtering) - identifiable as "not a real build"
     * rather than failing startup over a cosmetic value. */
    static final String UNKNOWN_VERSION = "0.0.0-dev";

    private static final String VERSION = loadVersion();

    private ClientIdentity() {
    }

    /** The full build version, qualifier included (e.g. "0.1.0-SNAPSHOT") - what the UI and the
     * REST API report, so a bug report says exactly which build it came from. */
    public static String version() {
        return VERSION;
    }

    /** Major.minor.patch only (e.g. "0.1.0") - what goes on the wire, where a "-SNAPSHOT"
     * qualifier means nothing to the other side. */
    public static String numericVersion() {
        return numericVersionOf(VERSION);
    }

    /** "GrimTorrenter 0.1.0" - the extended handshake's "v" field, which other clients show
     * as this peer's client name. */
    public static String displayName() {
        return NAME + " " + numericVersion();
    }

    /** "GrimTorrenter/0.1.0" - the HTTP User-Agent for tracker announces and other fetches. */
    public static String userAgent() {
        return NAME + "/" + numericVersion();
    }

    /** "-GT0100-" for 0.1.0 - the 8-byte Azureus-style prefix of our peer id. */
    public static String peerIdPrefix() {
        return peerIdPrefixFor(VERSION);
    }

    static String numericVersionOf(String version) {
        int[] parts = versionParts(version);
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    /** One character per version component (major, minor, patch, then a fixed 0), 0-9 then A-Z
     * for 10-35 as the convention allows; anything larger is capped at Z rather than
     * overflowing the fixed 8-byte prefix. */
    static String peerIdPrefixFor(String version) {
        int[] parts = versionParts(version);
        return "-" + PEER_ID_CLIENT_CODE + versionChar(parts[0]) + versionChar(parts[1]) + versionChar(parts[2]) + "0-";
    }

    /** Leading major.minor.patch of a version string; a missing or non-numeric component is 0,
     * and anything from the first non-digit of a component on ("-SNAPSHOT", "-rc1") is ignored. */
    private static int[] versionParts(String version) {
        int[] parts = new int[3];
        String[] pieces = version.split("\\.", 4);
        for (int i = 0; i < parts.length && i < pieces.length; i++) {
            int end = 0;
            while (end < pieces[i].length() && end < 6 && Character.isDigit(pieces[i].charAt(end))) {
                end++;
            }
            parts[i] = end == 0 ? 0 : Integer.parseInt(pieces[i], 0, end, 10);
            if (end < pieces[i].length()) {
                break;
            }
        }
        return parts;
    }

    private static char versionChar(int component) {
        if (component < 10) {
            return (char) ('0' + component);
        }
        return component < 36 ? (char) ('A' + component - 10) : 'Z';
    }

    private static String loadVersion() {
        try (InputStream in = ClientIdentity.class.getResourceAsStream("client-identity.properties")) {
            if (in == null) {
                return UNKNOWN_VERSION;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").trim();
            return version.isEmpty() || version.startsWith("${") ? UNKNOWN_VERSION : version;
        } catch (IOException e) {
            return UNKNOWN_VERSION;
        }
    }
}
