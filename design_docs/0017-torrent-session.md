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

**Fourth correction (2026-10-03): dead connections were being registered and never removed.**
Found on a real container: a seeding torrent listed 80 connections against a cap of 30 after 13
hours, 75 of them having never sent a bitfield or moved a byte. `PeerConnection`'s factory
methods start the read loop and send the extended handshake *before* returning, so a peer that
closes straight after its handshake (common: it is at its own cap, or already connected to us)
fires `onDisconnected()` while the session hasn't added the connection yet - the `remove()` is
a no-op and the `connectionSlots` permit is released. `attemptConnect()` and both inbound
accept paths then added the already-dead connection to `connections`, where nothing would ever
remove it. The semaphore stayed correct (real sockets never exceeded the cap, which is why this
went unnoticed), but `connections` - and so `connectedPeers`, the Peers tab, and every loop
over connections - grew without bound for the life of the session, each entry pinning a
`PeerConnection` and its buffers.

**Fixed with `adopt()`**: add, then check `isClosed()`, and remove again if it is. Add-then-check
rather than check-then-add because `closed` is set before `onDisconnected()` runs, so whichever
side loses the race still removes the entry; the permit is still only ever released by
`onDisconnected()`, never here. On the outbound path such an address also goes into
`failedAddresses` (with its `inFlightAddresses` claim kept, per the third correction), which
keeps the behaviour the bug gave by accident - a peer that drops us at the handshake is not
re-dialled on every refill. Considered and not done: starting the read loop only after the
session has registered the connection, which removes the window instead of tolerating it but
changes `PeerConnection`'s construction contract for every caller. No regression test - the
window is between two statements on one thread and has no seam to hold it open.

**Revision (2026-10-05): outbound attempts no longer hold connection slots.** With the listen
port forwarded, a real container received about 1,500 inbound connections in a ten-minute log
and kept 3. Of the 315 that reached a torrent, 286 were refused as "at the connection limit of
30" while those torrents had 1-11 peers connected. Since the first correction above, an outbound
attempt took its `connectionSlots` permit *before* dialling and every failure immediately
started a replacement, so with a large candidate pool (most of it dead addresses) nearly every
permit was permanently held by a dial in progress - and inbound peers, the ones known to be
alive and reachable, were turned away.

`connectionSlots` now bounds **established** connections only, inbound and outbound together; a
permit is taken once a handshake has completed. Attempts get their own bound,
`outboundAttemptSlots` (`min(maxConnections, 16)`), acquired atomically in `fillConnections()`
before the attempt's thread starts - the same structural guarantee the first correction needed
against an attempt cascade, just no longer spent from the budget inbound peers use.
`fillConnections()` starts attempts only for the room left (free slots minus attempts already in
flight); that subtraction isn't atomic, so an attempt can still succeed into a session that
filled meanwhile, in which case the new connection is closed and its address stays eligible. A
`slotHolders` set records which connections hold a permit, so it is released exactly once
whichever of `adopt()` and `onDisconnected()` reaches a dead connection first, and never for one
closed before it was given a permit. `adopt()` also closes a connection that finishes its
handshake after the session has stopped, which previously stayed open on a paused torrent.

Stability: worst case per session is `maxConnections` established plus 16 attempts (sockets and
virtual threads), up from a flat `maxConnections` - bounded, and attempts are short-lived. Cost:
a session connects out at most 16 at a time instead of 30, so the first fill of a fresh torrent
is somewhat slower. Alternatives considered: reserving a fixed share of slots for inbound
(still refuses inbound once the outbound share is all dials); keeping one semaphore and evicting
a pending dial when an inbound peer arrives (needs cancellable attempts, which `connect()` is
not).

**Also 2026-10-05: connections to ourselves are dropped.** Our own public address comes back
from trackers, DHT and PEX like anyone else's, and the session dialled it - seen in the same log
as inbound connections from the router's address, one of which was kept (two slots on one
torrent, talking to itself). Both ends now compare the remote peer id with our own: the inbound
side refuses before taking a slot, the outbound side closes and puts the address in
`failedAddresses`. The peer id is per process, so this can't match another install.

