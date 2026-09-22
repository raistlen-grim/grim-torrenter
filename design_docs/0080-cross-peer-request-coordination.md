# 0080 - Cross-peer request coordination and adaptive request pipeline

Status: Accepted (2026-09-21). Revises the "no cross-peer coordination" part of
[[0016-piece-management-and-storage]].

## Problem

Real report: a magnet with dozens of available seeders downloaded from one peer at ~18 kB/s
in this app, while qBittorrent pulled ~3 MB/s from several. Three causes in the request path:

1. **Every peer was asked for the same blocks.** `SequentialPieceSelectionStrategy` returns the
   lowest incomplete piece a peer has, and `PieceManager` only knew which blocks were
   *received*, not *requested*. Each connection cross-checked only its *own* pending requests,
   so N connected peers all requested the same first few blocks; the first reply won and the rest
   was wasted. Effective throughput was about one peer's pipeline, however many were connected.
2. **Fixed pipeline of 5 blocks (80 KiB) per peer.** Per-peer throughput is capped at roughly
   80 KiB / RTT - ~1.6 MB/s at 50 ms, ~400 KB/s at 200 ms.
3. **Requests were never forgotten on choke.** BEP 3: a choke discards the peer's unanswered
   requests. `PeerConnection.pendingRequests` kept them, so after one choke/unchoke cycle the
   stale entries still counted against the pipeline and (once 5 were stale) the peer was never
   asked for anything again.

## Decision

- **`InFlightBlocks` (torrent package)** - one per `TorrentSession`, a `ConcurrentHashMap` of
  (piece, offset) -> (owning connection, claim time). A block is requested only after
  `tryClaim()` succeeds. Claims are released when the block arrives (from anyone), when the owner
  chokes us, and when the owner disconnects. A claim older than 30 s can be taken over by a
  *different* connection, so a peer that accepted a request and went quiet can't hold a block
  indefinitely. The owner never re-claims its own expired claim.
- **`requestMore()` skips fully-claimed pieces** by passing `selectNextPiece` a predicate that
  also requires a requestable block, so a peer moves on to another piece rather than idling.
  The piece selection strategy interface is unchanged.
- **Adaptive pipeline depth**: `clamp(rate x 2 s / 16 KiB, 5, 128)`, from a new smoothed
  per-connection download rate (`PeerConnection.downloadRateBytesPerSec()`, 1 s windows, averaged
  with the previous value). Starts at 5 until the first window completes.
- **`PeerConnection` clears `pendingRequests` on Choke.**
- A duplicate block for an already-received block (possible after a takeover) is dropped
  without rewriting.

## Alternatives considered

- **Tracking in-flight blocks inside `PieceManager`** - rejected; it would make the piece
  bookkeeping aware of peers. Kept as a session-level structure owning the peer relationship.
- **Checking every other connection's `pendingRequestsSnapshot()`** - rejected; copies a set per
  connection per candidate block on the hottest path.
- **Retrying failed peer addresses** - unrelated to this decision, tracked in TODO.md.

## Stability ([[0051-stability-as-a-standing-consideration]])

- Memory: the claims map is bounded by the torrent's block count and by
  connections x max depth in practice; every exit path (block received, choke, disconnect)
  releases entries, and expired claims are overwritten by takeovers.
- Peers/hostile: the 128 cap keeps one peer's queue modest; a peer that never answers is
  bounded by the 30 s takeover plus the existing idle-read timeout. A peer can inflate the
  measured rate only by actually sending data.
- Concurrency: lock-free (`ConcurrentHashMap`, single-writer rate fields), no `synchronized`,
  per [[0007-concurrency-model]]. `PieceManager`'s lock is still held while the selection
  predicate scans blocks, as before, just over slightly more work.
- Cost: `requestMore()` may scan several fully-claimed pieces per call; each scan is a walk over
  one piece's blocks (256 for a 4 MiB piece).

## Frontend

None - engine-internal ([[0071-thin-frontend-as-a-standing-consideration]]).

## Addendum: endgame mode (2026-09-21)

Without it, the last blocks could sit on one slow peer for up to the 30 s takeover timeout while
every other connection idled.

- **Trigger** (`TorrentSession.inEndgame()`): when `requestMore()` finds no block this peer can
  claim exclusively, endgame applies if the wanted blocks still missing
  (`PieceManager.missingWantedBlocksAtMost`, early-exit count) are no more than the number of
  blocks in flight - i.e. everything missing is already requested from someone. A peer that
  merely lacks the pieces the rest need doesn't trigger it mid-download.
- **Duplicates**: `InFlightBlocks` now holds a *list* of holders per block.
  `tryClaimDuplicate()` adds the requesting peer as an extra holder, up to
  `MAX_ENDGAME_HOLDERS_PER_BLOCK` (3), never the same peer twice.
- **Cancel on arrival**: `release()` returns every holder; `onPieceBlockReceived` sends `Cancel`
  to all but the delivering peer. This also cleans up the pending entry after a timed-out claim
  is taken over.
- Late duplicates that still arrive are dropped by the existing already-received guard.

Stability: extra bandwidth is bounded to the final blocks x 2 extra holders; the map stays bounded
by block count x 3 and is released on the same three exit paths. `missingWantedBlocksAtMost`
holds `PieceManager`'s lock for at most one pass over pieces, exiting early mid-download.
