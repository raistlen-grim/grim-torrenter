# 0082 - Force recheck and force reannounce

Status: Accepted (2026-10-01). Builds on [[0026-resume-state-persistence]] (the restore-time
verification pass) and [[0022-multi-tracker-fallback]]/[[0036-dht-backstop-for-tracker-bearing-torrents]]
(the scheduled announce and DHT discovery).

## Problem

Two manual per-torrent actions an established client has and this one didn't (TODO.md's
2026-09-19 missing-feature review):

- **Recheck** only ever happened automatically at restart. If data changed on disk behind the
  engine's back (a file replaced, restored from backup, damaged), the only way to make the
  engine notice was to restart the whole app.
- **Reannounce** only happened on each tracker's own interval. After fixing a network problem, or
  when a torrent has no peers, there was no way to ask for peers now.

## Decision

### Force recheck

`TorrentSession.recheck()`:

1. Does nothing (returns false) if a verification pass is already running.
2. Remembers whether the torrent should run afterwards: yes for any state other than `STOPPED`.
3. `stop()` - peers are disconnected and `STOPPED` is announced in the background
   ([[0081-background-stopped-announce]]). A recheck with peers still writing blocks would be
   verifying a moving target.
4. `PieceManager.resetCompletion()` - forgets every completed piece and received block.
5. Enters `VERIFYING` and runs the same `verifyThenSettle()` pass `restoreAsync()` uses, on its
   own virtual thread, through the same shared `pieceVerificationLimiter`
   ([[0048-piece-verification-throttling]]).
6. Settles to `STOPPED`, then `start()`s if step 2 said so. A torrent whose data is intact ends
   up `SEEDING`/`DOWNLOADING` exactly as before; one with damage goes to `DOWNLOADING` for the
   pieces that failed.

It reuses the existing `VERIFYING` state rather than adding a "rechecking" one: every consumer
(the row's status, disabled pause/resume/remove, progress climbing from 0) already handles it, and
to a client the two are the same thing ([[0071-thin-frontend-as-a-standing-consideration]]).

The engine's persisted running/stopped marker is not touched, so a restart mid-recheck simply
re-verifies and resumes as it would have anyway.

**Superseded passes.** A verification pass abandons itself when the state leaves `VERIFYING`.
With recheck that is no longer enough: pass A abandoned by a pause, followed by a second recheck
starting pass B, would let A wake up, see `VERIFYING` again and carry on from the middle - and
possibly settle the session before B had finished. Each pass now carries a run number
(`verificationRun`, bumped by every `recheck()`) and stops as soon as it is no longer the
current one.

### Force reannounce

`TorrentSession.reannounceNow()` runs the ordinary scheduled `reannounce()` once, on a virtual
thread, and kicks `discoverPeersViaDht()` as well (itself already asynchronous and a no-op for a
private torrent or with DHT off). It does not reset the regular schedule. Refused (false) unless
the torrent is `DOWNLOADING` or `SEEDING`, and while a previous forced reannounce is still in
flight - repeated clicks can't stack announces. There is no further cooldown: one announce at a
time, each as slow as the slowest tracker, is already a low ceiling.

### REST

- `POST /api/torrents/{infoHash}/recheck` - 200 with the torrent's `TorrentView` as it now is
  (normally `VERIFYING`), the real resource rather than an acknowledgement. Repeating it while a
  pass is running changes nothing and returns the same. 404 unknown; 409 for a magnet still
  fetching metadata.
- `POST /api/torrents/{infoHash}/reannounce` - 204 once the announce has been started (not when
  it finishes; the outcome shows on the existing Trackers endpoint). 404 unknown; 409 unless
  downloading or seeding.

Both return immediately, so neither holds a worker thread.

### Frontend

Two context-menu items, "Force reannounce" and "Force recheck", in both the row menu and the
details panel's menu, via `TorrentActionsService`. Recheck applies the returned torrent straight
away so the row shows Verifying without waiting for a snapshot; reannounce changes nothing visible
in the row, so it gets an info toast. Reannounce is disabled unless the torrent is running;
recheck is disabled while verifying, while another action is pending, or for a pending magnet.
No confirmation dialog for recheck - it interrupts the torrent but destroys nothing.

## Known limits

- **Pausing mid-recheck** (possible through the API; the UI disables pause while verifying)
  abandons the pass, as at restore time. Pieces not yet re-verified count as missing and would be
  downloaded again on resume. Rechecking again, or a restart, puts it right.
- **A file that no longer exists** makes the pass fail with an I/O error and the torrent goes to
  `ERROR`: files are only created when the session is, not on demand. A restart recreates them.
- **No new `COMPLETED` event** when a rechecked torrent finishes again, and `completedAtEpochMillis`
  keeps its original value - the same suppression that stops a restart re-recording completion
  ([[0055-library-events]]).
- Failed pieces found by a recheck are not added to wasted bytes, same as restore-time
  verification ([[0066-peer-diagnostics]]).
- Partially downloaded pieces (some blocks received) are discarded by the reset and fetched again.

## Alternatives considered

- **Verify without disconnecting peers** - rejected; blocks arriving during the pass would race
  the reset and the per-piece verify, for the sake of a few seconds of transfer.
- **A new `RECHECKING` state** - rejected, see above.
- **A synchronous recheck request** that returns when verification finishes - rejected; a large
  torrent takes minutes, exactly the long-held-request shape 0081 just removed.
- **Reannounce that also resets the tracker schedule** - not needed; an extra announce between
  scheduled ones is harmless.

## Stability ([[0051-stability-as-a-standing-consideration]])

- Threads: one virtual thread per recheck pass and one per forced reannounce; a second recheck is
  refused while one runs, a second reannounce while one is in flight. A superseded pass exits at
  its next piece boundary.
- Memory/CPU/disk: a pass reads and hashes one piece at a time under the engine-wide verification
  limiter, so rechecking many torrents at once is bounded exactly as a restart is. File handles
  come from the shared pool ([[0047-bounded-file-handle-pool]]).
- Hostile angle: both endpoints sit behind the same authentication as everything else. A caller
  hammering reannounce gets at most one announce at a time; hammering recheck gets one pass.
- Locking: `recheck()` is `synchronized` like `start()`/`stop()`, held only for the state change
  (the pass itself runs outside it). Not a hot path ([[0007-concurrency-model]]).
  `PieceManager.resetCompletion()` uses the existing `ReentrantLock`.
- Exit paths: the in-flight flag for reannounce is cleared in `finally`.

## Tests

`TorrentSessionTest`: intact data returns to `SEEDING` via `VERIFYING`; corrupted data is noticed
and the torrent returns to `DOWNLOADING`; a paused torrent is rechecked and stays paused without
announcing; a forced reannounce sends an event-less announce at once and is refused when not
running. `TorrentResourceTest`: recheck returns the torrent and is repeatable, reannounce is 409
for a torrent that isn't running, both are 404 for an unknown torrent. Not covered: the
superseded-pass guard, the in-flight reannounce guard, and the frontend menu items.
