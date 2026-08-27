# 0057 — Torrent added-at timestamp

**Status:** Accepted

## Decision

The style-guide restyle of the details panel ([[0032-style-guide-and-primeng-theme]]) specs an
"Added" fact-grid cell (`12 Aug, 09:14`, absolute date on hover) that nothing in the codebase
tracked. Rather than drop that cell silently, this is a standalone, user-requested task to add
the missing data before task 6 of that pass builds the fact grid around it.

`TorrentSession` gains a new immutable `Instant addedAt` field, set once at construction
(`create()`/`restoreAsync()`) and never reassigned - a genuinely brand-new torrent (`create()`,
or `addTorrent()`'s "removed with keep files, now re-added" `restoreAsync()` case) stamps
`Instant.now()`; a torrent restored from a previous process run (`TorrentEngine.restoreOne()`)
reads it back from a new per-directory marker file, `.grimtorrenter-added-at` (ISO-8601 instant
text), following the exact same "marker file next to the torrent's data, no central DB" pattern
already used for the torrent-file marker, the state marker and the seeding-limit-override
marker.

### Nullable, not backfilled

A directory added before this field existed has no marker to read. `readAddedAtMarker()`
returns `null` for that case (and for a corrupt/unparseable marker, logged and treated the
same way rather than failing the whole restore over one cosmetic fact) - `TorrentSession.
addedAt()` is nullable throughout, same convention as `lastError()`, all the way out to the
frontend's `addedAt: string | null`. No migration/backfill: the restore timestamp is not the
add timestamp and would be a *wrong* value with real confidence behind it, which is worse than
an honest "unknown" the UI can render as an em dash. Exactly the same reasoning
[[0054-seeding-limits]] already used for its own marker ("absent means inherit... no migration
needed").

### Threaded through the existing telescoping-overload chain, not a new parameter everywhere

`TorrentSession.create()`/`restoreAsync()` are already long chains of overloads, each adding one
more optional constructor knob for callers that predate it (rate limiting -
[[0042-rate-limiting]], encryption - [[0052-message-stream-encryption]], seeding-limit override
- [[0054-seeding-limits]]). `addedAt` follows the same pattern: the
previous longest overload of each becomes a delegating shim that stamps `Instant.now()`, and a
new longest overload takes the real `Instant addedAt` explicitly. Only `TorrentEngine`'s two
real call sites (`addTorrent()`, `restoreOne()`) - which already called the longest overload of
each - needed updating; every shorter overload, and every existing test that calls one, is
source-compatible and unchanged in behavior (there was no `addedAt` to observe before, so
`Instant.now()` being stamped underneath them changes nothing observable).

`TorrentView` (grimtorrenter-app) reads `session.addedAt()` directly rather than threading a
second parameter through `TorrentView.from(session, ...)` and updating its four call sites
(`TorrentResource` ×2, `TorrentEventListener`, `TorrentSnapshotScheduler`) - `addedAt` is
session-lifecycle data of exactly the same kind as the metadata/state/lastError this class
already reads off `TorrentSession` the same way.

## Scope

Backend + the frontend `Torrent`/`TorrentWithRate` model field only. No UI renders it yet -
that's [[0032-style-guide-and-primeng-theme]]'s task 6 (fact grid), which can now build the
"Added" cell against real data instead of dropping it.

Explicitly not built: "Saved to" (the details panel's other data-gap fact-grid cell, the
per-torrent download directory). Raised alongside this one, but judged not worth adding - this
is a headless, self-hosted web client with no in-browser way to act on a filesystem path (the
guide's own spec for that field, "click to reveal in the file manager," is a desktop-app
affordance that doesn't exist here at all), and there's currently exactly one download
directory in play per torrent, so the path is rarely more than duplicated information the user
was the one who configured it in Settings in the first place.

## Stability ([[0051-stability-as-a-standing-consideration]])

One new file per torrent directory, same bounded footprint as the three marker files already
written there - no unbounded growth. Write failures throw `TorrentEngineException` (same as
`writeStateMarker`); read failures (missing or corrupt marker) are swallowed to `null` and
logged rather than raised, so one bad timestamp can never block a whole `restore()` scan the
way a thrown exception from `restoreOne()` would otherwise risk (deliberately: `restoreOne()`'s
own contract is already "log and skip on any failure", so this matches its existing error
handling rather than introducing a new failure mode into it). No new locking/concurrency: the
field is `final`, set once at construction off the same thread that already creates the
`TorrentSession`, before it's published to `sessions` - no `synchronized` needed, consistent
with [[0007-concurrency-model]].

## Alternatives considered

- **A central index/database of add times** - rejected; this codebase has no such store
  anywhere (every other piece of per-torrent state lives in a marker file next to the data
  itself, precisely so a torrent's own directory is self-describing and survives being copied
  or moved independently of any central index). Would have been a new persistence mechanism
  for one field.
- **Backfilling unknown torrents with the restore/process-start time** - rejected; see
  "Nullable, not backfilled" above. A confidently-wrong date is worse than an honest gap.
