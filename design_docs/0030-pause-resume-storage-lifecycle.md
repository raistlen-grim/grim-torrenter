# 0030 — Pause/resume must not close storage

**Status:** Accepted

## Decision

**Bug found via real manual testing** (pause then resume a real,
actively-downloading torrent): every piece received after resuming failed
with `ClosedChannelException` from `TorrentStorage.write()`, and the
session immediately went to `ERROR`.

**Root cause**: `TorrentSession.stop()` - used for both "pause" (via
`TorrentEngine.pauseTorrent`) and genuine teardown (removal, process
shutdown) - unconditionally closed `storage` inside `shutdownInternal()`.
`storage` is a `final TorrentStorage` set once at construction with no
reopen path, so once closed it's permanently unusable. `start()` never
recreates it. A paused-then-resumed session went back to `DOWNLOADING`
with fully torn-down storage - not a race, a guaranteed failure on the
very next read or write.

Not a new bug from this session's work - `TorrentEngine`'s own class
Javadoc already claimed "why 'pause'/'resume' need no new `TorrentSession`
logic" ([[0018-torrent-engine]]), which was true for state/scheduling/
connections but turned out false for storage. It surfaced now because
real usage finally exercised pause-then-continue-downloading on a live
swarm, which none of the existing automated tests happened to do (they
checked state transitions around stop()/start(), not continued data flow
afterward).

**Fix: split "stop" from "permanently done."**
- `stop()` now only tears down networking (peer connections, the
  scheduler) and announces `STOPPED` to the tracker - storage stays open.
  This is what `pauseTorrent()` calls; a resumed session's storage is
  exactly as usable as it was before pausing.
- `close()` (the `AutoCloseable` override) calls `stop()` and *then*
  releases storage - for when the session is never coming back:
  `TorrentEngine.removeTorrent()` and `TorrentEngine.shutdown()` now call
  `close()` instead of `stop()`. Tests using try-with-resources on a
  `TorrentSession` get the same "fully done" semantics for free.
- `fail()` handles its own teardown (`shutdownNetworking()` +
  `closeStorage()`) rather than calling `close()`/`stop()` - `stop()`
  would re-announce `STOPPED` and, worse, overwrite the `ERROR` state
  `fail()` just set. `ERROR` is terminal anyway (`start()` only ever
  resumes from `STOPPED`), so releasing storage there is correct, not
  just convenient.

A side benefit: the already-documented "abandoned mid-verify" race in
[[0026-resume-state-persistence]] (`stop()` called while a restored
session is still `VERIFYING`) is now strictly safer too - the background
re-hash's in-flight `storage.read()` can no longer race a `storage.close()`
from a plain pause, since pausing no longer closes storage at all.

## Testing

`TorrentSessionTest` gained `pausingAndResumingKeepsStorageUsable` - a
real two-piece download where the session receives piece 0, is paused,
resumed, reconnects (a fresh `PeerConnection`, since pausing does close
connections), and receives piece 1 - reaching `SEEDING` and writing both
pieces' bytes to disk. This exercises actual post-resume I/O, which is
exactly what every prior stop()/start() test omitted.

## Update: storage no longer holds its own permanent channels at all

[[0047-bounded-file-handle-pool]] replaced `TorrentStorage`'s "open every file once at
construction, hold forever" model with a shared, bounded pool that every `read()`/`write()`
call borrows a channel from on demand. The specific fix described above (don't close storage
on a mere pause) still stands and is still what `stop()` does - there's no benefit to evicting
a torrent's files just because it's paused, since resuming will likely touch them again soon.
But the underlying hazard this doc fixed - "closed, with no reopen path" - no longer exists as
a *possible* state at all: every access is now a potential cache miss that transparently
reopens, so even a hypothetical future code path that did evict on pause would no longer be
able to reproduce this bug. See 0047 for the full design.

## Alternatives considered

- **Make `TorrentStorage` reopenable** (add a method to reopen closed
  `FileChannel`s) - rejected: more moving parts than simply not closing
  it in the first place for a pause, and `TorrentStorage` staying
  "open or permanently done" (no third "reopened" state) is easier to
  reason about.
- **Have `pauseTorrent()` call something other than `stop()`** entirely
  (a new `TorrentSession.pause()` method) - rejected as unnecessary once
  `stop()` itself does the right thing; no need for a second method name
  for the same behavior.
