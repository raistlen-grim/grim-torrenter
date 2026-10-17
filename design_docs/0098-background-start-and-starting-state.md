# 0098 - Adding or resuming a torrent doesn't wait for the tracker

Status: Accepted (2026-10-17). Resolves the "upload latency" known gap (PROGRESS.md, TODO.md
Beta readiness). Builds on [[0092-resource-bounds-and-lock-hold-times]] (start()'s announce
outside the session lock) and revises [[0097-pause-resume-return-the-torrent]]. Applies
[[0051-stability-as-a-standing-consideration]] and
[[0071-thin-frontend-as-a-standing-consideration]].

## Problem

`TorrentSession.start()` returns only after its first tracker announce, and any DHT fallback,
has answered. `TorrentEngine.addTorrent()` and `resumeTorrent()` called it inline, so an upload,
a watch-folder add, a resolved magnet or a resume held its caller for that whole time. With a
15 s HTTP timeout per tracker tier plus the DHT fallback, a torrent with dead trackers could hold
the request for most of a minute. 0092 moved the announce out of the session lock, but not out
of the request.

Simply running `start()` on a thread wasn't enough. While the announce was in flight the session
was still STOPPED, so the UI would show "Paused" for a torrent the user had just added or
resumed: a lie the frontend would have to paper over (0071).

## Decision

- **New `TorrentState.STARTING`**: a start from STOPPED whose first announce is in flight. It
  becomes DOWNLOADING/SEEDING when a tracker or DHT answers, or the retryable ERROR when nothing
  did, as before.
  - A retry from that retryable ERROR (the background retry, or "retry now" after a proxy change)
    stays ERROR while it runs, so the row doesn't flicker Error → Starting → Error every backoff,
    and the reason stays visible.
- **`start()` is split** into `beginStart()` (locked: guards, generation bump, → STARTING) and
  `finishStart()` (the unlocked announce, then the locked outcome).
  - `start()` runs both inline, unchanged for every existing caller: restore, recheck, the
    retry loop.
  - The new `startInBackground()` runs `beginStart()` on the caller's thread, so the session is
    STARTING when it returns, and `finishStart()` on a virtual thread.
- **`addTorrent()` and `resumeTorrent()` use `startInBackground()`.** An add or resume now
  returns at once with the torrent STARTING. `POST .../resume` (0097) therefore answers STARTING,
  not the outcome of the announce; the outcome arrives through the normal snapshots.
- **`stop()` during STARTING** goes straight to STOPPED, with no STOPPED announce of its own. The
  generation bump makes `finishStart()` discard the outcome, and if the tracker had accepted the
  STARTED, `finishStart()` sends the STOPPED, exactly as 0092 already did for a stop during a
  synchronous start.
  - **A resume straight after such a pause starts at once.** The old `startInProgress` flag stayed
    set until the cancelled announce came back, so the resume was silently refused and the torrent
    stayed paused (caught by the tests). The flag is replaced by `inFlightStartGeneration`: a
    start in flight only blocks another while its generation is still current, and a stop makes
    it stale immediately. `recheck()`'s "was it running?" check uses the same test.
  - **The cancelled start doesn't send its STOPPED if a newer start has begun** (STARTING,
    DOWNLOADING or SEEDING, or a retry in flight). That STOPPED could land after the newer
    STARTED and make the tracker drop us; a second STARTED is harmless. With no newer start, the
    STOPPED goes out as before, and the next start waits for it (0081).
- **`fail()` also bumps the generation**, so a start still announcing can't bring a failed
  session, whose storage has just been closed, back to DOWNLOADING.
- **An interrupted `finishStart()`** with no outcome puts a still-STARTING session back to
  STOPPED rather than leaving it STARTING forever.
- **Frontend:** STARTING shows as "Starting" with a spinner. Pause is offered for it (row,
  detail, selection bar, Pause all, Enter), since pausing cancels the start. It isn't counted as
  "running" anywhere that needs peers: force reannounce, the Downloading/Seeding filters.

Every other STARTING-unaware check (`state != DOWNLOADING && state != SEEDING` for choking, PEX,
LSD, reannounce, health) already treats it like STOPPED, which is right: there are no peers yet.

## Alternatives considered

- **Return STOPPED and let the client show a pending state:** the frontend would have to invent
  and reconcile a state the server doesn't have, the opposite of 0071.
- **Make `start()` itself asynchronous:** every caller and dozens of tests assume `start()` has
  finished when it returns. The split keeps that contract and adds the background variant only
  where a request is waiting.

## Stability

- One virtual thread per background start, ending when the announce does: bounded by the
  tracker clients' own timeouts plus the DHT query timeout, like the synchronous start it
  replaces. A second start while a current one is in flight is refused, so a resume hammered
  repeatedly can't stack threads. Pause/resume cycles can: each pause makes the in-flight start
  stale, and the next resume begins another. Each stale thread still ends with its announce, so
  the count is bounded by request rate times the tracker timeout, one REST round trip per cycle.
- Lock hold times are unchanged from 0092: the announce never runs under the session lock.
- Cleanup on every exit path: the outcome is applied, discarded (with a STOPPED announce if
  needed), or, if interrupted, the session returns to STOPPED.
