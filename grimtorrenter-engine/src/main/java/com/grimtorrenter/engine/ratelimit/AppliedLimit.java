package com.grimtorrenter.engine.ratelimit;

/**
 * The rate limit applying to some traffic right now, and where it comes from - what the UI shows
 * beside a capped rate ("Capped by the off-hours schedule"). bytesPerSec is 0, with source NONE,
 * when nothing limits it. See design_docs/0102.
 */
public record AppliedLimit(long bytesPerSec, Source source) {

    public enum Source {
        /** No limit applies. */
        NONE,
        /** The global limit from Settings (design_docs/0042). */
        GLOBAL,
        /** The off-hours schedule's limit, while its window is active (design_docs/0046). */
        SCHEDULED,
        /** This torrent's own override (design_docs/0072). */
        TORRENT
    }

    public static final AppliedLimit NONE = new AppliedLimit(0, Source.NONE);

    /** A limit of 0 or less means unlimited, as everywhere else in rate limiting. */
    static AppliedLimit of(long bytesPerSec, Source source) {
        return bytesPerSec > 0 ? new AppliedLimit(bytesPerSec, source) : NONE;
    }
}
