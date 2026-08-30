package com.grimtorrenter.engine.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** See design_docs/0028's addendum. */
class LiveResizableSemaphoreTest {

    @Test
    void growingAdmitsMoreConcurrentAcquires() throws InterruptedException {
        LiveResizableSemaphore semaphore = new LiveResizableSemaphore(2);
        assertTrue(semaphore.tryAcquire());
        assertTrue(semaphore.tryAcquire());
        assertFalse(semaphore.tryAcquire(), "should be exhausted at the initial size of 2");

        semaphore.resizeTo(3);

        assertTrue(semaphore.tryAcquire(), "the extra permit from growing should be available immediately");
    }

    /** reducePermits() (what a shrink uses internally) doesn't forcibly reclaim a permit
     * already held by an in-flight acquire() - it only reduces what's available to be
     * acquired next. Confirms shrinking doesn't strand the semaphore in some inconsistent
     * state relative to permits it already handed out. */
    @Test
    void shrinkingDoesNotDisturbAlreadyIssuedPermits() {
        LiveResizableSemaphore semaphore = new LiveResizableSemaphore(3);
        assertTrue(semaphore.tryAcquire());
        assertTrue(semaphore.tryAcquire());

        semaphore.resizeTo(1);

        assertFalse(semaphore.tryAcquire(), "already down to (or below) the new total via the two held permits");

        semaphore.release();
        semaphore.release();

        assertTrue(semaphore.tryAcquire(), "back to the new configured total of 1 after both releases");
        assertFalse(semaphore.tryAcquire(), "still bounded at the new total of 1, not the original 3");
    }

    @Test
    void repeatedResizesToTheSameValueAreIdempotent() {
        LiveResizableSemaphore semaphore = new LiveResizableSemaphore(2);

        semaphore.resizeTo(2);
        semaphore.resizeTo(2);
        semaphore.resizeTo(2);

        assertEquals(2, semaphore.availablePermits(), "resizing to the same value repeatedly shouldn't drift the total");
    }
}
