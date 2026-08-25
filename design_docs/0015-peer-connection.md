# 0015 — PeerConnection

**Status:** Accepted

## Decision

`PeerConnection` wraps one connected `Socket`. `PeerConnection.connect(...)`
is a static factory (not constructor-driven blocking I/O): it opens the
TCP connection, performs the handshake, validates the returned info hash
matches what was expected, then starts the read loop and returns a fully
running connection.

- **The read loop runs on a dedicated virtual thread**
  (`Thread.ofVirtual()...start(...)`) — this is the first place
  [[0007-concurrency-model]] is actually implemented, not just decided.
  Blocking-style reads via `PeerWireCodec` run directly on that thread.
- **Writes are serialized** under a private `writeLock` monitor so
  multiple callers sending messages concurrently (e.g. a future piece
  manager sending `Request`s while something else sends a `Have`) can't
  interleave bytes on the wire.
- **State is bidirectional from Phase 1** per [[0008-seeding-design-considerations]]:
  all four choke/interest flags, the peer's known piece bitset (`BitSet`,
  updated from `Bitfield`/`Have` messages), and `sendPiece(...)` all exist
  now even though nothing drives `am_choking` to `false` or calls
  `sendPiece` for real until Phase 2's choking algorithm exists.
- **Timeouts**: 10s to connect + complete the handshake; a 120s idle read
  timeout once the connection is established (matches BEP's recommended
  keep-alive cadence — a silent connection past that is treated as dead).
  Most tracker-provided peer addresses are unreachable, so failing fast
  here matters.
- **No self-scheduled keep-alives.** `sendKeepAlive()` is exposed as a
  plain send primitive; ticking it periodically across idle connections is
  an orchestration concern for the future `TorrentSession`, not this
  class's job — adding a scheduler here would be reaching into a layer
  above what "wraps a connection, runs the codec, holds per-peer state"
  should own.
- **Disconnect notification is unified**: both a local `close()` and a
  read/write I/O failure funnel through the same `disconnect(Throwable
  cause)` path, `cause == null` meaning an intentional local close.
  `disconnect` is `synchronized` and idempotent (checks `closed` under the
  lock) so a close racing with a concurrent read/write failure can't
  double-notify the listener.
- **`pendingRequestsSnapshot()`** exposes in-flight block requests so a
  future piece manager can requeue them elsewhere after a disconnect,
  rather than losing track of what was outstanding.
- **Only outbound connections are supported** (`connect(...)` to a
  tracker-discovered `PeerAddress`). Accepting inbound connections is a
  Phase 2/seeding concern — see [[0009-phased-scope]] — and isn't built
  here.

## Testing

`PeerConnectionTest` drives a raw `ServerSocket` as a fake remote peer,
performing handshake/message I/O manually with `PeerWireCodec` (already
covered by its own tests in [[0014-peerwire-protocol]]) so this suite
tests `PeerConnection`'s behavior specifically: handshake success/failure,
state updates from incoming messages, local state updates + wire output
from `send*` calls, disconnect notification on peer-initiated close, and
close() idempotency.

## Alternatives considered

- **Netty-based connection handling** — rejected in
  [[0007-concurrency-model]] already; this class is the concrete result of
  that decision.
- **Self-scheduled keep-alive inside PeerConnection** — rejected; belongs
  to the orchestration layer that manages many connections at once.
