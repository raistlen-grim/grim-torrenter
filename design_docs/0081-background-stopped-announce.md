# 0081 - Background STOPPED announce

Status: Accepted (2026-10-01). Revises the synchronous-announce part of `stop()` in
[[0017-torrent-session]]; `start()`'s synchronous first announce is unchanged.

## Problem

`TorrentSession.stop()` announced `STOPPED` to the tracker(s) before returning. The announce is
best-effort (a failure was already ignored), but it takes as long as the slowest tracker's
timeout - with a dead UDP tracker in the list, most of a minute. Everything that stops a session
paid for it:

- **Pause** - `POST /api/torrents/{h}/pause` held its worker thread, and the UI its spinner, for
  that long.
- **Remove** - the same, via `close()`.
- **Engine shutdown** - `TorrentEngine.shutdown()` closes sessions one after another, so the cost
  was the sum over every running torrent. Seen in dev mode as a ~10 s live reload during which
  requests queued up and were answered `503` (worker pool exhausted) and the WebSocket dropped.
  In a container it risks running past the runtime's stop grace period (10 s by default for
  `docker stop`) and being killed mid-shutdown.

## Decision

- `stop()` does everything local first - captures the announce totals, sets `STOPPED`, shuts down
  networking - then starts the `STOPPED` announce on its own virtual thread and returns.
- **Ordering with a following start.** `start()` joins that thread before sending `STARTED`, so a
  quick pause-then-resume can't reach the tracker as `STARTED` then `STOPPED` (which would leave
  us running but dropped from the tracker's peer list until the next reannounce). `start()` was
  already synchronous on a tracker announce, so this adds no new kind of wait - only, at worst,
  the remainder of one announce.
- **Shutdown.** Closing every session now only *starts* the announces, so they run concurrently.
  `shutdown()` then waits for them for one shared window of 3 seconds
  (`SHUTDOWN_ANNOUNCE_GRACE`) and proceeds; any still in flight end with the process. Shutdown
  time is now bounded by that constant rather than by tracker count x torrent count.
- `TorrentSession.awaitStoppedAnnounce(Duration)` is the one new public method, used by
  `shutdown()` and by tests.

## Accepted costs

- A tracker that takes longer than 3 s to answer during shutdown may not hear `STOPPED`; it drops
  us on its own announce-interval timeout instead. Previously shutdown would wait however long it
  took.
- A pause's REST response no longer implies the tracker was told. Nothing depended on that: the
  outcome was never reported and a failure was already swallowed.
- `removeTorrent()` doesn't wait at all; the announce finishes in the background after the
  torrent is gone.

## Alternatives considered

- **Make the pause endpoint asynchronous at the REST layer** (return 202, run `stop()` on another
  thread) - rejected; it would leave shutdown and removal slow, and the UI would have to track
  when the pause "really" finished, against [[0071-thin-frontend-as-a-standing-consideration]].
- **A shorter tracker timeout for `STOPPED` only** - rejected; still serial during shutdown, and
  needs a timeout parameter threaded through every `TrackerClient`.
- **Also making `start()` asynchronous** - not done here. Resume and add still wait on the first
  announce; that is the larger change already listed in PROGRESS.md's known gaps.
- **Cancelling an in-flight `STOPPED` when `start()` is called** instead of waiting for it -
  rejected; the tracker clients aren't written to be interrupted mid-announce, and a cancelled
  request may still have reached some trackers.

## Stability ([[0051-stability-as-a-standing-consideration]])

- Threads: at most one background announce thread per session at a time in practice - a second
  `stop()` is a no-op while `STOPPED`, and `start()` joins the previous one before the session can
  be stopped again. Virtual threads, each bounded by the tracker clients' own timeouts.
- Cleanup on every exit path: the thread only calls `announce()` and swallows its failure; it
  holds no locks, sockets or file handles of the session's own. A removed torrent's session
  object stays reachable until its announce returns, then is collected.
- Hostile/slow tracker: can no longer stall a pause, a removal or shutdown; it can still delay a
  resume issued while the previous `STOPPED` is in flight, bounded as above.
- Concurrency: no new `synchronized`. `start()` holds the session monitor while joining, as it
  already did while announcing ([[0007-concurrency-model]] - not a hot path).

## Frontend

None. Pause simply returns quickly ([[0071-thin-frontend-as-a-standing-consideration]]).

## Tests

`TorrentSessionTest`: `stopReturnsWithoutWaitingForTheStoppedAnnounce` (a tracker that blocks on
`STOPPED` no longer blocks `stop()`), `startWaitsForAPendingStoppedAnnounceSoTheTrackerSeesThemInOrder`,
and two existing cases updated to wait for the background announce before counting announces.
No test covers the 3 s shutdown window.
