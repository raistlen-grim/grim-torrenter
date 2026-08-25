# 0050 — `PieceManager`: `ReentrantLock` instead of `synchronized`

**Status:** Accepted

## Decision

The last remaining item from the engine stability/scale audit behind
[[0047-bounded-file-handle-pool]]/[[0048-piece-verification-throttling]]/[[0049-many-torrents-load-test]]:
`PieceManager`'s bookkeeping methods were all `synchronized`, which reads directly against
[[0007-concurrency-model]]'s own stated care point ("avoid `synchronized` blocks in the
per-connection hot path, as they can pin a virtual thread to its carrier"). The audit judged
this was never an actual pinning risk in practice - nothing blocking (I/O, another lock) ever
happens while the monitor is held, just `BitSet` reads/writes - but a `synchronized` method
sitting in code that's explicitly on the per-connection hot path (every block received, every
piece selected) was close enough to what 0007 warns about to be worth removing outright rather
than leaving a "trust me, it's fine" comment behind.

**Every `synchronized` method became a plain method body wrapped in
`lock.lock() / try / finally { lock.unlock() }`** against a new `private final ReentrantLock
lock` field - mechanical, one-for-one, no behavior change. `ReentrantLock` doesn't have
`synchronized`'s pinning failure mode (it's not a JVM monitor), so this closes the doc/reality
gap even though, per the audit, there was no live bug to fix - see
[[0047-bounded-file-handle-pool]]'s own use of the identical reasoning for why `ReentrantLock`
was already this project's answer to "needs a lock, must not pin."

### Reentrancy preserved on purpose - this is the one thing that had to be gotten right

`selectNextPiece()` calls into `PieceSelectionStrategy.selectNextPiece(this, peerHasPiece)`,
and `SequentialPieceSelectionStrategy`'s implementation calls back into `manager.stateOf(i)` -
**the same instance, same thread, while `selectNextPiece()` still holds the lock.**
`synchronized` supports this (a thread already holding an object's monitor can re-enter it);
`ReentrantLock` supports the identical case by design (it's literally named for it). Existing
`PieceManagerTest` coverage (`sequentialSelectionSkipsCompleteAndUnavailablePieces`,
`selectNextPieceReturnsEmptyWhenPeerHasNothingUseful`) already exercises this exact call path -
a broken reentrant lock here would hang those tests outright, so no new test was needed
specifically to prove it; the existing suite passing is the proof.

### A small bonus: `verify()`'s SHA-1 hash no longer holds the lock

`verify(pieceIndex, actualBytes)` used to run the whole SHA-1 comparison
(`pieces.matches(pieceIndex, sha1(actualBytes))`) inside the `synchronized` block, even though
hashing doesn't touch any of the mutable state the lock protects (`completedPieces`,
`blockReceived`) - it only reads the immutable `pieces` field. Restructured so the hash
computation happens *before* `lock.lock()`, and only the resulting bookkeeping update
(`completedPieces.set(...)` / `blockReceived[...].clear()`) happens under the lock. Not the
point of this change, but a straightforward improvement to fold in while touching every method
body anyway - less time holding the lock during exactly the piece-verification path
[[0048-piece-verification-throttling]] already cares about keeping efficient. Similarly,
`validateIndex()` calls (pure reads of the immutable `pieceCount` field) were moved to before
`lock.lock()` in every method, for the same reason.

## Testing

No new tests - existing `PieceManagerTest` coverage is unchanged in behavior and already
exercises every method, including the one reentrant call path (`selectNextPiece()` ->
`stateOf()`) that mattered for this change's correctness.

## Alternatives considered

- **Leave `synchronized` and add a comment explaining why it's safe** - considered, since the
  audit found no live bug; rejected once actually making the swap turned out to be
  mechanical and risk-free (existing tests already prove reentrancy holds), and a codebase
  with zero `synchronized` blocks left to explain away is simpler to reason about for the
  next person reading [[0007-concurrency-model]]'s guidance than one with a documented
  exception to it.
