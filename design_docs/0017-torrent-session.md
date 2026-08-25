# 0017 — TorrentSession orchestration

**Status:** Accepted

## Decision

`TorrentSession` wires `TrackerClient` + `PeerConnection`s + `PieceManager`
+ `TorrentStorage` together via a `PeerConnectionListener` implementation
whose callbacks run on each peer's own read-loop virtual thread (per
[[0015-peer-connection]]) — no separate message-processing threads are
spawned; the existing per-connection virtual threads double as the
concurrent message-handling workers.

**Startup never verifies existing on-disk data** (confirmed with the
user) — every `start()` treats all pieces as `NEEDED`. Real verify-on-resume
is deferred to Phase 2, once persisted resume state exists to say *which*
pieces are worth checking, rather than blindly hashing an entire
potentially-large file on every single start including brand new
downloads. `TorrentState.VERIFYING` exists in the enum for that future
feature but Phase 1 never enters it.

**Two small additions were needed in already-built layers to make this
orchestration work correctly:**
- `HttpTrackerClient` didn't implement BEP 3's `tracker id` echo-back
  convention. Added as internal state on the client (`volatile String
  trackerId`, updated from each response, sent as `trackerid=` on
  subsequent requests) rather than a field on `TrackerRequest` — it's
  tracker-connection-scoped state, not per-request data, and one
  `HttpTrackerClient` instance lives for a session's whole lifetime.
- `PieceManager.completedCount()` was added for progress/bitfield-building
  use (see [[0016-piece-and-storage]]) — trivial delegation to the existing
  `completedPieces` bitset.

**Locking**: state transitions (`start`/`stop`/`fail`) are `synchronized`
on `this`, mirroring [[0015-peer-connection]]'s idempotent-disconnect
pattern. One deliberate exception: `checkForCompletion()` only holds the
lock for the state check-and-transition itself, not for the subsequent
`COMPLETED` tracker announce (a blocking HTTP call) — that runs after the
lock is released. Holding the lock through a blocking network call would
stall any other thread calling `start`/`stop`/`fail` concurrently
(realistically another peer's read-loop thread hitting `fail()`), which is
worth avoiding even though the practical window is narrow (only fires once
per torrent, at 100% completion).

This split caused a real, intermittent `TorrentSessionTest` flake: the
`SEEDING` state-changed notification fires *inside* the lock, before the
`COMPLETED` announce is sent outside it, so a test awaiting only the state
transition can race ahead and check the tracker's recorded requests before
the announce has actually happened. Fixed by having the test's fake
tracker client expose its own latch that counts down specifically on
receiving a `COMPLETED` request, so the test synchronizes on the actual
event it's asserting rather than an indirect proxy for it. The
`TorrentSession` behavior itself is correct as designed - it was purely a
test synchronization bug, and a reminder that "state changed" and "the
side effect that state change causes" aren't the same moment when the
side effect is deliberately moved outside a lock.

**Connection management is intentionally simple**, not because these
gaps aren't visible, but because they're bandwidth/politeness concerns,
not correctness ones, for Phase 1:
- `MAX_CONNECTIONS` (30) is a soft target — concurrent `fillConnections()`
  calls (from a re-announce and a disconnect happening close together)
  could transiently overshoot it slightly. Not worth adding reservation
  bookkeeping for.
- No per-peer-address retry backoff after a failed connection attempt —
  a failed address just gets retried on the next `fillConnections()` call.
  In practice this is naturally rate-limited by the tracker's re-announce
  interval (typically tens of minutes), so it doesn't become a retry
  storm.

**Requesting blocks without double-requesting from the same connection.**
`PieceManager` only tracks "received," not "requested" (by design, per
[[0016-piece-and-storage]]), which means `selectNextBlock` alone will keep
returning the *same* not-yet-received block on every call until it's
actually received. An earlier version of `requestMore` called it directly
in a loop up to `PIPELINE_DEPTH` times per peer event - for any piece with
fewer outstanding blocks than `PIPELINE_DEPTH` (trivially true for a
single-block piece, but true in general near the end of any piece), this
was a genuine infinite loop, not just an inefficiency: the loop condition
(`pendingRequestCount() < PIPELINE_DEPTH`) never changed because
`PeerConnection.sendRequest`'s `Set<Request>` silently no-ops on an
already-present entry, so the same block got "requested" endlessly on the
peer's own read-loop thread, which then never returned to read that peer's
actual replies. Fixed by `selectUnrequestedBlock`, which cross-checks each
candidate block against `connection.pendingRequestsSnapshot()` before
requesting it - avoiding a duplicate request to *this* connection, even
though duplicate requests *across different* connections are still
possible and accepted per 0016.

**Scheduling uses a plain single-threaded `ScheduledExecutorService`**
(one platform thread, `Executors.newSingleThreadScheduledExecutor()`), not
virtual threads — this is a different use case than
[[0007-concurrency-model]]'s peer-connection decision, which was about
handling potentially hundreds of concurrent blocking connections cheaply.
One dedicated thread per session for periodic re-announce/keep-alive
timing has negligible cost regardless of thread type; connection
*attempts* (`fillConnections`), which really do need many concurrent
blocking operations, still use `Thread.ofVirtual()` per attempt.

**Progress (`bytesDownloaded()`) is computed from verified-complete
pieces**, not from summing raw bytes received over the wire — deliberate:
it means "downloaded" always reflects good, hash-checked data, consistent
with what the number should mean for both UI progress and the tracker's
`downloaded` field, and avoids needing to reconcile per-connection byte
counters across a churning set of `PeerConnection`s.

**After reaching 100% completion**, the session loops over connections
sending updated interest state (typically `NotInterested`, since nothing
is needed anymore) — a small politeness addition beyond the minimum
needed for correctness, cheap enough to include.

## Alternatives considered

- **Hold the state lock through the completion tracker announce** —
  rejected; see locking note above.
- **Track in-flight requests globally to avoid asking two peers for the
  same block** — deferred, per [[0016-piece-and-storage]]'s existing note
  on `PieceManager`.
- **Virtual-thread-backed scheduler** for the periodic timers — rejected
  as unnecessary; this isn't the high-cardinality-blocking-work scenario
  [[0007-concurrency-model]] was written for.
