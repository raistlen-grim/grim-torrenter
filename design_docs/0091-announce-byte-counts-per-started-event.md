# 0091 - Announce byte counts are per STARTED event

Status: Accepted (2026-10-17). Found in the pre-release engine review
(`code_review/01-engine.md`, item A4). Narrows what the announce fields mean; the
`bytesUploaded()` accumulator from [[0025-seeding-and-upload-logic]] and the lifetime
stats from [[0064-persistent-lifetime-stats]] are unchanged.

## Problem

BEP 3's `uploaded` and `downloaded` announce fields count from the `started` event. Trackers,
and private trackers in particular because they keep ratio accounts, credit the change between
one announce and the next. We sent:

- `downloaded = bytesDownloaded()`: every verified piece **on disk**, including everything
  restored at startup. After a restart of a complete 50 GB seed, the STARTED announce said 0
  and the first regular announce said 50 GB. On every restart, a private tracker charged the
  user for a 50 GB download they never made.
- `uploaded = bytesUploaded()`, which accumulates across pause/resume within one process. A
  resume (a fresh STARTED) re-reported everything uploaded before the pause.
- `event=completed` whenever a session went DOWNLOADING → SEEDING. That includes every restore
  or resume of a torrent that was already complete, so each restart counted as another finished
  download (a "snatch").

## Decision

- **`uploaded` = `bytesUploaded()` minus its value at the last `start()`.**
- **`downloaded` = bytes of pieces verified by *live download* since the last `start()`.**
  Restore and recheck verification never count. A piece that fails its hash doesn't count
  either; that is already tracked separately as wasted bytes ([[0066-peer-diagnostics]]).
- Both reset at every `start()`: first start, resume after a pause, and a retried start from
  the retryable ERROR state. STARTED itself always sends 0 for both.
- **`event=completed` is sent only when this run downloaded something**
  (`downloaded > 0`). A torrent that was already complete, or whose wanted set became complete
  without any new data (for example after file priorities were narrowed), moves to SEEDING
  without announcing it. Its regular announces carry `left=0`, which is all a tracker needs to
  list it as a seeder.

Everything the UI and the seeding limits read is unchanged (`bytesUploaded()`,
`bytesDownloaded()`, lifetime stats). Only the three tracker announce sites changed
(`reannounce()`, `stop()`, `checkForCompletion()`), plus `start()`, which now sends 0.

## Stability

Adds two counters to each session (a volatile long and an AtomicLong). No new threads, state or
files. Concurrency: the download counter is updated from peer read loops, so it's atomic. The
upload baseline is written only in `start()`, which holds the session lock, and read with a
volatile read. No stability implication beyond that.

## Alternatives considered

- **Report `bytesReceived()` (raw wire bytes) as `downloaded`.** It's closer to what some
  clients send, but it includes data from pieces that fail their hash and duplicate endgame
  blocks. That inflates the user's download count on private trackers, which is the exact harm
  this decision fixes. Verified bytes are the conservative choice.
- **Persist the counters across restarts.** Not needed: a restart sends a fresh STARTED, and
  trackers start a new count from there.
