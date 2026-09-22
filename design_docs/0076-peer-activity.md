# 0076 — Peer activity: an at-a-glance health field on each peer

**Status:** Accepted

## Decision

`PeerView` (`GET /api/torrents/{h}/peers`) gains `activity`, one of:

- `ACTIVE` - a block of data moved in either direction within the last 10 seconds.
- `WAITING` - nothing moved recently, but we're interested in this peer (we want something
  from them: choked, or asked and not yet answered).
- `IDLE` - nothing moving and nothing we want from them (e.g. a peer that only has data we
  already have).

Computed by the backend (`PeerConnection.activity()`), not the frontend, per
[[0071-thin-frontend-as-a-standing-consideration]]: it's a judgment about connection health
that any other client of the REST API would otherwise have to re-derive from four choke/
interest booleans plus its own byte-counter rate tracking. It has value regardless of how the
Peers tab draws it - a single marker on a compact row, a sort key ("active peers first"), or a
column in the larger tracker/peer-source dialog.

### How it's derived

`PeerConnection` records the wall-clock time of the last block moved in either direction
(`lastTransferMillis`, set where `downloadedBytes`/`uploadedBytes` are already incremented; 0 =
never). `activity()` is `ACTIVE` when that's within `ACTIVITY_WINDOW_MILLIS` (10s), otherwise
`WAITING` if `amInterested()`, otherwise `IDLE`. The window is deliberately longer than the 3s
detail-view poll: blocks arrive in bursts (choke rounds, the rate limiter), and a window near
the poll interval would make a healthy peer flicker between states. `activity(nowMillis)` takes
the clock as a parameter purely so the window boundary is testable without sleeping.

Not tracked: a `WAITING` peer that *is* unchoking us but hasn't answered yet, versus one that's
choking us, are both `WAITING` - the raw `peerChoking`/`amInterested` fields are still in the
response for a client that wants the distinction.

## Stability

- **Bounded state:** one `volatile long` per connection; nothing grows.
- **Locking:** a plain volatile write on the receive/send paths and a volatile read on the
  snapshot path - no lock, nothing new on the hot path beyond one `System.currentTimeMillis()`
  per block (already doing atomic adds and rate-limiter acquires there).
- **Hostile peer:** a peer can only make itself look `ACTIVE` by actually sending us a block
  (or by us sending it one). It can't influence anything but its own row's label.
- **Cleanup:** no resources to release.

## UI use (2026-09-19)

The Peers tab now draws `activity` as a marker on a one-line-per-peer row, sorts by it, and the
details dialog's per-peer table shows it as a labelled column - see [[0066-peer-diagnostics]]'s
2026-09-19 addendum. The sentence explaining each state (which choke/interest fact put a peer in
`WAITING` or `IDLE`) is worded in the frontend (`shared/peer-activity.ts`) from the raw flags
that are still in the response; only the three-way classification itself is backend-computed.
