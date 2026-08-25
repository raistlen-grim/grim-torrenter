# 0026 — Resume state persistence

**Status:** Accepted

## Decision

Until now, a Quarkus restart forgot every torrent: `TorrentEngine.sessions`
is purely in-memory, and `TorrentSession.start()` was documented as
"every start is treated as fully fresh - no verification of pre-existing
disk data" ([[0017-torrent-session]]). In-process pause/resume already
worked (the `PieceManager` completion bitset just stays in memory across
`stop()`/`start()`), but the process itself had no memory.

**Recheck-by-rehashing, not a persisted fast-resume bitfield.** Confirmed
with the user. `TorrentSession.restoreAsync()` re-hashes every piece
already on disk against the expected piece hash (reusing
`PieceManager.verify()` as-is), rather than persisting a serialized
completion bitset. Self-healing against unclean shutdowns and partial
writes, no new on-disk format to keep in sync with reality. Trade-off
accepted: a restart costs CPU/disk proportional to how much is already
downloaded (SHA-1 over even a multi-GB file is low single-digit seconds,
not the minutes a full re-download would cost).

**Restored torrents are visible immediately, not after verification
finishes.** Raised by the user: the priority is an unambiguous UI - show
what exists as soon as it's known, rather than making the whole list wait
on a background computation. `TorrentSession.restoreAsync()` returns
straight away in the already-reserved `VERIFYING` state (the enum comment
previously said this was "for a future resume feature... once persisted
resume state exists" - this is that feature) and kicks the actual
re-hash off on a background virtual thread. The caller (`TorrentEngine`)
registers the session immediately, so it shows up in the next snapshot/
websocket push in `VERIFYING` before a single byte has been re-hashed.
Once the background thread finishes, the session settles to `STOPPED`
and then, if the torrent's persisted desired-state was "running", calls
the ordinary `start()` - the same path a live download already uses,
so no separate "resume into DOWNLOADING/SEEDING" logic was needed.

`restoreAsync()` is safe to call even for a torrent with 0 bytes
downloaded - `TorrentStorage.create()` already pre-allocates every file to
its full length via `setLength()` regardless of progress, so reading any
piece's byte range is always in-bounds; it just fails verification and
that piece's `blockReceived` bookkeeping stays empty, exactly as if it had
never been touched.

**`start()` itself is unchanged** - it still does no disk verification.
The only new behavior is a call to the existing `checkForCompletion()` at
the end of `start()`: without it, a restored torrent that's already 100%
complete would set `state = DOWNLOADING` and stay there forever, since
nothing else re-checks completion outside of a freshly-verified piece
arriving over the wire. Calling it here reuses the exact same "already
complete -> SEEDING + COMPLETED announce" path a live download takes when
its last piece verifies.

**Known minor side effects, accepted rather than special-cased:**
- Restoring an already-fully-downloaded, auto-started torrent sends a
  `STARTED` announce (from `start()`) immediately followed by a
  `COMPLETED` announce (from `checkForCompletion()`) on every process
  restart, not just the first time it finishes. Slightly redundant
  tracker chatter (most real clients only announce `COMPLETED` once) but
  harmless - BEP 3 doesn't forbid repeat completion announces, trackers
  only use it for stats.
- If `stop()` is called while a session is still `VERIFYING` (a narrow
  window right after a restart), the background re-hash abandons
  whatever pieces it hasn't reached yet rather than finishing them - they
  fall back to being treated as `NEEDED` and get re-downloaded normally
  later instead of being recognized as already-correct. Not a
  correctness issue, just a missed optimization in a rare, self-inflicted
  window - the same category of accepted race as the
  `updateChoking()`/`stop()` interaction in [[0025-seeding-and-upload-logic]].

**UI disables actions while `VERIFYING`.** Raised by the user, directly
motivated by the abandoned-rehash race noted above: rather than letting a
user pause/resume/remove a torrent whose real state isn't settled yet
(and rely on the backend's already-accepted "just misses an optimization"
handling of that race), the frontend disables all three action buttons
for any torrent in `VERIFYING`, only re-enabling them once it settles to
its real state. Backend behavior for that race is unchanged - this is a
UX guard, not a new server-side rejection, consistent with this being a
single-user self-hosted app rather than a multi-actor system needing a
hard server-side lock.

**Persistence is colocated in each torrent's own download directory, not a
new top-level store.** `TorrentEngine` already marks each directory with
`.grimtorrenter-infohash` ([[0024-name-based-download-directories]]). Two
more markers were added alongside it: `.grimtorrenter.torrent` (the
original `.torrent` file's raw bytes - needed again to re-parse metadata
after a restart, since nothing else keeps them once `addTorrent()`
returns) and `.grimtorrenter-state` (`RUNNING` or `STOPPED`, written
through immediately on every add/pause/resume, not only at clean
shutdown, so a `kill -9` or power loss doesn't lose it). No JSON index,
no database - a directory carrying both the info-hash marker and the
torrent-bytes marker *is* the record of "this torrent should be
restored," which can never drift out of sync with which directories
actually exist.

`TorrentEngine.restore()` scans immediate subdirectories of the base
download directory for that marker pair, and for each one calls
`TorrentSession.restoreAsync()` with the directory it already knows about
- it deliberately does not go through `resolveDownloadDirectory()`, since
collision resolution has no meaning here (the directory is already
claimed for this exact info hash). A directory that fails to restore
(corrupt bytes, no usable tracker, an I/O error) is logged and skipped
rather than aborting the whole scan, so one bad entry can't take down
every other torrent on restart.

**`removeTorrent` gained a `deleteData` flag.** Plain `removeTorrent(hash)`
(unchanged call sites still compile against this overload) deletes only
the two new markers, keeping the info-hash marker in place -
so a downloaded-but-removed torrent's directory is still correctly
recognized as "not free" if the same torrent is ever re-added, matching
the collision semantics `resolveDownloadDirectory()` already had, while
being excluded from `restore()`'s scan (no torrent-bytes marker) and
having no residual desired-state to accidentally resurrect. Confirmed
with the user that this needed an explicit `deleteData=true` variant too,
for actually clearing the downloaded files - that path deletes the whole
directory recursively instead of just the two markers.

**Quarkus wiring**: `TorrentEngine.restore()` is called from a new
`StartupEvent` observer in `TorrentEngineLifecycle`, mirroring the
existing `ShutdownEvent` observer there. It doesn't delay Quarkus startup
on however much re-hashing there is to do - `restore()` itself only does
directory scanning and (fast) session construction; the actual re-hash
runs on each session's own background thread via `restoreAsync()`.
`TorrentResource`'s `DELETE /api/torrents/{infoHash}` gained a
`deleteData` query parameter (default `false`), passed straight through
to the new `removeTorrent(infoHash, deleteData)` overload.

**Frontend "remove" becomes a split button.** Plain "Remove" (the default
action, unchanged) still never deletes files and needs no confirmation,
matching its existing behavior before this feature. The only new,
destructive path - "Remove and delete files" - lives behind the split
button's dropdown and is gated by a PrimeNG `ConfirmDialog` ("this cannot
be undone") before calling `TorrentService.remove(infoHash, true)`. A
plain checkbox-in-dialog design ("remove, optionally also delete files")
was considered and rejected - PrimeNG's `ConfirmationService` only models
a single accept/reject pair, so a third state would need a fully custom
dialog for one checkbox; a split button gets the same two distinct
actions from stock components.

## Addendum: test isolation from the real download directory

**Bug found via manual testing** (2026-08-23): `grimtorrenter-app`'s
`@QuarkusTest`s had always written real torrent directories into
`downloads/` (the config default, no test override existed) - harmless
before `restore()` existed, since a leftover directory just sat there
inertly. Once `restore()` started actively scanning that directory on
every real startup, those leftover test-fixture directories (using
deliberately-unreachable trackers like `http://127.0.0.1:1/announce`,
by design - see `TorrentResourceTest`) got picked up as real torrents to
resume, producing a `ConnectException` warning in the console on every
`quarkus:dev` start. Not a crash - `restore()` already logs and skips a
directory it can't restore, exactly as designed - but confusing console
noise, and a real test-isolation gap regardless.

Fixed with `grimtorrenter-app/src/test/resources/application.properties`
overriding `grimtorrenter.download-directory` to `target/test-downloads`
- already-gitignored build output, cleared by `mvn clean`, and no longer
anywhere `restore()` (or a developer) would ever look during a real run.
`grimtorrenter-app/downloads/` (the real default) was also added to
`.gitignore` - it holds runtime user data, not something to track.

## Testing

`TorrentResourceTest` gained cases confirming the `deleteData` query
parameter's default (`false`) leaves downloaded files in place and
`true` removes them.

`TorrentEngineTest` gained cases for `restore()` (registers immediately
and settles into the persisted running/paused state, both for a torrent
left running and one left paused; skips directories with no torrent-bytes
marker) and for `removeTorrent()` (plain form deletes only the resume
markers and leaves downloaded files alone, and a fresh engine's
`restore()` afterward does not pick it back up; `deleteData=true` deletes
the whole directory).

`TorrentSessionTest` gained three cases against `restoreAsync()`: with
matching data already on disk and `autoStart=true`, the session is
`VERIFYING` immediately on return and reaches `SEEDING` (with a
`COMPLETED` announce sent) once the background re-hash finishes; with
`autoStart=false` it settles to `STOPPED` instead, without ever
announcing to the tracker; with no data on disk at all, it settles and
then behaves exactly like a fresh `create()` (`DOWNLOADING`, piece count
0).

## Alternatives considered

- **Blocking synchronous restore (re-hash before the session is even
  constructed/registered)** - this was the first cut, then explicitly
  reversed after the user weighed in: the priority is showing existing
  torrents as soon as possible rather than making the UI wait on an
  unknown-duration background computation, even if that computation is
  usually fast.
- **Persisted fast-resume bitfield** - rejected in favor of re-hashing;
  see the accepted trade-off above. Would need a defined answer for
  "what if the bitfield says complete but the bytes on disk don't match
  right now" (crash mid-write, file edited out-of-band) that re-hashing
  gets for free.
- **Special-case "already complete before this start()" to skip the extra
  `COMPLETED` announce** - rejected as not worth the branch; see above.
