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

**Revised (2026-09-06, see [[0036-dht-backstop-for-tracker-bearing-torrents]]'s own
2026-09-06 revision): `fillConnections()` is no longer purely event-driven from external
triggers, and a failed address is no longer silently eligible for immediate re-attempt.**
Both halves of the "naturally rate-limited" assumption directly above turned out to be
false once DHT became a routine concurrent peer source (see that doc): a real side-by-side
comparison against qBittorrent found `discoverPeersViaDht()` correctly locating 275 DHT
peers for a real torrent, yet only 1 ever got connected - `fillConnections()` only ran from
four external triggers (`start()`, a successful tracker `reannounce()`, a
`discoverPeersViaDht()` tick, a PEX `addKnownPeers()` batch), never in response to an
individual `attemptConnect()` failure or a `PeerConnection` disconnecting, so the very first
burst of up to `MAX_CONNECTIONS` attempts (mostly failing within 5-20s, since most
tracker/DHT-supplied addresses are unreachable at any given moment - ordinary swarm churn,
confirmed via `attemptConnect()`'s own DEBUG logging) left the session under-connected until
the next external trigger, up to `dhtReannounceIntervalSeconds` (default 300s) away. Two
changes, needed together:
- `attemptConnect()`'s catch block and `PeerListener.onDisconnected()` both now call
  `fillConnections()` again, so a freed slot reaches a fresh candidate immediately rather
  than waiting for the next external batch.
- A new permanent-for-the-session `failedAddresses` set, excluded (alongside
  currently-connected addresses) from `fillConnections()`'s own candidate selection -
  necessary specifically *because* of the change above: without it, the new
  immediate-retry-on-failure trigger would hammer the same known-dead address in a tight
  loop instead of the old, accidentally-adequate "next reannounce" spacing.
  Deliberately never retried later this session (confirmed with the user, over a
  timestamp-per-address cooldown/expiry mechanism) - simpler, and DHT/tracker/PEX keep
  supplying fresh candidates continuously now anyway, so there's little to gain from ever
  retrying an address that's already failed once. Flagged as a real, deliberately deferred
  trade-off in `TODO.md`'s own matching entry - a genuinely transient failure (NAT timing, a
  peer briefly offline) won't ever be recovered from without a cooldown this doesn't have.

`fillConnections()`'s own soft-`MAX_CONNECTIONS` overshoot tolerance above now also covers
one more (rarer) case for the same underlying reason: several failures or disconnects
resolving around the same moment could each independently select an overlapping candidate
before any of them has registered as connected or failed yet, occasionally racing two
attempts at the same address - accepted for the same "not worth synchronizing against"
reasoning, not a new category of imprecision.

Stability ([[0051-stability-as-a-standing-consideration]]): `failedAddresses` can in
principle grow unboundedly over a very long-running session against a very large swarm,
same as `knownAddresses` itself already does (never pruned either) - accepted at this
project's real-world swarm-size scale (hundreds to low thousands of addresses), not a new
category of growth this introduces.

**Correction, same day: "no new concurrency pattern" above was wrong** - a real production
`OutOfMemoryError` surfaced within hours of this revision shipping. `fillConnections()`'s own
`slots = MAX_CONNECTIONS - connections.size()` computation only ever counted *established*
connections, never attempts still in flight - fine when `fillConnections()` only ran from a
handful of well-spaced external triggers, but this revision started calling it reactively
from every single `attemptConnect()` failure. A "connection refused" is near-instant (unlike
a 10-20s timeout), so a burst of candidates failing within milliseconds of each other could
each independently observe the same "N slots free" and each spawn up to N more attempts -
not a small, bounded overshoot like the pre-existing `MAX_CONNECTIONS` soft-limit tolerance
above, but an uncontrolled multiplicative cascade, made worse by `PREFERRED` encryption
mode's per-attempt Diffie-Hellman handshake (real CPU/memory cost, not free at an unbounded
concurrency level).

