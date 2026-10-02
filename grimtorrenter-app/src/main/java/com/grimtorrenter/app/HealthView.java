package com.grimtorrenter.app;

import java.util.List;

/**
 * GET /api/system/health - everything the Health page shows, grouped. Group and check names are
 * stable identifiers a client maps to its own labels and icons (the same closed-set-by-key shape
 * ServiceStatusView uses); message is ready-to-show text, so a client has nothing to compute.
 * status is the worst state present: FAILED, else WARNING, else OK. See design_docs/0086.
 */
public record HealthView(String status, List<Group> groups) {

    /** OK - working. INFO - a fact, neither good nor bad. WARNING - working, but worth a look.
     * FAILED - not working; counts toward the UI's alarm badge. DISABLED - switched off. */
    public enum State {
        OK, INFO, WARNING, FAILED, DISABLED
    }

    public record Group(String name, List<Check> checks) {
    }

    public record Check(String name, State state, String message) {
    }

    public static HealthView of(List<Group> groups) {
        boolean failed = false;
        boolean warning = false;
        for (Group group : groups) {
            for (Check check : group.checks()) {
                failed |= check.state() == State.FAILED;
                warning |= check.state() == State.WARNING;
            }
        }
        return new HealthView(failed ? "FAILED" : warning ? "WARNING" : "OK", groups);
    }
}
