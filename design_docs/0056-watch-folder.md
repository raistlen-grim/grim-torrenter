# 0056 — Watch folder

**Status:** Accepted. `.magnet` file support and a configurable poll interval - both originally
deferred, see the "Alternatives considered" section below - were built 2026-09-06; see their own
addenda further down.

## Decision

Picked from `TODO.md`'s "Watch folder" item: a monitored directory where dropping a `.torrent`
file auto-adds it, no manual upload needed. Explicit user requirement from scoping: outcomes go
into **`added/`/`failed/` subfolders** (not deleted, not left in place), with **configurable
cleanup** of those subfolders so they don't grow unbounded - matching the bounded-retention
discipline [[0055-library-events]] already established for the event log
([[0051-stability-as-a-standing-consideration]]). Magnet-link files (e.g. a dropped `.magnet`
text file) were explicitly **out of scope for this first pass** - `.torrent` files only - see the
2026-09-06 addendum below for why and how that was later added.

### Directory layout and config

A new `grimtorrenter.watch-directory` `@ConfigProperty` (default `watch`), independently
mountable - same convention as `download-directory`/`config-directory`
([[0041-live-settings-store]]). Created automatically if missing, same "create if absent, don't
fail startup over it" spirit as everything else in this codebase that owns a directory.

Two subdirectories, created lazily the first time they're needed:
`{watch-directory}/added/` (successfully-added files land here) and
`{watch-directory}/failed/` (files that couldn't be added land here). The scan itself is
**non-recursive** (`Files.list`, matching `TorrentEngine.restore()`'s own top-level-only scan of
`baseDownloadDirectory` - not `Files.walk`) specifically so `added/`/`failed/` are never
themselves rescanned as if they were newly-dropped files.

### Detection: periodic polling, not a filesystem watcher

Considered and rejected `java.nio.file.WatchService` (native inotify/FSEvents/ReadDirectoryW):
this is a self-hosted app that expects `watch-directory` to be a Docker bind mount, and native
filesystem-change notifications are well known to not reliably propagate through Docker bind
mounts on macOS/Windows (Docker Desktop's gRPC-FUSE/VirtioFS layer, and NFS/SMB-backed mounts in
general) - a watcher that silently stops noticing new files on a meaningful fraction of real
deployments is worse than simple polling. Polling also needs no new dependency and matches this
codebase's own existing pattern for periodic engine-owned work
(`TorrentEngine.checkSeedingLimits()`, [[0054-seeding-limits]]).

A new package-private `TorrentEngine.scanWatchFolder()` (same test-visibility rationale as
`checkSeedingLimits()` - a test calls it directly rather than waiting on a real tick), ticking
on the **same shared daemon-threaded scheduler** `checkSeedingLimits()` already runs on -
renamed from `seedingLimitScheduler` to a general-purpose `maintenanceScheduler` this feature
generalizes it into, rather than spinning up a second dedicated thread for a second periodic
concern. Both checks are cheap (O(active sessions) / O(files in a small folder)), so sharing one
thread is safe and avoids an ever-growing "one thread per periodic feature" pattern as more of
these get added over time. Interval is a fixed constant for this first pass (proposed: 30
seconds, matching the existing seeding-limit cadence) - not yet a configurable Settings field;
see Alternatives.

**Only reads `watch-directory` at all when `Settings.watchFolderEnabled` is true** - checked
fresh on every tick (genuinely live, like rate limits/encryption mode - not a
construct-once-then-fixed boolean like `dhtEnabled`/`acceptIncomingConnections`, since there's
no real socket/resource to tear down or recreate here, just "do nothing this tick" vs. "scan").
Default **disabled** - an opt-in feature that moves/deletes files a user drops somewhere is a
bigger surprise to default on than DHT/incoming-connections ever were, matching the rate-limit/
seeding-limit precedent of defaulting a potentially-surprising behavior off.

**Partial-write guard**: a dropped file being actively written (a slow copy, an SFTP upload
still in progress) must not be read mid-write. Each tick records `(size, lastModifiedTime)` for
every candidate file it sees; a file is only processed once its `(size, lastModifiedTime)` is
identical across two consecutive ticks (i.e. unchanged for at least one full poll interval) -
cheap, no platform-specific file-locking API needed, and correct for the realistic case (a copy
finishing takes far less than 30 seconds).

### Outcome handling

- **Success** (`TorrentEngine.addTorrent()` returns normally, whether newly-created or an
  idempotent re-add of an already-tracked info hash): the file moves to `added/`. An idempotent
  re-add still counts as success from the watch folder's perspective (the torrent *is* tracked
  now, regardless of whether this exact drop was what did it) - and `addTorrent()` already only
  fires an `ADDED` library event when the session was genuinely newly created
  ([[0055-library-events]]), so no duplicate event risk either way.
