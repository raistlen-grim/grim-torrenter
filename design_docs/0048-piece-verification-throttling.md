# 0048 — Bounding concurrent piece verification

**Status:** Accepted

## Decision

Second finding from the same stability/scale audit that produced
[[0047-bounded-file-handle-pool]]: `TorrentSession.verifyThenSettle()` (restore-time re-hash)
and `verifyPiece()` (normal completion) both read a **whole piece** into one `byte[]` before
SHA-1-hashing it (`PieceManager.verify()`). Piece sizes can run into several MB. Individually
fine, but nothing bounded how many of these could happen **at once across the whole engine**:
every restoring torrent re-verifies its entire piece set on its own unthrottled virtual thread
(`Thread.ofVirtual().start(() -> session.verifyThenSettle(...))`), so a process restart with
many torrents to restore fires off that many concurrent full-piece-buffer-plus-hash bursts
simultaneously - a genuine transient GC/memory spike under load, not a leak, but exactly the
kind of thing that undermines "handle many torrents without spiking resource usage."

**Fix: a shared `java.util.concurrent.Semaphore`, engine-wide, acquired around every
read-then-verify pair** in both `verifyThenSettle()`'s per-piece loop and `verifyPiece()`.
Threaded through via the exact same pattern already used for `RateLimiters` and
`FileHandlePool` ([[0042-rate-limiting]], [[0047-bounded-file-handle-pool]]): a new sibling
overload at each level of `TorrentEngine`'s and `TorrentSession`'s constructor/factory chains,
so every pre-existing caller and test keeps compiling and behaving exactly as before.

### Why a raw `Semaphore`, not a wrapper class

Unlike `FileHandlePool` (LRU eviction, reference counting, its own real logic worth naming and
testing in isolation), bounding concurrency here is exactly what `java.util.concurrent
.Semaphore` already does, with nothing this project needs to add on top - `acquire()` before
the read+verify pair, `release()` in a `finally` after. Wrapping it in a dedicated class would
be pure ceremony. `Semaphore` is also already virtual-thread-safe in the way that matters here
- it's `AbstractQueuedSynchronizer`-based, not a JVM monitor, so a virtual thread parked
waiting on `acquireUninterruptibly()` doesn't pin its carrier the way blocking inside a
`synchronized` block would ([[0007-concurrency-model]]'s own care point, also cited in 0047).

`acquireUninterruptibly()`, not `acquire()` - this codebase has no interruption-based
cancellation path for the background verification thread (the existing "abandoned mid-verify"
race in `verifyThenSettle()` is handled by a cooperative `state != VERIFYING` check each loop
iteration, not by `Thread.interrupt()`), so there's no `InterruptedException` this call site
would ever need to react to.

### Held across both the read *and* the verify, not just the read

The permit is released only after `pieceManager.verify()` (the SHA-1 hash) completes, not
right after `storage.read()`. Releasing early would still leave the just-read `byte[]` alive
during hashing without it counting against the limiter - missing exactly the CPU-bound half of
what this is meant to bound.

### Default: available processor count, configurable via `grimtorrenter.max-concurrent-piece-verifications`

Verifying more pieces in parallel than there are CPU cores buys no extra SHA-1 throughput -
it's purely CPU-bound work - so it only adds more simultaneous full-piece buffers in memory
for no benefit. `Runtime.getRuntime().availableProcessors()` is the principled default, not an
arbitrary fixed number. `0` (the `@ConfigProperty` default, since a default value has to be a
compile-time constant and can't call `availableProcessors()` itself) means "use processor
count," resolved in `TorrentEngineProducer` - the same "0 is a special sentinel value, not a
literal zero-permits deadlock" pattern `Settings`' own rate limit fields already use for "0
means unlimited." Deploy-time `@ConfigProperty`, same category as `grimtorrenter.max-open-files`
- a resource-sizing knob, not a live `/settings`-page preference.

## Testing

- `TorrentSessionTest` gained `restoreVerificationWaitsForAPieceVerificationPermit` - a
  restoring session given a zero-permit `Semaphore` must stay stuck in `VERIFYING`
  indefinitely (asserted after a short wait); releasing exactly one permit must let it
  proceed all the way to `SEEDING`. Proves the parameter is actually wired into the
  read-verify path, not just accepted and ignored.

## Alternatives considered

- **A dedicated `PieceVerificationLimiter` wrapper class** - rejected; see "Why a raw
  Semaphore" above.
- **Bound only the restore-time burst (`verifyThenSettle`), not steady-state `verifyPiece`** -
  rejected; steady-state completions are naturally spread out by network throughput and so
  burst less badly, but there's no reason to special-case them - one limiter governing "how
  many pieces are mid-verification across the engine, full stop" is simpler than two policies
  for what's conceptually the same operation.
- **A fixed default (e.g. 4) instead of processor count** - rejected; processor count is a
  principled bound tied to what this is actually limited by (CPU-bound hashing), not a number
  picked by guesswork.
