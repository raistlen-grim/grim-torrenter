# 0067 — Per-peer progress/relevance, lifetime average speed, tracker peer count and re-announce countdown

**Status:** Accepted

## Decision

The four items in the Medium-value tier of the qBittorrent-parity backlog (`TODO.md`,
2026-09-10) that are plain metrics/display work, picked up together - the fifth Medium item
(per-torrent bandwidth/connection limits) is a control, not a metric, and stays a separate
future decision as already flagged there.

## Per-peer Progress % and Relevance

Both computed in `TorrentSession.peers()`, iterating `pieceManager.pieceCount()` once per
connection per poll (every 3s, same cadence the Peers tab already polls at) -
`PeerConnection.peerHasPiece(int)` already exists for exactly this, just never iterated
across every piece before now.

- **`percentAvailable`**: `(pieces the peer has) / (total pieces)`. 0 for a 0-piece torrent
  (never actually reachable in practice, but avoids a division by zero).
- **`relevance`**: `(pieces the peer has that we still need) / (pieces we still need)` - this
  is what actually explains "why is this peer not helping me," unlike raw availability. `0`
  when we need nothing (already complete/seeding) - relevance is moot once there's nothing
  left to want.

**Both ride the existing `Peers` column layout rather than opening a new one for relevance.**
The Peers tab's own README spec already reserved a `Done` column (`peers-tab.scss`'s own
comment: "README's Peers tab content spec: minmax(0,1fr) 54px 62px 62px (Address/Done/Down/Up)
- only three columns here, not four: no per-peer completion percentage exists to put in a Done
column") - this isn't a new deviation from the guide, it's finally filling in a column the guide
already specified once the data existed. `relevance` has no column of its own in that spec, and
the drawer's 430px width has no room to add one - it rides a tooltip on the new `Done` cell
instead, the same "extra detail via tooltip, not a new column" pattern the tracker
seeders/leechers tooltip and the rate-trend sparkline both already established.

## Average lifetime speed

Purely a frontend derivation, no backend field needed: `avgDownload = bytesDownloaded /
(timeActiveMillis / 1000)`, `avgUpload = lifetimeUploadedBytes / (timeActiveMillis / 1000)` -
both already on `Torrent` since [[0064-persistent-lifetime-stats]]/[[0066-peer-diagnostics]].
`bytesDownloaded` (verified, not `bytesReceived`) is the right numerator here, same basis the
progress bar/ratio already use - lifetime downloaded needs no separate tracking since completed
pieces already persist across restarts on their own (0064's own reasoning).

Shown as a tooltip on the torrent-detail header's existing `Down`/`Up` fact cells (`title`
attribute, same native-tooltip convention the `Added` cell already uses) - not a new cell.
The fact grid already grew from 8 to 10 cells today; a `Down avg`/`Up avg` pair would be two
more, and unlike Active/Completed/Wasted (each a genuinely new fact with nowhere else to live),
an average is naturally a footnote on the number it's averaging, not a fact of its own.

Guards `timeActiveMillis === 0` (a torrent that's never actually run) to avoid a division by
zero - em dash, same idiom every other empty-value cell in this app already uses.

## Tracker peer count

A new `Integer peers` field on `TrackerStatus`/`TrackerView`/the frontend `Tracker` model,
populated in `TrackedTrackerClient.recordSuccess()` from `response.peers().size()` - the size
of the peer list *that announce's response actually returned* (bounded by that tracker's own
num_want handling), not the swarm's total size. Survives a subsequent `ERROR` the same way
`seeders`/`leechers` already do (stale-but-recent is more useful than clearing it, and
`state`/`lastAnnouncedAt` already say how fresh it is).

Shown in the existing `announceTooltip()` on a non-working tracker's row, alongside
seeders/leechers - not a new column, matching how those two already surface (this tab collapses
every working tracker into one summary line; only non-working ones get a row at all, per its
own "nobody reads it while it's healthy" philosophy).

## Tracker re-announce countdown

Purely client-side: `nextAnnounceAt - now`, humanized via the shared `humanizeDuration()`
utility [[0064-persistent-lifetime-stats]] already extracted. Replaces the tooltip's existing
absolute `Next: <date>` line with a relative one (`Re-announces in 4m 12s`) - a countdown is
what a power user actually wants to know ("how long until this tracker is checked again"), not
a timestamp they'd have to do the subtraction on themselves. Recomputed on each 3-second poll
tick (same cadence the tab already re-renders on), so it's accurate to within a few seconds -
not a live per-second ticker, consistent with this tab never having had one.

## Testing

- `TorrentSessionTest` - a peer with a known bitfield reports the correct `percentAvailable`;
  `relevance` correctly excludes pieces we already have and pieces the peer doesn't have,
  including the "we need nothing" zero case.
- `TrackedTrackerClientTest` - `peers` is recorded on success and survives a subsequent failure,
  same shape as the existing seeders/leechers test.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **No new unbounded growth**: the per-peer piece-iteration is O(pieces) per connection per
  poll, bounded by `MAX_CONNECTIONS` connections and this torrent's own (fixed) piece count -
  no state accumulates between polls.
- **No hostile-peer angle beyond what already exists**: `percentAvailable`/`relevance` are
  computed from a peer's own advertised bitfield/Have messages, which peerwire already trusts
  for piece-selection purposes elsewhere in this class; nothing new is trusted here that wasn't
  already.
- **No new locking**: `peerHasPiece()` already synchronizes internally on `peerPieces`; the new
  iteration just calls it `pieceCount()` times per connection instead of the existing 0.
