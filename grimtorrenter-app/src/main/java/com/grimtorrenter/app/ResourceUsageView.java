package com.grimtorrenter.app;

/** processCpuLoad is 0.0-1.0, or -1.0 if the JVM can't determine it (the same sentinel
 * {@code com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()} itself returns) -
 * passed straight through rather than reinventing an "unavailable" convention. */
public record ResourceUsageView(long heapUsedBytes, long heapMaxBytes, double processCpuLoad,
        int availableProcessors) {
}
