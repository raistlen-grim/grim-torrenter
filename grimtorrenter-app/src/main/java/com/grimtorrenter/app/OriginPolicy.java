package com.grimtorrenter.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which browser origins may open the WebSocket. Unlike the REST API (CrossSiteRequestFilter), a
 * WebSocket handshake can't carry a custom header, and WebSockets aren't covered by CORS at all -
 * so without this any web page could open /ws/torrents and read every torrent and library event.
 * See design_docs/0093.
 *
 * <p>Allowed: no Origin header (not a browser); an Origin whose host matches the Host header -
 * or X-Forwarded-Host, which a browser page can't set on a handshake - and, if the Host header
 * names a port, whose port matches too; or an Origin listed in grimtorrenter.allowed-origins, the
 * escape hatch for a reverse proxy that rewrites Host to its upstream's name (nginx's default
 * without {@code proxy_set_header Host $host}).
 */
@ApplicationScoped
public class OriginPolicy {

    /** Comma-separated full origins, e.g. {@code https://torrents.example.com}. */
    @ConfigProperty(name = "grimtorrenter.allowed-origins")
    Optional<List<String>> allowedOrigins;

    boolean allows(String origin, String host, String forwardedHost) {
        if (origin == null) {
            return true;
        }
        if (allowedOrigins.orElse(List.of()).stream().anyMatch(allowed -> sameOrigin(allowed, origin))) {
            return true;
        }
        return matchesHost(origin, host) || matchesHost(origin, firstForwardedHost(forwardedHost));
    }

    static boolean matchesHost(String origin, String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        URI originUri;
        try {
            originUri = URI.create(origin.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (originUri.getHost() == null) {
            return false; // "null" (sandboxed frame, file://) or garbage
        }
        URI hostUri;
        try {
            hostUri = URI.create("//" + hostHeader.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (hostUri.getHost() == null || !hostUri.getHost().equalsIgnoreCase(originUri.getHost())) {
            return false;
        }
        return hostUri.getPort() == -1 || hostUri.getPort() == effectivePort(originUri);
    }

    private static boolean sameOrigin(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String origin) {
        String trimmed = origin.strip().toLowerCase(Locale.ROOT);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** A chain of proxies appends - the first entry is what the browser asked for. */
    private static String firstForwardedHost(String forwardedHost) {
        if (forwardedHost == null) {
            return null;
        }
        int comma = forwardedHost.indexOf(',');
        return comma < 0 ? forwardedHost : forwardedHost.substring(0, comma);
    }
}
