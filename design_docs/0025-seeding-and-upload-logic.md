# 0025 — Real seeding/upload logic

**Status:** Accepted

## Decision

Until now, `PeerConnection` had full bidirectional plumbing
(`sendUnchoke`, `sendPiece`, a read path on `TorrentStorage`) built
deliberately from Phase 1 per [[0008-seeding-design-considerations]], but
`TorrentSession` never actually drove it — every connection stayed
permanently choked, so reaching `SEEDING` state meant "we have 100% of the
data," not "we're actually serving anyone." This closes that gap.

**Choking algorithm is a simplified, capped rotation, not full BEP 3
tit-for-tat** — confirmed with the user as a reasonable trade-off. Real
clients rank interested peers by their upload rate *to us* (reciprocity)
plus a rotating optimistic-unchoke slot; that needs per-peer rate
tracking, periodic re-ranking, and different behavior downloading vs
seeding (no reciprocity signal once we don't need anything). Instead:
every peer expressing interest triggers an immediate re-evaluation (so no
one waits out a timer for a free slot), plus a periodic tick every 10s for
ongoing rotation. If there are at most `MAX_UNCHOKED_PEERS` (4) interested
peers, all get unchoked; otherwise `ChokingStrategy.selectToUnchoke`
rotates which ones are picked across evaluations so no one is starved
forever. The same algorithm applies identically whether `DOWNLOADING` or
`SEEDING` - a direct benefit of not doing rate-based reciprocity, which
would otherwise need special-casing for the "nothing to reciprocate for"
seeding case.

**`ChokingStrategy` is a pure, generic utility** (not tied to
`PeerConnection`), mirroring `PieceSelectionStrategy`'s use of
`IntPredicate` in [[0016-piece-and-storage]] for the same reason: testable
without real sockets. Deliberately *not* made a pluggable interface like
`PieceSelectionStrategy` was - that pluggability was justified by an
explicit, already-documented future requirement ("sequential now,
rarest-first later" in [[0009-phased-scope]]); there's no equivalent
confirmed plan for a smarter choking algorithm, so a plain static method
is enough until there is one.

**Incoming `Request` handling** (`onBlockRequested`): served only if the
connection is unchoked, the requested length is sane (capped at 128 KiB -
generous over our own 16 KiB request size, guarding against a peer
requesting an absurd amount), and we actually have that piece verified
complete. A storage read failure while serving is logged and skipped
(that one request fails silently to the peer), *not* escalated to
`fail()` the way a write failure while downloading is - serving one peer
badly isn't a whole-session-ending problem the way being unable to
persist incoming data is. `Cancel` stays a no-op: requests are served
synchronously and immediately, so there's never a queued send to actually
cancel.

**Fixed a real, newly-relevant accounting gap**: the tracker's `uploaded`
figure was computed from `accumulatedUploaded` alone, which only rolls up
a peer's `uploadedBytes()` when it *disconnects*
(`PeerListener.onDisconnected`). Since nothing ever called `sendPiece`
before this slice, `uploadedBytes()` was always 0 and the gap was
invisible. Now that real uploading happens, `bytesUploaded()` sums
`accumulatedUploaded` plus every *currently connected* peer's live
`uploadedBytes()`, so the reported figure doesn't undercount while we're
actively serving someone. All `TrackerRequest` construction sites now use
this instead of `accumulatedUploaded.get()` directly. Made `public` (not
just used internally for tracker requests) so the app layer can expose it
in `TorrentView` too - see the UI addition noted below.

**`updateChoking()` is `synchronized`** - it can be triggered concurrently
from multiple peers' read-loop threads (on `Interested`/`NotInterested`)
and the periodic scheduled tick. Known, accepted minor interaction: since
`start()`/`stop()` also hold this same lock for their own (potentially
several-second) tracker announces, a peer's `Interested` message arriving
during a `stop()` call could block that peer's read-loop thread for the
remaining duration of the stop sequence. Judged acceptable - narrow
window, and the connection is about to be closed by that same `stop()`
call anyway.

**UI**: raised by the user immediately after this landed - once uploading
is real, there was nothing showing it. `TorrentView` gained
`bytesUploaded`; the frontend mirrors the exact pattern already used for
download stats (design_docs/0020) - `TorrentEventsService` now tracks both
downloaded *and* uploaded byte readings per torrent and derives both
rates client-side, exposed as `uploadRateBytesPerSec` alongside the
existing `downloadRateBytesPerSec` on `TorrentWithRate`. The table has a
new "Uploaded" column (bytes + rate, same layout as "Progress"'s bytes +
rate line). No new peer-level "who are the uploaders" concept was added -
BitTorrent doesn't really have a fixed uploader/downloader role, any
connected peer is a potential recipient once unchoked, so a torrent-level
aggregate is what's meaningful here, not a per-peer breakdown.

## Testing

`ChokingStrategyTest` covers the pure selection logic in isolation
(under/at/over cap, rotation changing the selection, empty input).
`TorrentSessionTest` gained
`unchokesInterestedPeerAndServesRequestedBlockOnceWeHaveIt`: the same fake
peer that hands us a piece then asks for it back (narratively a bit odd
for a real swarm, but the simplest way to exercise the actual
unchoke-then-serve path without a separate two-peer test harness) -
confirms we send `Unchoke` on `Interested` and correctly serve the
requested block back as a matching `Piece`.

## Alternatives considered

- **Full BEP 3 tit-for-tat with optimistic unchoke** - discussed with the
  user and explicitly deferred in favor of the simpler rotation; can be
  swapped in later by converting `ChokingStrategy` from a static method
  into an interface at that point, not before.
- **Escalate a serving-read failure to `fail()`** - rejected; conflating
  "couldn't upload one block to one peer" with "the session is broken" is
  disproportionate.
