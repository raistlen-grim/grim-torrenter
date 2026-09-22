# 0075 — Per-file download priorities (selective download)

**Status:** Accepted

## Decision

Every file in a torrent gets one of four priorities: `SKIP`, `LOW`, `MEDIUM` (the default),
`HIGH`. `SKIP` means the file is not wanted and is never requested. `LOW`/`MEDIUM`/`HIGH` are all
wanted, differing only in the order they're fetched - `SKIP` is deliberately a separate concept
from `LOW` (confirmed with the user): `LOW` will still be gotten sooner or later, `SKIP` may
never start. The middle level is named `MEDIUM` in the engine and REST API but labelled "Normal" in the UI (a display choice, 2026-09-19). Chosen *after* a torrent is added, from the Files tab (choosing at add time needs an
"add paused" flow and, for a magnet, resolved metadata first - deferred, a later slice).

Data already on disk for a file that's later set to `SKIP` is **kept**, never deleted
automatically (confirmed with the user).

### Completion is "every wanted piece complete", not "every piece complete"

`PieceManager.isAllComplete()` used to be the completion test (`checkForCompletion()`,
`wasCompleteOnRestore`). Both now use a new `isWantedComplete()`: a torrent whose wanted files
are done reaches `SEEDING` (a partial seed), progress and bytes-remaining are measured against
the wanted set only, and the tracker's `left` is wanted-remaining (what libtorrent reports too).
`bytesDownloaded()` stays "every verified piece", since that's the real lifetime data figure
([[0064-persistent-lifetime-stats]]) and drives the raw-rate calculation.

New transition: **`SEEDING` -> `DOWNLOADING`** when a file is un-skipped (or raised from
nothing) on a torrent that had already completed its wanted set. The existing
`TorrentEventListener` guards (`completedAtEpochMillis == 0`, `wasCompleteOnRestore`) already
make a second completion not re-record `COMPLETED`. The tracker `COMPLETED` announce can fire
again on the second completion - harmless (trackers treat it as a stats event) and not worth a
new guard. Un-skipping needs no new scheduler work: `SEEDING` and `DOWNLOADING` already share the
same running scheduler.

Setting every file to `SKIP` is rejected (`IllegalArgumentException`, a 400 at the REST layer):
a torrent with nothing wanted would "complete" instantly, which is not a state anyone wants by
accident - pause the torrent instead.

### Piece priority: the highest priority among the files a piece overlaps

Files aren't piece-aligned, so a piece can span two files. A piece's tier is the **maximum** of
the non-`SKIP` priorities of every file it overlaps, and it's *unwanted* only when every file it
overlaps is `SKIP`. So a boundary piece shared by a skipped file and a wanted one is still
downloaded (its bytes for the skipped file land on disk - unavoidable, the piece can't be
verified without them). Zero-length files overlap no piece and are ignored.

`SequentialPieceSelectionStrategy` now walks tiers `HIGH` -> `MEDIUM` -> `LOW`, lowest piece index
first within a tier, never picking an unwanted piece. Sequential order within a tier is
unchanged from [[0009-phased-scope]]'s original strategy. `updateInterest()`/`requestMore()`
already go through `selectNextPiece()`, so "am I interested in this peer" correctly becomes
"does this peer have a wanted piece I need." `peers()`' per-peer *relevance* is likewise
computed against wanted-and-incomplete pieces.

Requests already in flight for a piece that is then skipped are left to finish (bounded by the
existing pipeline depth); nothing new is requested for it.

### Storage: unchanged

`TorrentStorage.create()` still preallocates every file (`RandomAccessFile.setLength`, sparse on
most filesystems), so a skipped file exists as a sparse zero-filled file. Not building lazy
per-file creation: a boundary piece may write into a skipped file anyway, and a lazily-created
file would need its own create-on-first-write path through `FileHandlePool` ([[0047-bounded-file-handle-pool]]).
Cost: a filesystem without sparse-file support allocates skipped files' full size up front - the
same behavior every torrent already has today.

### Persistence

One new per-torrent marker, `.grimtorrenter-file-priorities`, in the config-side torrent
directory ([[0065-config-side-per-torrent-storage]]), so it follows the same lifecycle as every
other marker - including surviving a "remove but keep files, re-add later" the way
`SeedingLimitOverride` does. Format is sparse `index=PRIORITY` lines for every file *not* at
`MEDIUM`; absent marker, absent line, unknown priority name, or an out-of-range index all mean
`MEDIUM` (forward-compatible, and a hand-edited/truncated file degrades to "download
everything" rather than failing the restore). Written before the in-memory change takes effect,
same crash-safety ordering as [[0072-per-torrent-limits]]'s `setTorrentLimits`.

Priorities reach `TorrentSession` through a new widest `create()`/`restoreAsync()` overload (the
existing widest one delegates with all-`MEDIUM`), not a post-construction setter: a restoring
session starts re-verification and completion checks on a background thread immediately, and
`wasCompleteOnRestore` must see the correct wanted set from the start.

### REST

`GET /api/torrents/{h}/files` (existing) gains a `priority` string per file. New
`PUT /api/torrents/{h}/files/priorities`, body a JSON array of priority names in file order (one
per file - a length mismatch is a 400), responding with the updated `List<FileView>`. Whole-array
rather than per-file so a client can express "skip everything except X" atomically, and the
synchronous response carries the real resource so the frontend reconciles nothing itself
([[0071-thin-frontend-as-a-standing-consideration]]). `TorrentView` (the always-broadcast DTO)
is unchanged in this slice: `progress` simply now means "of the wanted set."

## Stability

- **Bounded state:** one byte per piece (the tier array, replaced wholesale under the existing
  `PieceManager` lock on each change) and one small list per torrent; nothing grows with time.
- **Locking:** the tier array is read/written under `PieceManager`'s existing `ReentrantLock`,
  no new `synchronized` on a hot path ([[0007-concurrency-model]]). `setFilePriorities()` on the
  session takes the session monitor only for the state flip, same as `checkForCompletion()`.
- **Hostile peer:** no new wire-facing input; priorities only narrow what *we* request. A peer
  can't influence them.
- **Cleanup:** no new resources opened, so no new exit path to clean up. A failed marker write
  throws before the in-memory change is applied.
- **Cost:** `peers()` and progress now consult the tier array per piece - the same O(pieces)
  loops they already ran.

## Deferred

- Choosing files at add time (needs "add paused"; for magnets, needs metadata first).
- A distinct "partial" display for wanted-vs-total size in the torrent list/header
  (`TorrentView.totalSize` is still the full torrent).
- Deleting a skipped file's data on request.