- **Failure** (`IOException` or `MetainfoException`/other `RuntimeException` from
  `MetainfoParser`/`addTorrent()` - the exact same `catch (IOException | RuntimeException e)`
  shape `TorrentEngine.restoreOne()` already uses for its own best-effort directory scan): the
  file moves to `failed/` unchanged (no error-code suffix on the filename - the *why* lives in
  the library event's message, not encoded into the filename, to keep the move logic simple).
- **Name collision** at the destination (the same filename was already moved there once before,
  e.g. the same file re-dropped after a previous failure was fixed and re-dropped again): reuses
  `TorrentEngine`'s own existing `-2`, `-3`, ... suffix convention from
  `resolveDownloadDirectory()`, rather than inventing a second collision scheme.
- **Missing `added/`/`failed/` directories** (explicit user requirement: guard against them not
  existing, not just create them once at startup) - `moveWithCollisionSuffix()` calls
  `Files.createDirectories(destinationDir)` immediately before every single move, not only once
  per tick or once at construction. This recreates either subfolder if it was deleted (by the
  user, by an external cleanup script, by anything) at any point between ticks, since the
  guard runs at the exact moment it's actually needed rather than relying on an earlier check
  in the same tick to still hold true.

### Settings and event-log integration

Two new live fields on `Settings` (another sibling-constructor-overload addition, same pattern
every prior field followed): `watchFolderEnabled` (boolean, default `false`) and
`watchFolderRetentionDays` (int, default 7, **no "0/negative = unlimited" sentinel** - same
`<= 0` -> normalized-to-default treatment `eventLogRetentionDays` uses, for the same reason: the
user's own stated requirement here was "make sure this doesn't grow out of control," so offering
an unlimited option would defeat the point). A new **Watch folder** settings-page group
(enable toggle + retention field), following [[0045-settings-page]]'s "own component/
form-builder pair" convention.

The *same* `scanWatchFolder()` tick that looks for new files also prunes `added/`/`failed/` of
anything older than `watchFolderRetentionDays` - one shared retention value for both
subfolders in this pass (a fixed cleanup interval, not something a user tunes with any more
precision than "keep for N days"), reusing `JsonLinesEventStore.prune()`'s exact day-based
comparison idiom rather than inventing a second one.

**`addTorrent()` gains an internal, engine-only notion of *source*** so a watch-folder-triggered
add is distinguishable in the event log from a direct upload: the existing public
`addTorrent(byte[])` stays exactly as-is (source `null`, meaning "direct upload" - unchanged
behavior, zero new call-site burden on `TorrentResource`/magnet resolution), and a new
package-private `addTorrent(byte[], String source)` is what actually does the work, called by
`scanWatchFolder()` with `"watch folder"`. The `ADDED` event's `message` becomes
`"Added via watch folder"` when a source is present, `null` otherwise - closing part of the gap
flagged when `TODO.md`'s watch-folder item was written, without touching every existing
`addTorrent()` call site. A failed add records a new `ERROR`-typed event with `infoHash`/
`torrentName` both `null` (parsing may have failed before an info hash was even extracted) and a
`message` naming the dropped file and the failure reason, e.g.
`"Watch folder: could not add bad-file.torrent (Missing or invalid 'info' dictionary)"`.

### REST/frontend

No new REST endpoint or frontend page beyond the Settings group above - `added/`/`failed/` are
plain, directly-browsable directories on whatever host path is bind-mounted, and outcomes are
already visible through the existing Events page ([[0055-library-events]]). Revisit if a
"pending files currently in the watch folder" indicator proves worth having once this is used
in practice.

## Testing

- `WatchFolderTest` (new, plain JUnit, same style as `ManyTorrentsRestoreLoadTest`/
  `TorrentEngineTest`'s seeding-limit tests - calling `scanWatchFolder()` directly rather than
  waiting on the real 30-second tick): a dropped valid `.torrent` file is added and moved to
  `added/`; a malformed file is moved to `failed/` and records an `ERROR` event naming the file;
  an idempotent re-add of an already-tracked info hash still moves to `added/` without a second
  `ADDED` event; a file whose `(size, mtime)` changed between two ticks is left in place (not
  yet processed); a same-named file dropped twice gets the `-2` suffix on its second move to
  `added/`; `scanWatchFolder()` is a no-op when `watchFolderEnabled` is `false`.
- `TorrentEngineTest` (new case) - a watch-folder-sourced add records its `ADDED` event with the
  `"Added via watch folder"` message; a direct `addTorrent(byte[])` call still records `message:
  null`, confirming the new source parameter didn't change existing behavior.
- Retention: `added`/`failed` files older than `watchFolderRetentionDays` are deleted on the
  next `scanWatchFolder()` tick; one within the window survives - same shape as
  `JsonLinesEventStoreTest`'s own prune test.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Unbounded growth**: `added/`/`failed/` are bounded by the same day-based retention-and-prune
  discipline as the event log, per the user's own explicit requirement - no unlimited option.
- **Hostile/malformed input**: a dropped file can't do anything a malicious upload through the
  existing REST endpoint couldn't already do - it goes through the exact same
  `MetainfoParser`/`addTorrent()` path, with the exact same limits and failure handling. The one
  new attack surface is the partial-write guard itself: a file that never stops changing (e.g.
  something continuously appending to it) is simply never processed - a permanent no-op, not a
  crash or a resource leak.
- **Concurrency vs. [[0007-concurrency-model]]**: one scan tick at a time (via the shared
  `maintenanceScheduler`, `concurrentExecution = SKIP`-equivalent single-threaded executor, same
  as `checkSeedingLimits()` today) - no new locking, and `Files.move`/`Files.list` are
  themselves atomic-enough for this single-writer-per-directory use.
- **Resource cleanup**: no held file handles across ticks - each tick lists, stats, and
  moves/deletes independently, then the thread goes idle until the next tick, same as every
  other file-system-touching periodic task in this codebase.
- **Consolidating two schedulers into one** (`seedingLimitScheduler` -> `maintenanceScheduler`)
  is itself a small stability improvement: one fewer daemon thread per `TorrentEngine` instance,
  including every short-lived test-constructed engine that never calls `shutdown()`.
- **Accepted narrow edge case: a file whose *move* fails after a successful add/parse
  outcome.** The add/parse outcome (and its event, if any) is recorded before the move is even
  attempted, so this can't duplicate or lose an `ADDED`/`ERROR` event on retry - `addTorrent()`'s
  own idempotency means a stuck `added/`-bound file just gets silently re-confirmed as already
  tracked each time it's retried, not re-recorded. A stuck `failed/`-bound file, however, *would*
  re-record a fresh `ERROR` event each time it's retried, since failures aren't deduplicated the
  way successful adds are - bounded to roughly once every two poll intervals (a processed file
  is dropped from the stability-tracking snapshot, so it's treated as newly-seen and needs one
  more full interval to stabilize again before being retried), not once per tick. A destination
  directory that keeps failing to accept a move is itself a sign of a broken environment (e.g. a
  permissions problem) an operator needs to notice and fix regardless; this is judged an
  acceptable, self-limiting edge case rather than one worth more machinery to fully close off.

## Alternatives considered

- **`WatchService`/native filesystem events** instead of polling - rejected; unreliable through
  Docker bind mounts on a meaningful fraction of real host platforms, which defeats the point of
  a background auto-add feature that's supposed to just work.
- **A single flat `processed/` folder** instead of separate `added/`/`failed/` - rejected (user
  decision); the explicit ask was to be able to tell success from failure without opening the
  event log.
- **Renaming moved files with an embedded error code/reason** - rejected; adds filename-encoding
  complexity for information the library event's `message` already carries more legibly.
- **Reusing `eventLogRetentionDays`** for `added/`/`failed/` cleanup instead of a dedicated field
  - rejected; the user asked specifically about cleaning up *this* folder, and a `.torrent`
    file's useful "I might want to double check this" lifetime isn't necessarily the same length
    as how long the structured event log is worth keeping.
- **A configurable poll interval** - deferred, not rejected outright; a fixed 30-second constant
  is simple and matches the existing seeding-limit cadence, and nothing about this feature's
  first real usage is likely to demand tighter latency than that. **Built 2026-09-06** - see the
  addendum below.
- **Magnet-link files** (e.g. a `.magnet` text file convention) - deferred (user decision,
  scoping conversation); `.torrent` files cover the concrete stated use case, and the mechanism
  built here (poll, stabilize, process-or-fail, move) extends to a second file type later without
  rework. **Built 2026-09-06** - see the addendum below.

## Configurable poll interval (added 2026-09-06)

Closes this doc's own deferred "configurable poll interval" item. New live `Settings` field
`watchFolderPollIntervalSeconds` (default 30s, matching the fixed cadence it replaces), same
**no** "0/negative means unlimited" treatment as every other tunable interval field
(`eventLogRetentionDays`, `dhtRefreshIntervalSeconds`, ...) - silently normalized to the default
by `Settings`' own compact constructor.

Same "engine-wide scheduled task, read once at construction, a live change takes effect on the
engine's next construction/restart, not retroactively" shape `dhtRefreshIntervalSeconds`
([[0028-magnet-links-and-dht]]'s own 2026-08-30 addendum) already established - the underlying
`ScheduledExecutorService.scheduleWithFixedDelay()` period genuinely can't change mid-flight
without cancelling and rebuilding the whole task, so this is a property of what
`maintenanceScheduler` is, not a limitation particular to this field. `TorrentEngine` replaces
the previous `WATCH_FOLDER_SCAN_INTERVAL_SECONDS` constant with a `watchFolderScanIntervalSeconds`
field read from `Settings` at construction. Exposed as a new row in the Watch folder settings
group, its own description calling out the restart caveat explicitly (same convention every
other restart-required row on the Settings page already follows).

## Magnet-link files (added 2026-09-06)

Closes this doc's own deferred "magnet-link files" item. A dropped `.magnet` file is just a bare
`magnet:` URI as the file's entire (trimmed) text content - no other structure. `scanWatchFolder()`'s
candidate filter now matches either `.torrent` or `.magnet` (case-insensitive), and
`processWatchedFile()` dispatches on extension to one of two extracted methods
(`processWatchedTorrentFile()`, the pre-existing logic unchanged in behavior; a new
`processWatchedMagnetFile()`).

### "Added" means "accepted," not "resolved" - a real asymmetry from the `.torrent` case

A `.torrent` file's outcome is fully synchronous - `addTorrent()` either returns (the torrent
really is tracked) or throws (it really isn't) by the time `processWatchedTorrentFile()` decides
which subfolder to move it to. A magnet add is fundamentally different:
`TorrentEngine.addMagnet()` only ever synchronously resolves *one* case (no usable tracker and
DHT unavailable - throws immediately) - every other case kicks off a real peer metadata fetch on
a background virtual thread and returns immediately, with the real success/failure known only
much later (bounded by `Settings.magnetFetchTimeBudgetSeconds`, default 90s).

`processWatchedMagnetFile()` therefore moves a file to `added/` as soon as `addMagnet()` accepts
it without throwing - **not** once it's actually resolved into a real torrent. This mirrors the
same "success at request time isn't the same as success" shape already established for the REST
magnet-add endpoint and its own optimistic pending-row UI ([[0060-magnet-add-failure-feedback]]):
a background failure still surfaces, just as a `MAGNET_ADD_FAILED` library event rather than by
this file's on-disk location. Waiting here for the real outcome before moving the file was
rejected outright (see Alternatives) - it would block this whole scan tick for up to the full
fetch time budget *per file*, serializing every other watch-folder candidate behind whichever
magnet happens to be resolving slowest.

### Source threading, for parity with the `.torrent` case

A watch-folder-dropped `.torrent` file's resulting `ADDED` event already reads "Added via watch
folder", distinguishing it from a direct upload. Making a watch-folder-dropped *magnet* file's
eventual `ADDED` event carry the same distinction (rather than the generic "Added via magnet"
every other magnet resolution gets, per this doc's own `MAGNET_RESOLVED` addendum) meant
threading a `source` parameter the rest of the way through the magnet pipeline, which didn't
carry one before now:

- `addMagnet(MagnetLink)` (public) now delegates to a new package-private
  `addMagnet(MagnetLink, String source)` - `null` from the public overload (ordinary REST/UI
  adds, unchanged behavior), `WATCH_FOLDER_SOURCE` from `processWatchedMagnetFile()`.
- `fetchMagnetMetadataViaTrackerThenAdd()`/`fetchMagnetMetadataViaDhtThenAdd()` both gained a
  `source` parameter, threaded straight through to whichever they call next.
- `addFetchedTorrent()` (already threading a `source` through to `addTorrent()` since the
  `MAGNET_RESOLVED` addendum) now resolves it as `source != null ? source : MAGNET_SOURCE` -
  `"magnet"` for the ordinary case (unchanged), the watch folder's own source string otherwise.
- `recordMagnetAddFailed()` gained the same `source` parameter, prefixing a non-null source as
  `"Watch folder: "` onto the message - the same literal prefix
  `processWatchedTorrentFile()`'s own `ERROR` event already uses for a failed `.torrent` add, so
  a watch-folder-triggered `MAGNET_ADD_FAILED` reads consistently with its `.torrent` counterpart
  rather than looking like any other magnet failure.

### Testing

- `WatchFolderTest` (new cases) - a `.magnet` file with a usable (if unreachable) tracker URL is
  accepted and moved to `added/` with no `ERROR` event recorded (the background fetch's own
  eventual failure is a separate, unasserted `MAGNET_ADD_FAILED`, matching how this file's
  existing `.torrent` tests already leave a resulting `ERROR` *session* state unasserted); a
  `.magnet` file with no tracker and DHT disabled is moved to `failed/` with an `ERROR` event
  naming the file (the one synchronous-throw case); a malformed `.magnet` file (not a magnet URI
  at all) is likewise moved to `failed/` with an `ERROR` event naming the file.
- `TorrentEngineTest` (new case) - `addFetchedTorrent()` called with a non-null source records
  `"Added via watch folder"` instead of `"Added via magnet"` - the one piece of new logic in the
  source-threading chain above that's cheaply, deterministically testable without a real peer
  metadata fetch (every other link in the chain is a straight parameter pass-through).

## Stability addendum ([[0051-stability-as-a-standing-consideration]])

- **Hostile/malformed input**: a dropped `.magnet` file goes through the exact same
  `MagnetLink.parse()`/`addMagnet()` path a REST-submitted magnet already does, with the same
  limits - no new attack surface. A file that's neither valid UTF-8 text nor a parseable magnet
  URI is simply a synchronous failure, moved to `failed/` like any other malformed input.
- **No new unbounded growth**: `.magnet` files share the exact same `added/`/`failed/`
  retention-and-prune discipline as `.torrent` files - no new storage mechanism.
- **Resource cleanup**: reading a `.magnet` file's text content is a single bounded read (a
  magnet URI is always a short, single-line string in practice), not a stream held open across
  ticks - same "list, stat, move/delete, then go idle" shape as everything else in this method.
- **The "accepted, not resolved" asymmetry above is itself worth flagging as a real, accepted
  narrow edge case**: a `.magnet` file that ends up in `added/` still cost this feature nothing
  extra to *bound* - the metadata-fetch attempt it kicked off is subject to the exact same
  engine-wide `magnetFetchConcurrencyLimit`/`magnetFetchTimeBudgetSeconds` bounds every other
  magnet add already respects (design_docs/0028's own addendum), regardless of whether it was
  triggered by this feature or the REST endpoint.

## Alternatives considered (2026-09-06 addendum)

- **Waiting for the real fetch outcome before deciding `added/` vs. `failed/`** - rejected; would
  serialize `scanWatchFolder()`'s entire tick behind whichever magnet in the batch takes longest
  to resolve (up to the full `magnetFetchTimeBudgetSeconds` budget), defeating the point of a
  background, concurrent, virtual-thread-based fetch design elsewhere in this codebase. The
  existing `MAGNET_ADD_FAILED` event already gives a real, if asynchronous, failure signal.
- **A distinctly-labeled "pending" third subfolder** for magnet files whose fetch hasn't resolved
  yet - rejected; would need `scanWatchFolder()` to track in-flight fetches across ticks and move
  the file a *second* time once resolved, real added complexity for a distinction the Events tab
  (`MAGNET_ADD_FAILED`/`ADDED`) already makes without it.
- **Leaving watch-folder-dropped magnets labeled generically "Added via magnet"** (no source
  threading) - considered, for less invasive scope; rejected in favor of the fuller threading
  above once it was clear the change was a straightforward parameter pass-through at every link,
  for consistency with the `.torrent` case's own existing "Added via watch folder" precedent.
