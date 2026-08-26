package com.grimtorrenter.app;

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
        // No eventLogRetentionDays check here, unlike the schedule times above - Settings'
        // own compact constructor already normalizes 0/negative to a safe default (see its
        // Javadoc), so by the time this method sees `settings` there is no invalid value left
        // to reject. See design_docs/0055.
        settingsStore.update(settings);
        return settingsStore.current();
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
