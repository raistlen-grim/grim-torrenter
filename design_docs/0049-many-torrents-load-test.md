# 0049 — A many-torrent concurrent restore load test

**Status:** Accepted

## Decision

The last open item from the engine stability/scale audit that produced
[[0047-bounded-file-handle-pool]] and [[0048-piece-verification-throttling]]: both were
proven correct at the unit level (`FileHandlePoolTest`, the throttling test in
`TorrentSessionTest`), but nothing exercised them **together, under real concurrent
multi-torrent load** - the actual scenario that motivated both in the first place (many
torrents restoring at once on a real process restart).

**New test: `ManyTorrentsRestoreLoadTest`** (`grimtorrenter-engine`, `torrent` package).
Restores 40 real `TorrentSession`s concurrently (`TorrentSession.restoreAsync()`, each
spawning its own virtual thread exactly as production does), all sharing one
`FileHandlePool` and one piece-verification `Semaphore` - the same shared-instance wiring
`TorrentEngine` does in production. Deliberately adversarial sizing: **40 torrents against a
5-file pool and a 4-permit verification limiter** - both mechanisms have to actually do their
job (evict, throttle) rather than coasting with headroom to spare.

### What it actually proves, and how

- **Every torrent still verifies correctly** (`completedPieceCount() == PIECES_PER_TORRENT`
  for all 40) despite real, concurrent, adversarial file-handle churn - the single most
  important assertion: a shared pool with a bug could plausibly hand one torrent's read a
  wrong/stale channel, or silently corrupt data under contention. This is the integration-level
  sibling to `TorrentStorageTest`'s own small-pool correctness test
  ([[0047-bounded-file-handle-pool]]), just now at real multi-session scale instead of one
  `TorrentStorage` instance alone.
- **Peak concurrent verification never exceeds the configured limit** - measured *exactly*,
  not sampled: `PeakTrackingSemaphore`, a test-local `Semaphore` subclass overriding
  `acquireUninterruptibly()`/`release()` to track a live counter and its high-water mark. Since
  `TorrentSession.restoreAsync()` accepts a plain `Semaphore` parameter and Java dispatches
  virtually, passing an instance of this subclass instruments the *exact* calls
  `verifyThenSettle()`/`verifyPiece()` make - zero changes to production code needed. A
  companion assertion (`peak() > 1`) guards against the test silently not exercising real
  concurrency at all (e.g. if some future change accidentally serialized everything).
- **Peak open file count stays within the configured budget** - measured by polling
  `FileHandlePool.openCount()` (made `public`, previously test-only-via-same-package, purely
  for this diagnostic purpose) from the test's main thread while every session's `state()`
  settles to `STOPPED`. Deliberately called out in the test's own comment as **best-effort,
  not exact** - unlike the `Semaphore` case, `FileHandlePool` is a `final` class with no
  overridable hook to instrument precisely without touching production code, and this is
  sampled from outside rather than counted at the exact call site. `FileHandlePoolTest`'s own
  unit tests remain the precise, deterministic proof of the eviction algorithm itself; this is
  corroboration under real load, not a replacement for that proof.

### Why the pool/permit sizing makes the "exceeds budget" case actually impossible, not just unlikely

Chosen deliberately, not arbitrarily: `MAX_CONCURRENT_VERIFICATIONS` (4) `<=` `MAX_OPEN_FILES`
(5). Since every piece-read-then-verify only ever holds exactly one pool entry at a time (each
torrent here is single-file), and the verification `Semaphore` caps how many of those can run
at once, the pool can never have more than `MAX_CONCURRENT_VERIFICATIONS` entries genuinely
*in use* simultaneously - always `<=` its own capacity, so real eviction-of-something-in-use
(the "small overshoot" case both design docs already accept as fine) is never actually needed
here. The test still forces genuine LRU churn, just via *distinct files touched over time* (40,
vastly more than 5 slots) rather than via simultaneous pressure - proving the pool correctly
recycles its slots across many sequential torrents, not just that it survives a moment of
true overcommitment.

### `autoStart=false` - scoped to the restore/verify burst itself

Every session settles to `STOPPED` once verification finishes rather than continuing into
`start()` (tracker announce, peer connections). Keeps the test focused on the actual scenario
under audit - the verification burst - without pulling in unrelated machinery (fake trackers/
peers) that [[0047-bounded-file-handle-pool]]/[[0048-piece-verification-throttling]]'s own
unit tests don't need either.

## Addendum: a second `PeakTrackingSemaphore` flake (2026-08-30)

Found as a spurious failure while unrelated feature work (a magnet-metadata-fetch change,
`[[0028-magnet-links-and-dht]]`'s own addendum) happened to run the full test suite:
`PeakTrackingSemaphore.acquireUninterruptibly()` called
`current.incrementAndGet()` *inside* the lambda passed to `peak.updateAndGet(...)`. That
lambda is a compare-and-swap retry loop - `AtomicInteger.updateAndGet()`'s own Javadoc
requires the function be side-effect-free, since it can be re-invoked more than once per call
under real contention. A side-effecting increment inside it isn't side-effect-free: under the
genuine 40-threads-racing-for-4-permits contention this test deliberately creates, a single
logical `acquireUninterruptibly()` call could increment `current` more than once if the CAS
retried, inflating the observed peak above what the real, correctly-bounding `Semaphore`
underneath ever actually permitted. The real concurrency bound was never actually violated -
only miscounted. **Fixed** by incrementing once, outside the lambda, into a local captured by
the now-pure comparison lambda. Different bug, same root shape as the first flake this test
already had (see this doc's own history) - a timing-sensitive assertion in test
instrumentation, not production code, surfacing only under real scheduling variance rather
than every run.

## Alternatives considered

- **Drive this through a real `TorrentEngine.restore()`** instead of calling
  `TorrentSession.restoreAsync()` directly 40 times - rejected; would require replicating
  `TorrentEngine`'s marker-file/directory-resolution conventions for no added coverage of the
  thing actually under test (the shared pool/limiter collaboration), just more setup code and
  indirection.
- **Instrument `FileHandlePool` the same way as the `Semaphore`** (make it non-final,
  override-friendly) - rejected as disproportionate; it would mean changing production code
  purely to make one load test's secondary, corroborating assertion exact instead of
  best-effort, when the primary correctness guarantee for the pool already comes from
  `FileHandlePoolTest`'s precise unit coverage plus this test's own data-correctness
  assertion.
