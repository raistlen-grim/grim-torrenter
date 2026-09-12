# 0064 — Persistent lifetime torrent stats

**Status:** Accepted

## Decision

Closes a gap [[0054-seeding-limits]] explicitly called out and deliberately deferred at the
time ("Neither ratio nor seed time survives a process restart... a deliberate, documented
limitation, not a silent one"): three per-torrent metrics now survive an engine restart -
**lifetime uploaded bytes**, **cumulative active time**, and **completed-on timestamp** -
picked from the qBittorrent-parity backlog (`TODO.md`, 2026-09-10) as the highest-value item
for a long-running, power-user-facing server. Lifetime *downloaded* bytes needs no new work:
`TorrentSession.bytesDownloaded()` (verified-complete pieces) is already effectively lifetime,
since a completed piece stays on disk - and therefore counted - across restarts on its own.

Two user-confirmed decisions shape this:
- **"Active" means DOWNLOADING, VERIFYING, or SEEDING** - anything except STOPPED/ERROR counts,
  matching "the torrent is doing something" rather than narrowly "transferring bytes right now."
- **Lifetime stats carry forward across a "remove but keep files" + later re-add**, consistent
  with `SeedingLimitOverride`'s existing precedent ([[0054-seeding-limits]]) of treating a
  torrent-directory-scoped fact as belonging to the data, not the in-memory session.

## New `TorrentSession` state

### `lifetimeUploadedBytes()` - a new method, not an overload of `bytesUploaded()`

`bytesUploaded()` already means something specific and load-bearing elsewhere: BEP 3's
`uploaded` announce field is defined as "since this client's `started` event to the tracker"
(a session-scoped protocol convention), and [[0054-seeding-limits]]'s ratio check reads it too.
Making `bytesUploaded()` itself lifetime-cumulative would silently start sending inflated
`uploaded` values to trackers and silently change the seeding-ratio-limit's meaning from
per-session to per-lifetime - a real behavior change, not something this feature should cause
as a side effect. Same reasoning [[0031-torrent-detail-endpoints]] already used to justify a
second `bytesReceived()` method alongside `bytesDownloaded()` rather than repurposing one field
for two meanings.

Instead: a new `long lifetimeUploadedBytesBaseline` field, set once at construction from
persisted state (0 for a brand-new torrent) and never itself mutated afterward.
`lifetimeUploadedBytes()` returns `lifetimeUploadedBytesBaseline + bytesUploaded()` - correct
for the life of the process without needing its own live accumulator, since `bytesUploaded()`
already does that job for "this session's" contribution.

### `timeActiveMillis()` - a genuinely new accumulator

No existing method tracks session-elapsed-in-an-active-state, so unlike the upload case this
needs its own live state: a new `AtomicLong activeMillisAccumulated` (seeded from persisted
state at construction) plus a `volatile Instant activeSince` (null when not currently active).
`setState()` is the single choke point every state transition already goes through, so it's
also where this hooks in:
- Transitioning **into** DOWNLOADING/VERIFYING/SEEDING from a non-active state sets
  `activeSince = Instant.now()` (a transition between two active states, e.g.
  DOWNLOADING → SEEDING on completion, leaves it untouched - the clock keeps running).
- Transitioning **out** (into STOPPED/ERROR) folds the elapsed time into
  `activeMillisAccumulated` and clears `activeSince` back to null.

`timeActiveMillis()` returns `activeMillisAccumulated.get() + (activeSince == null ? 0 :
Duration.between(activeSince, Instant.now()).toMillis())`.

### `completedAtEpochMillis` persistence, and a real restore-time bug it fixes for free

`checkForCompletion()`'s existing guard (`if (completedAtEpochMillis == 0)`,
[[0054-seeding-limits]]) only prevented re-stamping *within one process run* - a routine
pause/resume cycle. Across a restart it did nothing: a restored, already-complete torrent's
`completedAtEpochMillis` starts at a fresh in-memory `0`, so its first post-restart
`checkForCompletion()` call (already called unconditionally from `enterDownloading()` on every
`start()`, per that method's own comment) re-stamps it to "now." Undetected until now because
nothing exposed this field anywhere - `wasCompleteOnRestore` (also from
[[0054-seeding-limits]], used for a different purpose there) was never wired to guard it.

Fixed as a direct consequence of persisting the value, not a separate patch: the constructor
now takes the persisted `completedAtEpochMillis` (0 if the torrent has never completed) instead
of always starting at 0. A restored already-complete torrent now starts with the real
non-zero value already in place, so the existing guard holds across restarts too, with no new
special-casing needed.

## Persistence mechanics

**Note:** written before [[0065-config-side-per-torrent-storage]] existed, which relocates
every per-torrent marker (this one included) from the torrent's download directory into
`configDirectory/torrents/<infoHash>/`. Everything below about the marker's *shape* and
*lifecycle* is unaffected - only which directory it lives in changed; implement this marker
directly in its 0065 location rather than the download-directory location described below.

One new marker file per torrent, `.grimtorrenter-lifetime-stats`, same directory and same
plain `key=value`-lines shape as `.grimtorrenter-seeding-limit-override`
([[0054-seeding-limits]] - `grimtorrenter-engine` has zero production dependencies, so no JSON
library is available at this layer):

```
lifetimeUploadedBytes=123456
activeMillis=789000
completedAtEpochMillis=1757500000000
```

One combined file rather than three separate markers - all three update on the same cadence
for the same reason (a live session's ongoing activity), so splitting them would mean three
near-identical read/write helper pairs for values that are never meaningfully read or written
independently.

**Written atomically** (temp file + `ATOMIC_MOVE`), unlike the rarely-changing markers
(state/seeding-limit-override/added-at, which use a plain `Files.writeString` since they only
change on a deliberate user action) - this one is rewritten periodically by a background tick,
the same "written often enough that a torn write is worth avoiding cheaply" reasoning
`saveDhtRoutingTable()` already applied to the DHT routing table marker.

**Read** in both places a torrent's directory-scoped markers are already read: `restoreOne()`
(the plain-restart path) and `addTorrent()`'s reused-directory branch (a torrent removed with
keep-files and now being re-added) - missing marker (brand new torrent, or one added before
this feature existed) defaults to all-zero, the same "no marker = fresh state" idiom every
other marker in this file already follows.

**Flushed**:
- On the same 30-second engine-wide `maintenanceScheduler` tick `checkSeedingLimits()` already
  runs on ([[0054-seeding-limits]]) - a new sibling method, not folded into that one, keeping
  one method per concern on the shared scheduler.
- On `pauseTorrent()` and `removeTorrent(infoHash, false)` (keep-files), right before the
  session stops/closes, so a deliberate stop never loses anything.
- On `shutdown()`, for every session, before closing it - a graceful process exit shouldn't
  rely on the next periodic tick having already run.

Not flushed on `removeTorrent(infoHash, true)` (delete-data) - the whole directory, marker
included, is deleted anyway.

## Wire exposure

Three new fields ride the existing always-broadcast `TorrentView` (`lifetimeUploadedBytes`,
`timeActiveMillis`, `completedAtEpochMillis`), the same "cheap enough not to need a dedicated
endpoint" precedent `usesDht`/`trackerCount` already set in [[0031-torrent-detail-endpoints]] -
these are plain field reads on every broadcast tick, no per-tick computation. `0` for
`completedAtEpochMillis` means "never completed," same sentinel convention used throughout this
codebase (e.g. `addedAt`'s null-means-unknown, `Settings`' 0-means-default fields).

**Frontend**: the detail header gains Time Active (humanized via a new shared
`humanizeDuration()` helper, extracted out of `FormatEtaPipe` into a small utility both it and
a new `FormatDurationPipe` call - `FormatEtaPipe`'s own "Stalled"/em-dash special cases don't
apply to a plain elapsed duration, so a new pipe wrapping the same day/hour/minute/second
humanization logic is cleaner than overloading the existing one), Completed On (Angular's
`DatePipe`, `'short'` format, matching the Trackers tab's own `lastAnnouncedAt`/`nextAnnounceAt`
convention - em dash when `completedAtEpochMillis === 0`), and a lifetime Share Ratio
(`lifetimeUploadedBytes / bytesDownloaded`, guarding the zero-downloaded case the same way
[[0054]]'s own ratio check does).

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Bounded growth**: one small, fixed-size marker file per torrent - same footprint class as
  the state/seeding-limit-override/added-at markers already in place. No unbounded growth.
- **Crash-safety / accepted loss window**: an ungraceful crash (kill -9, power loss) between
  flushes loses at most ~30 seconds of upload/active-time accrual for a torrent that was
  running at the time - nothing corrupted (atomic write), just slightly stale. Explicitly
  accepted rather than engineered around, matching this codebase's existing tolerance for the
  same class of loss elsewhere (the DHT routing table marker, the watch-folder scan snapshot).
- **No hostile-peer angle**: every value here derives from our own already-validated internal
  accounting (`bytesUploaded()`, state transitions) - no untrusted peer input reaches this path.
- **Concurrency**: no new synchronization needed. `activeMillisAccumulated` is an `AtomicLong`
  (same idiom as the existing `accumulatedUploaded`/`accumulatedReceived`), `activeSince` is
  `volatile`, and all marker I/O runs on `maintenanceScheduler`'s single thread (the same
  thread already running `checkSeedingLimits()`/`scanWatchFolder()`) or inline on whichever
  thread already owns the state transition (`pauseTorrent()`, `removeTorrent()`, `shutdown()`),
  never concurrently with the periodic tick's own read of the same session's live counters
  racing anything worse than a torn *read* of two independently-updated numbers - acceptable
  for a display value refreshed every 30 seconds regardless.

## Alternatives considered

- **Overloading `bytesUploaded()`/`bytesDownloaded()` to mean "lifetime" directly** - rejected;
  see the protocol/seeding-limit reasoning above. A quieter, more dangerous shortcut than it
  first looks.
- **Three separate marker files, one per stat** - rejected; all three share one update cadence
  and one owner, so one file avoids tripling near-identical plumbing for no isolation benefit.
- **Flushing on every byte transferred** - rejected; excessive disk I/O for a number nobody
  needs millisecond-fresh, especially multiplied across many concurrently-active torrents.
- **A per-torrent embedded database instead of marker files** - rejected; this codebase
  consistently uses plain marker files for small persisted per-torrent facts at the engine
  layer (state, seeding-limit override, added-at, DHT routing table), specifically because
  `grimtorrenter-engine` carries zero production dependencies. No reason to introduce a
  different mechanism for this one fact.
- **Resetting lifetime stats on every "remove but keep files" re-add** - rejected (user
  decision); inconsistent with `SeedingLimitOverride`'s existing precedent, and a
  torrent-directory-scoped fact like this is more naturally tied to the data than to how many
  times it's been removed and re-added through the UI.
