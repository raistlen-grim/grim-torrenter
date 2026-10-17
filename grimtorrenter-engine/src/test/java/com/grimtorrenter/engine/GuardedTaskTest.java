package com.grimtorrenter.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** See design_docs/0093. */
class GuardedTaskTest {

    /** The point of the wrapper: unwrapped, the first throw would cancel every later run. */
    @Test
    void aPeriodicTaskKeepsRunningAfterItThrows() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ranAgain = new CountDownLatch(1);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.scheduleWithFixedDelay(GuardedTask.of("test", () -> {
                if (runs.incrementAndGet() == 1) {
                    throw new IllegalStateException("first tick fails");
                }
                ranAgain.countDown();
            }), 0, 10, TimeUnit.MILLISECONDS);

            assertTrue(ranAgain.await(5, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }
}
