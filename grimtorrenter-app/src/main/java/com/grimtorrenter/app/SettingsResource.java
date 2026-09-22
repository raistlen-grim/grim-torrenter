package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** Read/write access to the live SettingsStore (design_docs/0041) for the frontend's
 * settings page. No SettingsView wrapper - unlike TorrentSession/DhtStatus, Settings is
 * already a flat record of primitives with no engine internals to translate, and
 * JsonSettingsStore already serializes it directly for the on-disk file, so doing the same
 * here isn't a new precedent. See design_docs/0045. */
@Path("/api/settings")
public class SettingsResource {

    @Inject
    SettingsStore settingsStore;

    @Inject
    AuthStore authStore;

    @Inject
    TorrentEngine torrentEngine;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Settings current() {
        return settingsStore.current();
    }

    /** Always replaces the whole Settings record - the frontend always sends a complete
     * object back (it only ever holds one loaded from current() in the first place), so
     * there's no partial-update/merge case to handle. Returns the stored value (not just
     * echoing the request) so the caller sees exactly what update() persisted. */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Settings update(Settings settings) {
        if (settings.rateLimitScheduleEnabled()) {
            requireParsableTime(settings.rateLimitScheduleStart(), "rateLimitScheduleStart");
            requireParsableTime(settings.rateLimitScheduleEnd(), "rateLimitScheduleEnd");
        }
        // Refuses to ever let authEnabled become true with no password to log in with -
        // otherwise a user could flip this toggle and immediately lock themselves out of
        // their own API with no way back in short of editing settings.json by hand. See
        // design_docs/0061.
        if (settings.authEnabled() && !authStore.hasPassword()) {
            throw new BadRequestException("Set a password (PUT /api/auth/password) before enabling authEnabled");
        }
        // An enabled blocklist needs somewhere to load from, and a URL must be http(s) - checked
        // here at the boundary so Blocklist itself can trust it. Only while enabled, like the
        // schedule times above: a disabled blocklist's source is kept but never read. See
        // design_docs/0078.
        if (settings.blocklistEnabled()) {
            String source = settings.blocklistSource().strip();
            if (source.isEmpty()) {
                throw new BadRequestException("Set a blocklist source (a file path or an http(s) URL) before enabling it");
            }
            if (source.contains("://") && !source.regionMatches(true, 0, "http://", 0, 7)
                    && !source.regionMatches(true, 0, "https://", 0, 8)) {
                throw new BadRequestException("A blocklist URL must start with http:// or https://");
            }
        }
        // An enabled proxy needs somewhere to go - checked here at the boundary so nothing below
        // has to cope with a half-filled configuration (ProxyConfig itself treats one as "no
        // proxy" rather than erroring on every connection). design_docs/0079.
        if (settings.proxyEnabled()) {
            if (settings.proxyHost().isBlank()) {
                throw new BadRequestException("Set a proxy host before enabling the proxy");
            }
            if (settings.proxyPort() < 1 || settings.proxyPort() > 65535) {
                throw new BadRequestException("The proxy port must be between 1 and 65535");
            }
        }
        // No eventLogRetentionDays check here, unlike the schedule times above - Settings'
        // own compact constructor already normalizes 0/negative to a safe default (see its
        // Javadoc), so by the time this method sees `settings` there is no invalid value left
        // to reject. See design_docs/0055.
        Settings before = settingsStore.current();
        settingsStore.update(settings);
        // A torrent that failed its first announce (most often because of the proxy) is retried on a
        // backoff; a change to the proxy is the thing most likely to have fixed it, so don't make it
        // wait. Only proxy fields trigger this - an unrelated save shouldn't poke every tracker.
        if (proxyChanged(before, settings)) {
            torrentEngine.retryFailedStarts();
        }
        return settingsStore.current();
    }

    private static boolean proxyChanged(Settings before, Settings after) {
        return before.proxyEnabled() != after.proxyEnabled()
                || !before.proxyHost().equals(after.proxyHost())
                || before.proxyPort() != after.proxyPort()
                || !before.proxyUsername().equals(after.proxyUsername());
    }

    /** RateLimitSchedule (grimtorrenter-engine) trusts these are valid "HH:mm" strings
     * whenever the schedule is enabled, rather than re-checking on every RateLimiter.acquire()
     * call - this is the system boundary that has to actually enforce that, not the engine's
     * hot path. Only checked while the schedule is enabled; a disabled schedule's start/end
     * are display-only and never read. See design_docs/0046. */
    private void requireParsableTime(String value, String field) {
        try {
            LocalTime.parse(value);
        } catch (NullPointerException | DateTimeParseException e) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
    }
}
