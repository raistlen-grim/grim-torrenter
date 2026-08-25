# 0016 — Piece management and storage

**Status:** Accepted

## Decision

**`piece` and `storage` are independent siblings — neither depends on the
other.** While building this slice, the original [[0006-engine-layering]]
list implied piece depends on storage (verifying a piece needs its bytes),
but that dependency isn't necessary: `PieceManager` never touches
`TorrentStorage`. Instead:
- `PieceManager.verify(pieceIndex, actualBytes)` takes the bytes to hash as
  a parameter rather than reading them itself.
- `PieceManager` exposes `pieceOffset`/`pieceLength`/`blockOffsetWithinPiece`/
  `blockLength`/`globalOffset` so a caller can compute exactly what to read
  from or write to `TorrentStorage` (or send in a wire `Request`) without
  `PieceManager` needing a storage reference.

The future `torrent`/`TorrentSession` layer is what wires the two
together: read blocks from storage, hand them to `PieceManager.verify`,
write incoming blocks to storage, tell `PieceManager.markBlockReceived`.
This keeps both independently unit-testable (no temp files needed to test
piece-selection logic; no piece concepts needed to test file-splitting
logic) confirmed with the user rather than assumed.

### PieceManager

- **Block size is a fixed constant** (16 KiB, `PieceManager.BLOCK_SIZE`),
  not configurable — de facto universal in the BitTorrent ecosystem; many
  peers reject/ignore other sizes.
- **Piece selection strategy is pluggable** (`PieceSelectionStrategy`
  interface), matching the Phase 1 (sequential) vs. later (rarest-first)
  plan in [[0009-phased-scope]]. `SequentialPieceSelectionStrategy` is the
  Phase 1 implementation.
- **`peerHasPiece` is a plain `IntPredicate`**, not a `peer.PeerConnection`
  reference — keeps `piece` free of any dependency on `peer`, and keeps
  selection strategies testable without constructing a real connection.
- **No cross-peer in-flight-request tracking.** Two different peers could
  in principle both be asked for the same block since `PieceManager` only
  tracks "received," not "requested." This is a bandwidth optimization
  ("endgame mode" in most real clients), not a correctness issue, and is
  deferred — not built here.
- All mutating/reading methods touching `blockReceived`/`completedPieces`
  are `synchronized`; the pure arithmetic methods (`pieceOffset`,
  `pieceLength`, etc.) aren't, since they only read immutable
  construction-time fields.

### TorrentStorage

- **Pre-allocates each file to its final size** via
  `RandomAccessFile.setLength(...)` at creation (sparse on most
  filesystems) — necessary because blocks arrive out of order from
  different peers, not sequentially, so every offset must be writable
  immediately.
- **A write or read can span multiple files** (BitTorrent block boundaries
  don't align with file boundaries in multi-file torrents) — both methods
  loop across as many `FileSlice`s as the requested range touches.
- **Relies on `FileChannel`'s positional read/write** (the
  position-taking overloads, not the stream-style ones) for safe
  concurrent access from multiple threads without extra locking in this
  class — multiple peer connections will be delivering blocks
  concurrently once the orchestration layer exists.
- **`close()` attempts to close every file channel even if one fails**,
  collecting later failures as suppressed exceptions on the first, rather
  than aborting after the first failure and leaking the rest.

## Alternatives considered

- **`PieceManager` holds a `TorrentStorage` reference and does I/O
  itself** — considered and explicitly rejected (confirmed with the user)
  in favor of the decoupled design above.
- **Track in-flight requests globally across peers in `PieceManager`** —
  deferred; not needed for Phase 1 correctness, only for bandwidth
  efficiency.
