# 0037 — Reuse existing data when re-adding a previously-removed torrent

**Status:** Accepted

## Decision

A known gap tracked in `PROGRESS.md` since [[0029-optimistic-upload-feedback]] first raised
it: removing a torrent with "keep files" (the default) leaves its downloaded data and
`INFO_HASH_MARKER_FILENAME` marker on disk (see [[0024-name-based-download-directories]]),
so `resolveDownloadDirectory` already correctly routes a later re-add of the same torrent
back to that same directory instead of disambiguating into a `-2` suffix. But `addTorrent()`
then unconditionally called `TorrentSession.create()` for any newly-created session -
`create()` always starts a brand-new `PieceManager` with every piece `NEEDED`, ignoring
whatever was already sitting on disk. The re-added torrent would silently re-download data
that was already correct - a real bandwidth/time waste, not just a missing optimization,
since `TorrentSession.restoreAsync()` (the exact re-verify-in-the-background path a process
restart already uses, see [[0026-resume-state-persistence]]) was sitting right there unused
for this case.

**Fix: `resolveDownloadDirectory` now tells its caller whether the directory it resolved
was freshly created or reused.** `claimDirectory`'s previous `boolean` return (occupied vs.
usable) became a three-way `ClaimResult` enum (`OCCUPIED`/`CREATED`/`REUSED`), and
`resolveDownloadDirectory` returns a new `DirectoryResolution(Path directory, boolean
preExisting)` record instead of a bare `Path`. `addTorrent()` uses that flag to choose
`TorrentSession.restoreAsync(..., true)` (background re-verify, then auto-start) instead of
`TorrentSession.create()` + an explicit `start()` - the exact same choice `restoreOne()`
already makes for every directory found during a process-restart scan, just reached from a
different trigger (an explicit re-add within the same running process, not a restart).

**`preExisting` is a conservative proxy for "might hold real data," not a guarantee of
it** - a directory could in principle be freshly marked but never actually written to
(e.g. a crash between claiming it and any download progress). `restoreAsync()` handles
that whole range correctly regardless: a piece that fails verification just gets marked
`NEEDED` exactly as `create()` would have set it up in the first place, at the cost of one
harmless background hash pass. This mirrors `restoreOne()`'s own existing behavior, which
already re-verifies unconditionally for every restored directory regardless of how much
progress it actually holds.

**Deliberately did *not* make `addTorrent()` always use `restoreAsync()`** (i.e. drop the
`preExisting` distinction and treat every add uniformly) - `TorrentStorage.create()`
pre-allocates every file to its full declared length immediately, so a brand-new torrent's
storage is already a full-length (sparse/zero) file the instant it's created. Verifying
that unconditionally for every new add would mean hashing a potentially huge amount of
zero-filled data before ever starting the first real download - a real regression for the
overwhelmingly common case (adding a torrent for the first time), not just wasted but
harmless work like the crash-before-progress edge case above.

## Testing

`TorrentEngineTest` gained
`reAddingAPreviouslyRemovedButDataKeptTorrentVerifiesExistingDataInsteadOfRedownloading`:
adds a torrent, removes it with data kept, writes the *correct* file content directly to
the still-marked directory (same direct-write technique `TorrentSessionTest`'s own
`restoreAsync` tests already use, rather than driving a full peer-wire download just to get
correct bytes on disk), then re-adds the same torrent bytes. Deliberately no fake peer
anywhere in the test - the re-added session can only ever reach `SEEDING` by verifying the
existing file, since nothing could otherwise supply the missing piece. If the bug
regressed, the session would sit in `DOWNLOADING` forever and the test's `awaitState`
deadline would fail it, rather than the test passing vacuously.

Existing `TorrentEngineTest` coverage (`addTorrentParsesStartsAndRegistersSession`,
`addTorrentTwiceReturnsSameSession`, `differentTorrentsWithSameDeclaredNameGetDisambiguatedDirectories`,
`reAddingSameTorrentFromAFreshEngineReusesItsExistingDirectory`, etc.) all either add a
genuinely brand-new torrent (`CREATED`, unaffected - still goes through `create()`) or
don't exercise `addTorrent()`'s directory-reuse branch in a way this change alters
observable behavior for, and continue to pass unchanged.

## Alternatives considered

- **Always use `restoreAsync()` for every `addTorrent()` call**, dropping the
  `preExisting` distinction entirely - rejected; would regress the common brand-new-torrent
  case into hashing a full-length pre-allocated (zero) file before the first real download
  even starts, for torrents of any size.
- **A separate `Files.exists`/content-based check** ("does this directory actually contain
  non-trivial data") instead of the marker-presence-based `preExisting` flag - rejected as
  unnecessary complexity; `restoreAsync()` already handles the full range from "nothing
  really there" to "fully complete" correctly and cheaply for a single-piece-scale check,
  so a coarser, already-available signal (was this directory previously claimed for this
  info hash) is sufficient and avoids inventing new disk-inspection logic.
