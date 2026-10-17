package com.grimtorrenter.app;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Failed-login backoff for the single shared password - global, not per-IP (per-IP tracking
 * behind a reverse proxy needs a trusted-proxy header policy that's easy to get wrong). The first
 * FREE_FAILURES consecutive failures cost no delay: an honest typo or two shouldn't be penalised
 * like a real guessing attempt. After that the lockout doubles per failure up to
 * MAX_BACKOFF_SECONDS, and the next successful login resets it. See design_docs/0061.
 *
 * <p>Only one verification runs at a time (tryBegin() → verify → finish()). Without that, every
 * request arriving while the lockout was clear passed the check before any of them recorded its
 * failure, so a burst got one guess each - and each guess costs a full PBKDF2 hash, which also
 * made a burst a CPU-exhaustion lever. A request that finds the slot busy is refused like a
 * locked-out one. See design_docs/0094.
 */
@ApplicationScoped
public class LoginThrottle {

    private static final int MAX_BACKOFF_SECONDS = 60;
    private static final int FREE_FAILURES = 4;

    private final Semaphore attemptSlot = new Semaphore(1);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> lockedUntil = new AtomicReference<>(Instant.EPOCH);

    /** Claims the single attempt slot. False while locked out or while another attempt is being
     * verified; on true, the caller must call finish() exactly once. */
    boolean tryBegin() {
        if (!attemptSlot.tryAcquire()) {
            return false;
        }
        if (isLockedOut()) {
            attemptSlot.release();
            return false;
        }
        return true;
    }

    void finish(boolean succeeded) {
        try {
            if (succeeded) {
                recordSuccess();
            } else {
                recordFailure();
            }
        } finally {
            attemptSlot.release();
        }
    }

    boolean isLockedOut() {
        return Instant.now().isBefore(lockedUntil.get());
    }

    void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures <= FREE_FAILURES) {
            return;
        }
        long backoffSeconds = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures - FREE_FAILURES, 6));
        lockedUntil.set(Instant.now().plusSeconds(backoffSeconds));
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
        lockedUntil.set(Instant.EPOCH);
    }
}
