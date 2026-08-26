# 0056 — Watch folder

**Status:** Accepted

## Decision

Picked from `TODO.md`'s "Watch folder" item: a monitored directory where dropping a `.torrent`
file auto-adds it, no manual upload needed. Explicit user requirement from scoping: outcomes go
into **`added/`/`failed/` subfolders** (not deleted, not left in place), with **configurable
cleanup** of those subfolders so they don't grow unbounded - matching the bounded-retention
discipline [[0055-library-events]] already established for the event log
([[0051-stability-as-a-standing-consideration]]). Magnet-link files (e.g. a dropped `.magnet`
text file) are explicitly **out of scope for this pass** - `.torrent` files only.

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
  first real usage is likely to demand tighter latency than that. Revisit if it does.
- **Magnet-link files** (e.g. a `.magnet` text file convention) - deferred (user decision,
  scoping conversation); `.torrent` files cover the concrete stated use case, and the mechanism
  built here (poll, stabilize, process-or-fail, move) extends to a second file type later without
  rework.
