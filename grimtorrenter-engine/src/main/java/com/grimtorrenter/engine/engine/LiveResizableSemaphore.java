package com.grimtorrenter.engine.engine;

import java.util.concurrent.Semaphore;

/**
 * A {@link Semaphore} whose total permit count can be changed after construction - plain
 * {@code Semaphore} has no public "resize beyond the initial count" API, but it doesn't need
 * one: {@link #release(int)} safely adds permits with no prior {@code acquire()} required, and
 * the JDK's own protected {@link #reducePermits(int)} safely removes them without disturbing
 * permits already issued to an in-flight {@code acquire()} - its own Javadoc: "useful in
 * subclasses that use semaphores to track resources that become unavailable," exactly this
 * case. See design_docs/0028's addendum.
 *
 * <p>{@code availablePermits()} reflects currently-*free* permits, not the configured total, so
 * the delta on a resize has to be computed against a total this class tracks itself.
 * {@code resizeTo} is {@code synchronized} - fine per design_docs/0007's "no synchronized in
 * the hot path" rule, since a resize happens at most once per magnet-add attempt (before its
 * retry loop starts), not per-candidate or per-byte.
 */
final class LiveResizableSemaphore extends Semaphore {

    private int configuredTotal;

    LiveResizableSemaphore(int initialPermits) {
        super(initialPermits, true);
        this.configuredTotal = initialPermits;
    }

    synchronized void resizeTo(int newTotal) {
        int delta = newTotal - configuredTotal;
        if (delta > 0) {
            release(delta);
        } else if (delta < 0) {
            reducePermits(-delta);
        }
        configuredTotal = newTotal;
    }
}