**Revision (2026-10-06): `NUM_WANT` raised from 50 to 200.** Two new, well-seeded torrents
(about 2,300 seeders each) sat at 1 and 9 connected peers for many minutes. A direct probe of
one swarm from the same network settled why: of 691 addresses four trackers returned, 12
completed a plain TCP handshake, 636 timed out and 38 refused - under 2% reachable, nearly
everyone being behind NAT. At 50 per tracker the session had 100-200 unique candidates, so two
to four usable peers, and since `failedAddresses` is permanent it then ran dry. The same
trackers return 200 when asked (libtorrent's default), which this now requests. Not a setting:
there is no reason for a user to lower it. Resource behaviour: `knownAddresses` and
`failedAddresses` were already unbounded for the session's lifetime (DHT and PEX feed them
without a cap); this grows them about four times faster from trackers, at a few dozen bytes
per address - up to roughly 2,000 new entries per announce cycle with ten trackers, in
practice far fewer since trackers overlap heavily. Simultaneous dials are unchanged, still
bounded by `outboundAttemptSlots` (16). A 200-peer UDP response is 1,220 bytes, inside
`UdpTrackerClient`'s 2,048-byte receive buffer; a hostile tracker can't make it larger than
that buffer.

**Revision (2026-10-06): faster dialling, and failed addresses retried after a backoff.** The
cause of that day's poor downloads turned out to be the host's VPN: with no forwarded port, no
incoming connection could arrive, and in a swarm where under 2% of addresses accept an outbound
connection, incoming is where most peers come from. Many users run a torrent client behind a
VPN that offers no port forwarding, so the outbound-only case has to work acceptably by itself.
Two things made it slow.

*Dial rate.* A dead address holds its attempt for up to 12 s (2 s of µTP, then 10 s of TCP), and
attempts were capped at the smaller of 16 and the free connection slots - about 80 addresses a
minute, so one or two new peers a minute. Now:

- `MAX_PENDING_OUTBOUND` is 64 while downloading (about 320 addresses a minute) and 16 while
  seeding, where no download is waiting on more peers and interested peers mostly dial in.
- `fillConnections()` starts `ATTEMPTS_PER_FREE_SLOT` (4) attempts per free slot instead of one,
  up to that bound, because most fail. An attempt that succeeds after the session has filled is
  closed, as before; in a swarm where most addresses do answer that means a few
  connect-then-close exchanges whenever a slot frees up. Accepted.
- `fillConnections()` also runs every 60 s on the session scheduler, so the retries below
  happen even when no tracker, DHT or PEX batch arrives to trigger it.

The TCP connect timeout (10 s) is unchanged: slots were the limit, not time, and a shorter
timeout would start missing slow but reachable peers.

*Retry.* `failedAddresses` was a permanent set (the 2026-09-06 design, kept on 2026-09-21), so a
session that had tried every candidate once sat at zero peers until something new arrived -
seen on a torrent with 2,300 seeders. It is now a `FailedAddresses` map: a failed address is
excluded for 5 minutes, doubling with each further failure up to 2 hours, and the count is
cleared when the address connects. Addresses never tried always go first; ones whose backoff
has ended fill only the room left over, longest-waiting first. Our own address stays excluded
for good.

This reopens the duplicate-attempt race the third correction above closed by keeping the
`inFlightAddresses` claim forever on failure - a retry needs the claim released. It is closed a
different way: an attempt records its outcome (the failure, or the established connection)
*before* releasing its claim, and `fillConnections()` re-checks both *after* winning a claim.
A concurrent call working from a stale candidate list can win the claim but then sees the
recorded outcome and skips. That also closes the same race on the success path, which the old
scheme did not cover.

Resource behaviour:

- **Threads and sockets:** at most `maxConnections` established plus 64 attempts per
  downloading session (16 per seeding one), each attempt one virtual thread and one socket,
  still bounded atomically by `outboundAttemptSlots` however many threads race to refill. There
  is no engine-wide bound on attempts: 20 torrents downloading at once can have 1,280 dials in
  flight. That was already true at 16 each (320); an engine-wide limiter, like the one magnet
  fetches have, is the fix if a file-descriptor limit is ever hit.
- **Memory:** `FailedAddresses` holds one small entry per address ever failed and is never
  pruned, like `knownAddresses`.
- **Hostile angle:** a tracker or PEX peer feeding in unreachable addresses costs one attempt
  each, then at most about 16 retries a day per address at the 2-hour cap, and only when no
  untried candidate is waiting.
- **Locking:** none added; `FailedAddresses` is a `ConcurrentHashMap`.

Tests: `FailedAddressesTest` covers the backoff, cap, clearing and ordering with explicit
timestamps. `TorrentSession` has no injectable clock, so the retry itself is not exercised
through a real session (the first backoff is five minutes); the existing burst test still
asserts every candidate is attempted exactly once, now against the bound of 64.

Considered and left out: µTP and TCP attempted in parallel instead of in sequence (saves 2 s
per dead address, costs two sockets each); re-announcing early when peers are scarce; BEP 55
hole punching, the real answer for two firewalled peers, which is a much larger piece of work.

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
