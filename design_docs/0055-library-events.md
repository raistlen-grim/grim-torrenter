# 0055 — Library events

**Status:** Accepted - built for a first event set (ADDED/COMPLETED/ERROR/REMOVED/
SEEDING_LIMIT_REACHED); see "Deferred from this pass" below for what's intentionally not
wired up yet. `SERVER_STARTED` added 2026-08-26 - see its own section below.
`TRACKER_UNREACHABLE`/`TRACKER_RECOVERED` added 2026-09-06 - see their own addendum below.
`MAGNET_RESOLVED` (also 2026-09-06) resolved into reusing `ADDED` with a source-driven message
rather than a new `EventType` - see its own addendum below; both originally-deferred items are
now closed.

## Decision

Picked from `TODO.md`'s "Events viewer" item. Explicitly **not** a raw debug/activity log —
the user's framing is a feed of things a person would use to *manage their library*: torrent
added, completed, errored, removed, auto-paused by a seeding limit, tracker gone
unreachable/recovered, magnet metadata resolved. Per-piece completions, per-peer
connect/disconnect churn, and other high-frequency internal chatter are deliberately out of
scope - that's what a real log file (or a future debug-log viewer, if ever wanted) is for, not
this.

### What counts as an event

A new `LibraryEvent` record (`grimtorrenter-engine`, new `events` package):
`(Instant timestamp, EventType type, String infoHash, String torrentName, String message)`.
`infoHash`/`torrentName` are nullable - most events are torrent-scoped, but `SERVER_STARTED`
(added 2026-08-26, see its own section below) is a genuinely engine-wide event that isn't.
`EventType` is a closed enum (`ADDED`, `COMPLETED`, `ERROR`, `REMOVED`,
`SEEDING_LIMIT_REACHED`, `SERVER_STARTED` - see "Deferred from this pass" below for two more
originally scoped in but not yet built) rather than a free-form string tag, so the frontend can
render a fixed icon/label set instead of guessing at arbitrary text - matching `TorrentState`'s
own closed-enum precedent rather than `Settings`' more open shape.

### The reason gap this closes

`TorrentSessionListener.onStateChanged(session, oldState, newState)` today carries no *why* - a
seeding-limit auto-pause ([[0054-seeding-limits]]) and a manual user pause both produce an
identical `SEEDING` -> `STOPPED` transition. A library event log that can't tell those apart
isn't useful for its stated purpose (a user reviewing what *happened to* their library, not
just what state things ended up in).

Closed **without** changing `TorrentSessionListener`'s signature (an interface with only one
production implementer, but exercised by test doubles throughout the engine test suite - not a
change to make for a "nice to have"): `TorrentEngine.checkSeedingLimits()` already computes the
real reason (ratio vs. time) to decide whether to act at all, and already calls
`pauseTorrent()` itself rather than going through any generic path - so it records the
`SEEDING_LIMIT_REACHED` event with that reason directly, *before* calling `pauseTorrent()`, and
an ordinary manual `pauseTorrent()` call (from the REST layer) simply never records anything.
The generic `onStateChanged` callback still fires identically for both cases (the row's live
status still updates either way) - it's just never the place a *library* event gets recorded
for this particular transition.

For the two outcomes that genuinely are just a state transition with no separate decision point
to hook into - reaching `ERROR`, and completing a download - `TorrentEventListener`
(`grimtorrenter-app`, the one place that already receives every `onStateChanged` callback to
bridge it to the WebSocket) maps `oldState`/`newState` to an `EventType` and records it.

**Correction (2026-08-26), a real bug found in production**: the first cut assumed
`DOWNLOADING` -> `SEEDING` alone was enough to mean "just completed," on the theory that
restoring an already-complete torrent re-enters `SEEDING` straight from `VERIFYING` without
passing through `DOWNLOADING` at all. That assumption was wrong - `enterDownloading()`
unconditionally calls `checkForCompletion()` on **every** `start()`, restore included, so an
already-complete torrent genuinely does transition `DOWNLOADING` -> `SEEDING` again on every
restart. In production this showed up exactly as that implies: the same long-finished torrent
recorded a fresh `COMPLETED` event on every server restart, forever.