**Fixed the same day** with a `Semaphore connectionSlots` (`MAX_CONNECTIONS` permits),
replacing the size-based check entirely: one permit acquired atomically
(`tryAcquire()`) before an attempt (outbound, in `fillConnections()`'s own loop, or inbound,
in `acceptIncomingConnection()`) ever starts, held for as long as that attempt is in flight
or (on success) the resulting connection stays established, released on failure
(`attemptConnect()`'s catch block, `acceptIncomingConnection()`'s own catch block for a
failed `PeerConnection.accept()`) or disconnect (`PeerListener.onDisconnected()`). Atomicity
is what actually closes the gap - no matter how many threads call `fillConnections()`
concurrently, the *total* across all of them can never acquire more than `MAX_CONNECTIONS`
permits, so the cascade is structurally impossible rather than just statistically rarer.
Inbound connections (`acceptIncomingConnection()`) needed the same fix, not just outbound -
they previously used their own separate `connections.size() >= MAX_CONNECTIONS` check,
unaware of outbound attempts in flight, which would have let inbound and outbound
connections independently race past the real cap once outbound relied on the semaphore
instead.

**Second correction, same day: connectionSlots alone wasn't sufficient.** Deployed, and the
very next real run showed 0 connected peers despite a healthy tracker (336 seeders) and DHT
genuinely finding peers - the DEBUG logging added earlier showed the *same* single address
being attempted over a dozen times within a 24-millisecond window, then repeatedly for
several more seconds. `connectionSlots` correctly bounds the *total* number of concurrent
attempts, but does nothing to stop several concurrent `fillConnections()` calls (now firing
on every single failure, far more often than before) from each independently reading the
*same, not-yet-changed* `knownAddresses`/`failedAddresses` snapshot and each picking the
*same* leading untried candidate - wasting the connection budget hammering one or two
addresses instead of spreading across the real pool. Fixed with a second set,
`inFlightAddresses`, added to right in `fillConnections()`'s own candidate loop via
`Set.add()`'s own atomic "was this newly added" return value - the actual claim, since two
concurrent calls can never both win the add for the same address. Removed once the attempt
resolves either way (success: covered by the `connections`-based filter from then on;
failure: `failedAddresses`'s permanent exclusion takes over) - deliberately a separate set
from `failedAddresses`, since a peer connected once and later disconnected must remain
eligible for reconnection, which folding the two together would have broken.

`TorrentSessionTest`'s `neverExceedsMaxConnectionsEvenUnderABurstOfFailures` was renamed to
`neverDuplicatesOrExceedsMaxConnectionsEvenUnderABurstOfFailures` and strengthened to catch
both bugs at once: 60 candidates (double `MAX_CONNECTIONS`), each fake server looping accept
calls and counting them per port rather than accepting once, asserting both that peak
concurrent attempts never exceeds 30 *and* that every one of the 60 addresses is attempted
*exactly* once - never zero (every candidate eventually reached) and never more than once (no
duplicate/wasted attempts).

**Third correction, found much later (2026-09-10) by that same regression test failing
intermittently in real `mvn test` runs**: "removed once the attempt resolves either way"
above (the `inFlightAddresses` failure-path release) turned out to still permit a duplicate
attempt - narrower than the second correction's bug, but the same underlying shape. In
`attemptConnect()`'s catch block, `failedAddresses.add(address)` and
`inFlightAddresses.remove(address)` are two separate writes to two separate concurrent sets,
executed one after the other but with no atomicity *tying them together as observed by a
third thread*. A concurrent `fillConnections()` call evaluates its own candidate stream by
reading `failedAddresses` first, then `inFlightAddresses` (see that method's own filter order)
- if that read of `failedAddresses` happens to run before this thread's `add()` becomes
visible to it, but its later read of `inFlightAddresses` happens to run *after* this thread's
`remove()` has, the address looks "not failed, not in flight" to that concurrent call, which
then wins a fresh `inFlightAddresses.add()` claim and spawns a second, genuinely wasted
connection attempt to an address already known dead. Narrow window, but real: a burst of 60
candidates against fast-failing fake servers (all closing immediately, no handshake at all)
maximizes exactly the kind of tight-interleaving needed to hit it.

**Fixed by no longer releasing the `inFlightAddresses` claim on failure at all** - only on
success now (where the `connections`-based filter takes over instead, per the second
correction above). Since `failedAddresses` already excludes the address from every future
candidate snapshot permanently, there was never a correctness need to free its
`inFlightAddresses` slot too; doing so only ever existed to keep that set from growing, which
it now does anyway (a redundant entry alongside `failedAddresses` for every failed address,
not a new *category* of unbounded growth - see `failedAddresses`'s own already-accepted
growth note above). This closes the race structurally rather than narrowing its window
further: an address can never again be simultaneously "not yet visible as failed" and
"available for reclaim."

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
