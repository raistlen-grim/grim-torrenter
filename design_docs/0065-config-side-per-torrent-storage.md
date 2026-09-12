# 0065 — Config-side per-torrent storage, and single-file placement

**Status:** Accepted

## Decision

Raised by the user (2026-09-10) while scoping [[0064-persistent-lifetime-stats]]: this
codebase's download folder currently doesn't look like what other BitTorrent clients produce.
Two separate, related complaints, both fixed here:

1. **Per-torrent marker files live alongside the actual downloaded content**
   ([[0026-resume-state-persistence]]/[[0054-seeding-limits]]/[[0024-name-based-download-directories]]:
   `.grimtorrenter-infohash`, `.grimtorrenter.torrent`, `.grimtorrenter-state`,
   `.grimtorrenter-seeding-limit-override`, `.grimtorrenter-added-at`, and would have grown a
   sixth with 0064's own lifetime-stats marker). A user browsing/sharing the download folder
   (Samba, NFS, a file manager) sees GrimTorrenter's internal bookkeeping mixed in with their
   files - qBittorrent/Transmission keep this kind of state in their own app-data folder,
   entirely separate from the download tree.
2. **Single-file torrents get a redundant wrapper folder**
   (`downloads/movie.mkv/movie.mkv` instead of `downloads/movie.mkv`) - already flagged as a
   "known minor cosmetic quirk" in [[0024-name-based-download-directories]] and explicitly
   deferred at the time ("not addressed unless it becomes an actual problem"). It has now.

**No migration** - confirmed with the user: this is a new, unreleased app with no existing
installs to preserve compatibility for, so the old layout is replaced outright, not migrated.

## Per-torrent config storage: `configDirectory/torrents/<infoHash-hex>/`

Every per-torrent marker moves here, keeping the exact same filenames
(`.grimtorrenter.torrent`, `.grimtorrenter-state`, `.grimtorrenter-seeding-limit-override`,
`.grimtorrenter-added-at`, plus 0064's new `.grimtorrenter-lifetime-stats`) - only the
*directory* they live in changes, minimizing the diff against every existing read/write helper
in `TorrentEngine`. The download folder gets nothing but real content from this point on.

**`.grimtorrenter-infohash` is retired, not relocated.** It existed solely so
`resolveDownloadDirectory` could tell, from inside a name-collision candidate directory itself,
"does this already belong to my info hash" ([[0024-name-based-download-directories]]). Once
config-side storage is keyed directly by info hash (the folder name *is* the info hash), that
question has a direct answer with no marker needed - see path resolution below.

**A new marker, `.grimtorrenter-download-path`**, records the actual resolved location of the
torrent's content (a single file's path for a single-file torrent, a directory for a
multi-file one) - now the sole source of truth for "where do this torrent's files live." This
inverts the previous relationship: today, the download folder's existence *is* the fact that a
torrent exists ([[0026-resume-state-persistence]]'s own explicit reasoning); after this change,
the config-side record is the fact, and it happens to name a download path.

### The self-describing-directory property is deliberately given up here

[[0026-resume-state-persistence]] chose to colocate resume markers with downloaded content
specifically so "a directory carrying [the markers] *is* the record... which can never drift
out of sync with which directories actually exist." Splitting config from content reintroduces
exactly the class of problem that was avoiding: someone can now delete/move a download folder
by hand and the config side won't know.

**Accepted, not engineered around, for the same reason [[0026]] accepted its own known side
effects**: if a download path goes missing, `TorrentSession.restoreAsync()`'s existing
behavior already covers it without any new code - `TorrentStorage.create()` unconditionally
`setLength()`-preallocates every file regardless of what's already there
([[0026-resume-state-persistence]]'s own note), so a missing directory is simply recreated and
every piece fails verification, and the torrent quietly resumes downloading from scratch. No
crash, no data loss (there was nothing left to lose), just a silent full re-download instead of
a surfaced "files missing" error. Real clients (qBittorrent) detect and surface this as a
distinct state - worth doing if it proves to matter in practice, but not built here: it's a new
UX affordance beyond what was asked, not a consequence of this change's actual correctness.

## Path resolution: `resolveDownloadDirectory` rewritten around the config-side record

Today's version scans candidate directory names (`name`, `name-2`, ...) and reads each
candidate's own `.grimtorrenter-infohash` marker to decide "already mine / occupied by
something else / free." With markers gone from the download side, this inverts:

1. **Already known?** If `configDirectory/torrents/<infoHash>/.grimtorrenter-download-path`
   already exists (a torrent re-added after a keep-files removal - see below), reuse that exact
   recorded path directly. No naming/collision scan needed at all - the path was already
   claimed once.
2. **Genuinely new?** Generate candidates (`name`, `name-2`, `name-3`, ...) same as today, but
   what counts as "occupied" simplifies to "something already exists on disk at this path" -
   there's no more per-candidate marker to read, since case 1 above already handled every path
   this engine itself previously claimed. A pre-existing, unrelated file/folder is still
   correctly left alone (same conservative behavior as today), it just costs one
   `Files.exists()` check instead of a marker read.
3. Once resolved, the chosen path is written to that torrent's `.grimtorrenter-download-path`
   marker (created alongside its other markers, same time `addTorrent()` already writes the
   torrent-file/state/added-at markers).

### Single-file torrents resolve a *file* path, multi-file torrents resolve a *directory*

This is the actual fix for the wrapper-folder quirk. `TorrentStorage.create()` **needs no
change at all** - it already branches on `SingleFileTorrent` vs. `MultiFileTorrent` and, for a
single-file torrent, does `baseDirectory.resolve(single.name())`. The nesting only exists
today because `baseDirectory` (== the resolved "torrent directory") is itself already a
per-torrent subfolder (`downloads/movie.mkv/`), so resolving `single.name()` against it lands
one level too deep. Once `resolveDownloadDirectory` hands `TorrentSession`/`TorrentStorage` the
*shared download root* directly for a single-file torrent (instead of a per-torrent subfolder),
`baseDirectory.resolve(single.name())` alone produces the flat `downloads/movie.mkv` path - no
`TorrentStorage` change needed, only what `TorrentEngine` passes it.

Collision-candidate generation for a single-file torrent therefore checks *file* existence
(`downloads/movie.mkv`, `downloads/movie.mkv-2`, ...) rather than *directory* existence -
reusing the exact same `sanitizeDirectoryName`/suffix scheme verbatim for simplicity. **Known
minor cosmetic quirk, accepted rather than engineered around** (same spirit as the quirk this
whole doc is fixing): the suffix lands after the extension (`movie.mkv-2`, not `movie-2.mkv`).
Splitting the extension out to suffix before it would be a small added complexity for a rare
case (two different single-file torrents declaring the exact same file name); not worth it
unless it proves to matter in practice.

Multi-file torrents are completely unaffected - still resolve a directory path, still lay
files out under it exactly as `TorrentStorage.create()` already does today.

## `restore()` scans config, not the download folder

`TorrentEngine.restore()` now enumerates `configDirectory/torrents/*` (each subdirectory named
by info hash) instead of `baseDownloadDirectory/*`. Entry condition is unchanged in spirit -
skip anything without a `.grimtorrenter.torrent` marker, same as today - just relocated. Each
surviving entry reads its `.grimtorrenter-download-path` marker to know where
`TorrentSession.restoreAsync()` should actually look for content, rather than that path being
implied by which download-folder subdirectory the scan happened to be iterating.

## `removeTorrent` unchanged in spirit, relocated

- **`deleteData=true`**: deletes the resolved download path (file or directory, read from
  `.grimtorrenter-download-path`) *and* the entire `configDirectory/torrents/<infoHash>/`
  folder - nothing left behind either side.
- **`deleteData=false`** (keep files): deletes only that torrent's `.grimtorrenter.torrent` and
  `.grimtorrenter-state` markers from its config folder (excludes it from `restore()`'s scan,
  identical effect to today), while leaving `.grimtorrenter-seeding-limit-override`,
  `.grimtorrenter-added-at`, `.grimtorrenter-download-path`, and 0064's
  `.grimtorrenter-lifetime-stats` in place - the same "torrent-scoped facts stay with the data"
  precedent [[0054-seeding-limits]] already established for the seeding-limit override,
  confirmed by the user to extend to lifetime stats too, and now naturally extends to the
  resolved download path as well: a re-add of the same torrent reuses its old path (path
  resolution step 1 above) and picks its history back up, rather than landing in a fresh
  `movie.mkv-2` and starting from zero.

## Testing

Every `TorrentEngineTest` case asserting an exact old-layout path (e.g.
`tempDir.resolve("same-name.bin").resolve("same-name.bin")`,
`directory.resolve(".grimtorrenter.torrent")`) needs updating for the new split - either
asserting flat single-file placement (`tempDir.resolve("same-name.bin")`, no double-nesting) or
reading `configDirectory`-relative marker paths instead. No behavioral change to any of these
tests' actual assertions (disambiguated directories still disambiguate, a kept-files removal
still isn't picked back up by `restore()`, a re-add still reuses its old path and re-verifies
instead of redownloading) - purely updating *where* those facts are checked on disk.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Bounded growth**: unchanged in kind from today - one small per-torrent config folder
  instead of one small set of colocated marker files; same total marker count and size class.
- **No hostile-peer angle**: path resolution and marker I/O are driven entirely by local state
  (torrent metadata's own declared name, this engine's own prior records) - no peer/tracker
  input reaches this path.
- **Accepted drift risk**: covered above - a manually-deleted download path silently triggers a
  full re-download rather than surfacing an error. Explicitly accepted, not a regression
  relative to a guarantee this codebase previously made (today's colocated markers would
  simply vanish along with a manually-deleted folder instead, which is arguably *more* silent,
  not less - the torrent just disappears from `restore()`'s registry with no signal either).
- **Concurrency**: no change - `resolveDownloadDirectory` keeps its existing dedicated lock
  ([[0024-name-based-download-directories]]) around the whole candidate-resolution-and-claim
  sequence; the "already known" fast path (config lookup) needs no new locking since it's a
  single read of one already-fully-written marker file.

## Alternatives considered

- **Keep one tiny anchor marker in the download folder** (the "hybrid" option raised alongside
  this decision) - rejected (user decision) in favor of a full split; the user's stated goal
  was a download folder indistinguishable from what other clients produce, and even one hidden
  file falls short of that.
- **Migrate existing installs' old-layout directories to the new layout on startup** - rejected
  (user decision): no existing installs to migrate, since this app hasn't shipped yet.
- **Fix the wrapper-folder quirk independently of the config-side split, as a smaller
  standalone change** - rejected (user decision): same underlying "look like other clients"
  motivation, worth doing together rather than as two separate passes touching the same
  `resolveDownloadDirectory` logic twice.
- **Build explicit "download files missing" detection now** - considered, deferred; see the
  accepted-drift-risk reasoning above. Worth revisiting if it proves to matter in practice, not
  speculatively built ahead of that.