Fixed with a new `TorrentSession.wasCompleteOnRestore()` flag (set once, during
`verifyThenSettle()`'s restore-only re-verification pass, to whatever
`pieceManager.isAllComplete()` found *before* this session's first `start()` ever ran) - `false`
for a `create()`d session (never pre-populated) and for a restored session that genuinely had
data missing, `true` for a restored session whose data was already fully present and valid.
`TorrentEventListener` now records `COMPLETED` only when
`oldState == DOWNLOADING && newState == SEEDING && completedAtEpochMillis() == 0 &&
!wasCompleteOnRestore()` - the existing `completedAtEpochMillis() == 0` guard alone (already
used to gate seed-time-limit re-stamping, [[0054-seeding-limits]]) catches a same-process
pause/resume of a torrent this process has already seen complete once, but can't catch the
cross-restart case, since a brand-new `TorrentSession` object always starts with
`completedAtEpochMillis` back at 0 - `wasCompleteOnRestore()` is what's actually needed for
that. No reason string needed for either event - the transition shape plus these two flags is
unambiguous. Any other transition (including ordinary pause/resume) maps to nothing and is
recorded as nothing.

### `SERVER_STARTED` (added 2026-08-26): the first engine-wide event

User request, prompted by the `COMPLETED`-duplication bug above and how it was actually noticed
- a timeline of events is more useful for diagnosing surprises like that one if process restarts
are visible in the same feed, especially for a deployment fronted by an auto-updater like
Watchtower that recreates the container unattended. `LibraryEvent`'s `infoHash`/`torrentName`
being nullable had already anticipated exactly this - `SERVER_STARTED` is the first type that
actually uses that, with both null.

Recorded once, at the end of `TorrentEngine`'s canonical constructor - exactly one
`TorrentEngine` exists per running process in production
(`TorrentEngineProducer`'s `@ApplicationScoped` bean, constructed once), so construction time is
equivalent to "the app started." No new lifecycle hook needed - the constructor already runs
exactly when it needs to.

### Storage: rolling daily files, not one ever-growing file

Explicit user requirement: bounded, configurable retention - "rolling files from the Java
logging frameworks that roll over at the start of the day, or longer," not an unbounded
append-forever log and not a fixed event *count* cap. `EventStore` (interface,
`grimtorrenter-engine`, mirroring [[0041-live-settings-store]]'s split) exposes
`record(LibraryEvent)`, `all()`, and `forTorrent(String infoHash)`; the concrete
`JsonLinesEventStore` (`grimtorrenter-app`, `@ApplicationScoped`) writes one JSON object per
line (append-only - no read-modify-rewrite-whole-file cost on every event, unlike
`SettingsStore`'s swap-the-whole-value approach, which doesn't fit an append-heavy log) to
`{grimtorrenter.config-directory}/events/events-YYYY-MM-DD.jsonl`, one file per calendar day in
the JVM's default time zone. `grimtorrenter-engine` gains no new dependency - same reasoning as
every other engine-side interface with a JSON-backed app-side implementation.

A new `eventLogRetentionDays` field on `Settings` ([[0041-live-settings-store]]), default 30,
**live** as soon as the settings write completes (no restart, matching every other Settings
field except the two documented exceptions). Deliberately **no "0 or negative = unlimited"
sentinel** despite that being this codebase's established idiom
([[0054-seeding-limits]], `RateLimitSchedule`) - an event log is exactly the kind of thing that
grows without bound if "unlimited" is ever selected, and per
[[0051-stability-as-a-standing-consideration]] this feature shouldn't offer a configuration
that silently defeats its own bounded-growth guarantee. Enforced by `Settings`' own compact
constructor, which silently normalizes 0/negative to the default of 30 - the same mechanism
(and the same call site) that backfills a missing pre-0055 field, since a primitive `int` can't
tell those two cases apart. Unlike `rateLimitScheduleStart`/`End`'s malformed-string case
([[0045-settings-page]]), there is **no** corresponding `SettingsResource`-level rejection for
this field: by the time that layer sees a deserialized `Settings`, the compact constructor has
already run and there is no invalid value left to reject - a validation check there would be
unreachable dead code, not a real second line of defense.

A new hourly `@Scheduled` task (daemon-safe the same way [[0054-seeding-limits]]'s periodic
check is - it runs unconditionally per `TorrentEngine`/app instance, so it must not leak threads
in the many throwaway engines the test suite constructs) lists `events/`, parses each filename's
date, and deletes any file older than `today - eventLogRetentionDays`. Also run once at
application startup, so a period the app was down for doesn't leave stale files sitting past
their window until the next event happens to be recorded. Shrinking the retention window takes
effect on the next hourly prune, not instantly - consistent with how a shrunk rate limit
([[0042-rate-limiting]]) takes effect on the next check, not synchronously mid-transfer.

### Delivery: WebSocket for live push, REST for scrollback

`TorrentEventMessage`'s existing generic `(type, payload)` envelope gets a new `"event"` type,
broadcast over the same `/ws/torrents` connection ([[0019]] hybrid model) the instant
`EventStore.record()` is called - no new WebSocket endpoint needed. A new
`GET /api/events` (optional `?infoHash=` filter, for a future "this torrent's history" link from
the detail drawer) returns everything currently retained, newest first - response size is
naturally bounded by the same retention window that bounds disk usage, so no separate
pagination scheme is needed for a first cut.

### Frontend: a new top-level page, not a drawer tab

A new **Events** sidebar entry (alongside the existing status-filter nav,
[[0043-app-shell-and-filtering]]) rather than a torrent-detail-drawer tab - most of these events
are exactly the "something happened while you weren't looking" kind a per-torrent drawer would
hide until you happened to open that specific torrent. Reverse-chronological list, one row per
event (timestamp, a type-specific icon/label, the message, and - when `infoHash` is present - a
link that opens that torrent's detail drawer), loaded via `GET /api/events` on entry and
appended live from the WebSocket `"event"` messages thereafter, matching how the torrent list
itself is seeded then kept live.

## Testing

- `JsonLinesEventStoreTest` (new, plain JUnit, no container, mirrors `JsonSettingsStoreTest`'s
  own directly-constructed-instance approach) - `record()` then `all()`/`forTorrent()`
  round-trip; a `record()` writes into today's `events-<today>.jsonl`; `prune()` deletes a
  day-file older than the configured retention window and keeps one within it; a missing
  `events/` directory is created on `init()`.
- `TorrentEngineTest` (new cases) - `checkSeedingLimits()`'s auto-pause records a
  `SEEDING_LIMIT_REACHED` event whose message names the reason (ratio vs. time); an ordinary
  manual `pauseTorrent()` records nothing; `addTorrent()` records `ADDED` exactly once even
  across an idempotent re-add of the same info hash; `removeTorrent()` records `REMOVED`.
- `EventsResourceTest` (new, `@QuarkusTest`) - uploading a torrent via the real REST layer
  produces an `ADDED` event visible through `GET /api/events?infoHash=...`; removing it adds a
  `REMOVED` one. Filtered by the test's own `infoHash` throughout, never asserting on the
  unfiltered list, since `JsonLinesEventStore` is one `@QuarkusTest`-shared singleton other
  test classes in the same run also write into.
- **Gap, partially closed (2026-08-26)**: the `COMPLETED` half of this gap now has dedicated
  coverage - `TorrentEventListenerTest` (new, plain JUnit, `grimtorrenter-app`) proves a
  genuinely fresh `create()`d session records `COMPLETED`, and a `restoreAsync()`d
  already-complete session does not (the regression test for the duplicate-event bug above);
  `TorrentSessionTest` (new cases, `grimtorrenter-engine`) proves `wasCompleteOnRestore()`
  itself is set correctly across all three shapes (restored-and-complete, restored-and-
  incomplete, freshly created). The `ERROR` half still has no dedicated test - it would need a
  real `TorrentSession` driven into `ERROR` (no cheap, deterministic way to do that at the
  engine-test level the way seeding limits' degenerate ratio/time-of-zero trick allows) or
  extracting that mapping into an independently testable pure function. Left as a follow-up if
  this logic needs to change again.
- `SettingsResourceTest` (new case) - a `PUT` with `eventLogRetentionDays: 0` comes back `200`
  with the field normalized to `30`, confirming the compact constructor's normalization (not a
  `SettingsResource`-level rejection - see above) is reachable through the real REST layer, not
  just `Settings`' own constructor.
- `TorrentEngineTest` (new case, 2026-08-26) - constructing a `TorrentEngine` records exactly
  one `SERVER_STARTED` event with `infoHash`/`torrentName` both null.

## Deferred from this pass

Both event types from the original scoping that weren't wired up when this was built
(2026-08-26) are now closed - see their own addenda below (`TRACKER_UNREACHABLE`/
`TRACKER_RECOVERED`, and `MAGNET_RESOLVED`).

## `TRACKER_UNREACHABLE`/`TRACKER_RECOVERED` (added 2026-09-06)

Picked from `TODO.md`/`PROGRESS.md`'s "remaining known gaps" list - the other deferred item
from the original scoping above. The real decision this needed, per the original deferral note,
was designing a callback seam onto `TrackedTrackerClient`/`TrackerStatus`
(`grimtorrenter-engine`, `tracker` package) where none existed before (only a poll-on-demand
REST read, [[0031-torrent-detail-endpoints]]), and picking a debounce policy so a single missed
announce doesn't generate an event storm.

### Where the seam lives: `TrackedTrackerClient`, not `MultiTrackerClient`

`TrackedTrackerClient` (wraps one tracker; `recordSuccess()`/`recordFailure()` already run on
every `announce()`) is the one place with a genuine before/after view of a *single* tracker's
own status. `MultiTrackerClient` only aggregates already-decided `TrackerStatus` values from its
wrapped clients - it has no independent state to add here, so putting the seam there would just
mean forwarding. Per-tracker granularity also matches how `TrackerStatus`/the Trackers detail
tab are already per-URL, not per-torrent-aggregate.

A new engine-only `TrackerStatusListener` interface (`tracker` package:
`onTrackerUnreachable(String url, String lastError)` / `onTrackerRecovered(String url)`)
deliberately doesn't reference `EventStore`/`LibraryEvent` at all, keeping
`grimtorrenter-engine` free of any dependency on those `grimtorrenter-app`-adjacent concepts -
same reasoning as every other engine/app split in this codebase. `TrackedTrackerClient` gained a
new 4-arg constructor taking a listener (the existing 3-arg constructor delegates to a private
`NoOpTrackerStatusListener` enum singleton, the same "widen constructor, old one defaults"
precedent used for `configDirectory`/`watchDirectory` elsewhere).

### Debounce policy (confirmed with the user)

Each `announce()` call already survives its own transport-level retries
(`UdpTrackerClient`/`HttpTrackerClient`) before ever throwing `TrackerException` - but reannounce
cycles happen every interval (often many minutes), and an ordinary tracker restart/brief
overload can plausibly fail one cycle and recover the next. Firing on the very first failed
cycle risked being noisy for that ordinary case, so the policy is asymmetric:

- **`TRACKER_UNREACHABLE`** fires only after **2 consecutive** failed announce cycles with no
  intervening success. A `consecutiveFailures` counter and a `reportedUnreachable` boolean on
  `TrackedTrackerClient` track this; a third, fourth, ... consecutive failure doesn't re-fire -
  already reported, stays reported until a success resets it.
- **`TRACKER_RECOVERED`** fires on the **very next success** once `TRACKER_UNREACHABLE` was
  actually reported - not debounced the same way, on purpose: recovery is unambiguous good news,
  with no real cost to reporting it immediately. If unreachability was never reported (still on
  failure #1 when a success arrives), nothing fires - there's nothing to "recover" from in the
  user's eyes.

### Wiring: `TorrentEngine.createTrackerClient(TorrentMetadata)`, not the magnet-fetch overload

`createTrackerClient(TorrentMetadata)` (used by `addTorrent()` and `restoreOne()` - a torrent's
real, persistent tracker client) stopped being `static` and now builds a listener via a new
`trackerStatusListenerFor(metadata)` (package-private for direct testing, same rationale as
`selectTrackerTiers`/`sanitizeDirectoryName`) that adapts each callback into a
`eventStore.record(new LibraryEvent(...))` call with `infoHash`/`torrentName` from the metadata
and the tracker's own URL (plus `lastError` for `TRACKER_UNREACHABLE`) folded into the message -
same pattern already used for `DHT_UNAVAILABLE`/`PEER_SERVER_UNAVAILABLE` at construction time
([[0059-service-status]]).

The **listener-less** `createTrackerClient(List<List<String>>)` overload - used by
`fetchMagnetMetadataViaTrackerThenAdd()` to probe candidate trackers while resolving a magnet's
metadata - deliberately keeps no listener. That tracker client is short-lived and races/retries
candidates within a time budget ([[0028-magnet-links-and-dht]]'s own 2026-08-30 addendum); its
info hash isn't a tracked torrent yet, so reporting library events off its retry churn would be
noise, not signal.

### Testing

- `TrackedTrackerClientTest` (new cases) - the debounce policy itself: a single failure reports
  nothing; two consecutive failures report `onTrackerUnreachable` exactly once (a third doesn't
  re-fire); a success after unreachability was reported fires `onTrackerRecovered`; a success
  after only one failure (never reported unreachable) fires nothing.
- `TorrentEngineTest` (new case) - `trackerStatusListenerFor()` is the adapter under test here,
  not the debounce policy (already covered above): invoking the listener directly records a
  `TRACKER_UNREACHABLE`/`TRACKER_RECOVERED` `LibraryEvent` with the right `torrentName` and the
  tracker URL folded into the message.

### Stability ([[0051-stability-as-a-standing-consideration]])

- **Hostile/flaky-tracker angle**: this is exactly the risk the debounce policy above exists to
  bound - a tracker flapping between reachable/unreachable on a short cycle could otherwise
  generate an event on every single reannounce; the 2-consecutive-failures/1-success asymmetry
  means the worst case is still one event pair per genuine down-then-up transition, not per
  announce attempt.
- **No new unbounded growth**: these two types are subject to the exact same
  `eventLogRetentionDays`-bounded rolling-file storage as every other library event - no new
  storage mechanism introduced.
- **Concurrency**: `TrackedTrackerClient.recordSuccess()`/`recordFailure()` are already called
  from whatever single thread drives that session's `announce()`/`reannounce()` calls (no new
  concurrent access introduced); the listener callback runs synchronously on that same thread,
  same as the rest of `record()`'s call chain.

## `MAGNET_RESOLVED` (added 2026-09-06)

Picked up alongside the tracker-events addendum above, closing the other half of the pair this
doc originally deferred. Resolved as **reusing the existing `ADDED` event with a source-driven
message**, not a new `EventType` - the same mechanism [[0056-watch-folder]] already built for
`WATCH_FOLDER_SOURCE` (`addTorrent(byte[], String source)`), just given a second source value.
A distinct `MAGNET_RESOLVED` type was the shape implied by this item's original name, but adding
one would mean firing *two* events for the same add (`ADDED` and `MAGNET_RESOLVED` both, since
`addTorrent()`'s own idempotent-`ADDED`-recording logic doesn't distinguish the reason it was
called) - redundant, and inconsistent with the choice [[0056-watch-folder]] already made for the
exact same shape of problem (a watch-folder-triggered add is `ADDED` with `message: "Added via
watch folder"`, not a second, distinct event type).

`TorrentEngine.addFetchedTorrent(MagnetLink, byte[], List<String>)` - the single method both the
tracker-based and DHT-based magnet metadata-fetch paths already funnel through once a peer's
metadata is verified - now calls `addTorrent(bytes, MAGNET_SOURCE)` (`MAGNET_SOURCE = "magnet"`)
instead of the source-less public overload, producing `message: "Added via magnet"`. Made
package-private (was `private`) for direct testing, same test-visibility rationale as
`trackerStatusListenerFor()` above - a real end-to-end test would need a full fake-peer
metadata-fetch harness for no real additional coverage, since the only new logic here is which
`source` string gets passed through an already-tested mechanism.

No stability implication - this only changes which string flows into an existing, already-bounded
event-log write path; no new storage, concurrency, or growth behavior.

### Testing

- `TorrentEngineTest` (new case) - `addFetchedTorrent()` called directly with a synthesized info
  dict records an `ADDED` event with `message: "Added via magnet"` for the resulting torrent's
  real info hash (derived from the info dict bytes themselves, matching how `MetainfoParser`
  computes it).

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Unbounded growth**: the reason this design exists. Bounded on two axes - daily rotation
  caps how much a single burst of activity (e.g. a hostile/flaky swarm producing rapid
  error/reconnect churn on one torrent in one day) can write to a single file before the day
  rolls over, and the retention window caps total on-disk size regardless of how many days pass.
  No "unlimited" escape hatch is offered (see above) specifically so this guarantee can't be
  turned off by mistake.
- **Hostile-peer/tracker angle**: a malicious or flaky peer/tracker driving a torrent repeatedly
  into `ERROR` can drive up event volume within a day, but can't defeat the time-based bound
  across days the way it could defeat a naive count-based cap combined with a slow drip designed
  to evict older, more important entries. (Tracker-flapping itself doesn't yet produce events at
  all - see "Deferred from this pass".)
- **Locking/concurrency vs. [[0007-concurrency-model]]**: `record()` is a `synchronized`
  file-append, same shape as `SettingsStore.update()`'s existing `synchronized` write. Not a hot
  path - events fire on state transitions and engine decisions, not per-packet or per-piece -
  so this doesn't reintroduce the `synchronized`-in-the-hot-path concern
  [[0050-piece-manager-reentrant-lock]] resolved elsewhere.
- **File descriptors**: each append opens, writes, and closes the day's file rather than
  holding a handle open ([[0047-bounded-file-handle-pool]]'s pool is for torrent data files
  under sustained read/write load; this is low-frequency enough that a pooled handle would add
  complexity for no real benefit). No handle to leak on any exit path, including a crash mid-write
  (a torn last line is possible but self-contained to that one line, not corrupting the file).

## Alternatives considered

- **One single, ever-growing JSON array file** (`SettingsStore`'s own shape) - rejected; every
  append would mean reading, deserializing, appending, and rewriting the entire file, which gets
  worse the longer the log lives - the opposite of what an append-heavy, long-lived log needs.
  Also has no natural pruning point without extra bookkeeping.
- **A fixed event-count cap (e.g. "keep the last 5,000")** instead of a time window - rejected;
  explicit user requirement was a rolling-file/time-window scheme like a logging framework's
  daily rollover, not a count cap, and a count cap doesn't answer "how far back can I see" in a
  way a user can reason about the way "30 days" does.
- **Adding a `reason` parameter to `TorrentSessionListener.onStateChanged`** so every recording
  point could go through one place - rejected; changing a production interface (even one with a
  single real implementer) to carry a value only one call site (the seeding-limit check) ever
  has isn't worth it when that call site already has its own separate, more specific hook
  (`checkSeedingLimits()` calling `pauseTorrent()` directly) it can record from instead. The
  interface stays exactly as it was before this feature.
- **Recording every `onStateChanged` transition unconditionally** and filtering at read time -
  rejected; the whole point raised in scoping this feature was that a plain state delta can't
  distinguish *why* a transition happened (manual pause vs. auto-pause), and that information
  doesn't exist anywhere to filter on after the fact if it isn't captured at the moment the
  decision is actually made.
- **An embedded database (SQLite) for the event log** - rejected; introduces a new dependency
  for a problem plain rolling files already solve, and `grimtorrenter-engine`'s
  zero-production-dependency stance is a standing constraint elsewhere in this codebase
  (marker files instead of JSON, [[0054-seeding-limits]]) that a new event-only DB would break
  precedent with for no corresponding benefit.
