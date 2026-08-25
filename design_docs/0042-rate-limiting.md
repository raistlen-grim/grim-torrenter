# 0042 — Upload/download rate limiting

**Status:** Accepted

## Decision

The next Phase 3 item ([[0009-phased-scope]]) and the first real consumer of
[[0041-live-settings-store]]. Two new `Settings` fields (`uploadRateLimitBytesPerSec`,
`downloadRateLimitBytesPerSec`, both `long`, `0` meaning unlimited) plus the engine-side
enforcement.

**Two scoping questions confirmed with the user before implementing:**

- **Global limits only, not per-torrent.** One upload cap and one download cap shared
  across every torrent's combined traffic - the simplest, most common mode for a
  self-hosted client. Per-torrent overrides would be a natural later addition on top of
  this, not something this needed to include to be useful.
- **Enforcement point: a shared token bucket each `PeerConnection` blocks on**, not a
  socket-level byte-rate mechanism or a request-scheduling scheme. Fits the engine's
  existing blocking-I/O-per-virtual-thread model ([[0007-concurrency-model]]) with no
  rewrite - just a wait point before/after the bytes that actually matter.

### `RateLimiter` (new `ratelimit` package)

A token bucket, `acquire(int bytes)` blocking (in short increments) until enough budget is
available. Reads its limit from `SettingsStore.current()` on **every** call rather than
caching it - a live settings change takes effect on the very next `acquire()`, no reload
step, consistent with [[0041-live-settings-store]]'s "the store is the only holder of a
Settings instance" rule. Steady-state bucket capacity is one second's worth of the current
limit - no separate burst-allowance setting in v1.

**Real bug caught by the end-to-end test, not the unit tests**: capacity was originally
capped at the steady-state limit unconditionally, so a single `acquire(bytes)` call for
more than that could never succeed - `availableTokens` would top out at, say, 500 (a
500 B/s limit) and just sit there forever, since a real BitTorrent block can be up to
16 KiB (`PieceManager.BLOCK_SIZE`), already bigger than one second's worth of any limit
under 16 KiB/s. `RateLimiterTest`'s own cases happened to always request exactly the
bucket's capacity, so they passed against the buggy version; only
`downloadRateLimitActuallyThrottlesARealTransfer` (1000 bytes against a 500 B/s limit)
caught it, by timing out waiting for the transfer to ever complete. Fixed by letting a
single request temporarily widen the cap to fit itself (`Math.max(limit,
pendingRequestBytes)`) - the refill *rate* is still governed purely by the limit either
way, so an over-capacity request still takes proportionally longer, it just isn't stuck
forever waiting for a bucket that can structurally never hold enough.

`RateLimiters` (upload + download pair) bundles
both directions into the one object actually threaded through the engine, plus an
`unlimited()` factory backed by its own private `InMemorySettingsStore` (new, also
generally useful anywhere a `SettingsStore` is needed without real persistence - e.g.
tests) for every caller that doesn't care about limiting.

**Only real piece payload is throttled** - `PeerConnection.sendPiece()` (upload, before
writing) and the `Piece` case in `applyIncoming()` (download, after recording the bytes,
before the read loop moves on) - not handshakes, keepalives, or other protocol chatter,
matching how `bytesUploaded()`/`downloadedBytes()` already only count payload too.
Download throttling works by slowing down *our own* read loop rather than reading fewer
raw socket bytes per interval - once we stop reading, TCP flow control naturally backs the
remote sender off, achieving the same effect without needing raw socket-level rate control.

### Threading `RateLimiters` through without touching a single existing test

The exact same pattern already used for `TorrentEngine`'s `enableDht`/
`acceptIncomingConnections` constructor additions: **every existing signature is kept
exactly as it was**, delegating to a new sibling overload that takes a `RateLimiters` (or,
at the `TorrentEngine` level, a `SettingsStore` it builds one from). Only the actual
production wiring inside `TorrentEngine` (`addTorrent()`'s two branches, `restoreOne()`)
and `TorrentEngineProducer` were changed to use the new overloads with a real, live value.
`TorrentSession.create()`/`restoreAsync()`, `PeerConnection.connect()`/`accept()` all gained
a `RateLimiters`-accepting sibling overload alongside their existing one - meaning **zero**
of the several dozen existing call sites across `TorrentSessionTest`, `PeerConnectionTest`,
`TorrentEngineTest`, or `MetadataFetcher` (which never transfers real piece data, so
`RateLimiters.unlimited()` is also just correct for it, not merely convenient) needed to
change at all.

`TorrentEngine` owns the one `RateLimiters` instance for its whole lifetime, built from
whatever `SettingsStore` it was constructed with (a real `JsonSettingsStore` in
production, an `InMemorySettingsStore` default for every lower-arity/test constructor),
and passes it down into every `TorrentSession` it creates, which passes it down into every
`PeerConnection` it makes - one shared pair of limiters for the whole engine, not one per
session or connection, matching the "global, not per-torrent" scoping decision.

## Testing

- `RateLimiterTest` (new) - unlimited (`0`) returns immediately even for a huge request;
  a real limit blocks for a generous, flake-resistant lower bound of the expected duration
  (not a tight upper bound); a live settings change (via `InMemorySettingsStore.update()`)
  takes effect on the very next `acquire()` call.
- `TorrentSessionTest` gained `downloadRateLimitActuallyThrottlesARealTransfer` - a real
  fake-peer download of a 1000-byte piece capped at 500 bytes/sec, asserting the whole
  transfer took at least 1.5s (vs. the low-single-digit-ms an unthrottled loopback transfer
  would take) - proves the wiring end-to-end through a real `PeerConnection`, not just the
  `RateLimiter` class in isolation.

## Not built in this pass

**No REST endpoint or frontend UI to actually set the rate limit from the app** - a user
can currently only change it by hand-editing `settings.json` in the config directory
(design_docs/0041). The engine-side enforcement and the live-settings plumbing are both
fully built and working; exposing a way to change it without editing a file is a natural,
separate next step, not bundled into this one.

## Alternatives considered

- **Per-torrent limits alongside the global one** - deferred; see the scoping decision
  above.
- **Socket-level byte throttling** (limiting how many raw bytes are read off the socket
  per interval) instead of blocking the read loop after a full `Piece` message - rejected
  as unnecessary complexity for v1; blocking between messages already achieves the same
  practical effect (the remote sender gets backed off via TCP flow control once we stop
  reading) without needing to partially read messages mid-stream.
- **Changing every `TorrentSession`/`PeerConnection` factory signature in place** instead
  of adding sibling overloads - rejected; would have required updating dozens of existing
  test call sites for a mechanical, no-behavior-change addition, exactly the kind of
  blast radius the existing `enableDht`/`acceptIncomingConnections` precedent already
  established a working alternative to.
