package com.grimtorrenter.engine;

/**
 * Wraps a periodic task so an exception is logged instead of escaping. A ScheduledExecutorService
 * silently cancels every later run of a fixed-delay task the first time it throws - one bad tick
 * (a full disk under the event log, an unexpected peer address in a PEX message) would otherwise
 * end that task until the next restart, with nothing logged. Every scheduled task in the engine
 * goes through this. See design_docs/0093.
 */
public final class GuardedTask {

    private static final System.Logger LOG = System.getLogger(GuardedTask.class.getName());

    private GuardedTask() {
    }

    public static Runnable of(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Periodic " + name + " failed - it will run again on its next tick", e);
            }
        };
    }
}
