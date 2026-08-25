# 0014 — Peer wire protocol model and codec

**Status:** Accepted

## Decision

`PeerMessage` is a sealed interface, one record per message type
(`KeepAlive`, `Choke`, `Unchoke`, `Interested`, `NotInterested`, `Have`,
`Bitfield`, `Request`, `Piece`, `Cancel`, `Port`) - forces exhaustive
handling everywhere messages are processed, and keeps each message's
fields (or lack of any) distinct rather than one class with nullable
payload fields for whichever type it happens to be.

`Handshake` is modeled separately from `PeerMessage`, not as a member of
the sealed hierarchy - it has a genuinely different wire shape (fixed
68-byte structure, sent exactly once before the length-prefixed message
stream begins, no message id).

`Port` (BEP 5, DHT listen-port announcement) is included even though
nothing acts on it until Phase 2 DHT (see [[0009-phased-scope]]) - real
peers send it almost universally since DHT is near-ubiquitous, so Phase 1
needs to decode it cleanly rather than treat it as an unknown-message-id
protocol error.

`PeerWireCodec` reads/writes against `InputStream`/`OutputStream`, not
`java.net.Socket` directly - keeps it testable with plain byte streams and
directly reusable by the `peer` layer wrapping a real socket later.

**Two different exception styles are used deliberately, not
inconsistently:**
- I/O failures (connection reset, closed stream) propagate as the
  underlying **checked `IOException`**, unwrapped - callers on the `peer`
  layer need to tell a transient I/O problem (worth retrying/reconnecting)
  apart from protocol corruption, and checked propagation is the natural
  contract for a method doing blocking stream I/O.
- Successfully-read-but-malformed data (wrong protocol name, unknown
  message id, wrong payload length for a fixed-size message) throws the
  **unchecked `PeerWireException`** - consistent with how
  `BencodeException`/`MetainfoException`/`TrackerException` treat malformed
  external input elsewhere in this codebase.

`Bitfield`, `Piece`, and `Handshake` override `equals`/`hashCode` manually
(the `byte[]`-component records pitfall, same as `PieceHashes` in
[[0012-metainfo-parsing]]) since they're sliceable/positional buffers, not
identity values like `InfoHash`/`PeerId`.

`Bitfield.hasPiece(int)` returns `false` for an out-of-range index rather
than throwing - a peer sending a shorter-than-expected bitfield shouldn't
crash the connection over it.

## Alternatives considered

- **Single `PeerMessage` class + type enum + nullable fields** - rejected;
  loses compile-time exhaustiveness and mixes unrelated payload fields
  into one type.
- **Wrap `IOException` in an unchecked type for consistency with the rest
  of the codebase** - rejected; genuine I/O failures and protocol
  corruption need to be distinguishable by callers, and checked
  `IOException` is already the idiomatic contract for stream I/O in Java.
