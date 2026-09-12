# 0066 — Peer diagnostics: connection direction, peer-source attribution, wasted bytes

**Status:** Accepted

## Decision

The last three items in the High-value tier of the qBittorrent-parity backlog (`TODO.md`,
2026-09-10), picked up together since all three touch the same DTO surfaces (`PeerSnapshot`,
`PeerView`, the frontend `Peer`/`Torrent` models, the Peers tab).

## Connection direction (incoming/outgoing)

Cheaper than it looked when first scoped: `PeerConnection` already has two structurally
distinct factory families - `connect()` (always outbound) and `accept()` (always inbound) - so
direction is already known unambiguously at every call site, just never recorded. A new
`boolean incoming` field on the private constructor, hardcoded `false` from every path inside
`connectPlaintext()`/`connectEncrypted()` and `true` from every path inside the `accept()`
family. **No new public parameter, no existing overload signature touched** - direction is
baked in at the two points that already know it unconditionally, not threaded through as
caller-supplied data.

## Peer-source attribution (tracker/DHT/PEX/LSD)

`TorrentSession.knownAddresses` (currently a plain `Set<PeerAddress>`) becomes
`Map<PeerAddress, PeerSource>` - a new enum (`TorrentSource`... named `PeerSource`, `peer`
package alongside `PeerConnection`): `TRACKER, DHT, PEX, LSD, UNKNOWN`. **First source wins**
(confirmed with the user) - `putIfAbsent` semantics, so a peer independently rediscovered via a
second source later keeps its original attribution rather than churning; this answers "how did
we originally learn of this peer," which is what a power user actually wants to know, not
"what's vouching for it this instant."

Every place that currently adds to `knownAddresses` gets tagged:
- `enterDownloading()` (called from both `start()` and `startViaDhtBackstop()`) gains a
  `PeerSource` parameter - `TRACKER` from the former, `DHT` from the latter, since today it's
  one method serving two genuinely different callers with no way to tell them apart.
- `reannounce()` - `TRACKER`.
- `discoverPeersViaDht()` - `DHT`.
- `handleExtended()` (ut_pex) - `PEX`.
- The public `addKnownPeers(List<PeerAddress>)` - used today only by
  `TorrentEngine.onLsdPeerFound()` (its Javadoc's claimed magnet-seed use case no longer has a
  live call site anywhere in the codebase - stale, corrected in passing) - gains an explicit
  `PeerSource` parameter rather than silently hardcoding `LSD` into a generically-named public
  method; its sole real caller passes `PeerSource.LSD`.

`PeerConnection` gains a `PeerSource source` field (`UNKNOWN` default, matching direction's own
"no new public parameter on most overloads" approach - only the widest `connect(...)` overload
gains it, since `attemptConnect()` is TorrentSession's only call site that actually has a real
source to supply, looked up from `knownAddresses.get(address)`). An **incoming** connection's
source is always `UNKNOWN` - direction and source are orthogonal fields, not one combined enum;
"how a peer that connected to *us* found *our* address" isn't something this side of the
connection can know, and isn't the same question qBittorrent's own X/H/L flags answer either
(those only ever apply to outbound-discovered peers).

## Wasted bytes

A new counter on `TorrentSession`, incremented by exactly `actualBytes.length` every time
`verifyPiece()`'s call to `PieceManager.verify()` returns `false` - i.e. only the live-download
hash-mismatch path, not `verifyThenSettle()`'s restore-time re-verification (finding an
already-on-disk piece invalid there just means "not actually complete yet," not "bytes were
received and discarded this session" - a meaningfully different thing from qBittorrent's own
"Wasted" metric, which is specifically about redundant/corrupt data actually received over the
wire).

**Persisted, joining `PersistedLifetimeStats`** (confirmed with the user) as a fourth field
(`wastedBytesBaseline`) - trivial extension of [[0064-persistent-lifetime-stats]]'s already-
built marker/flush/restore machinery, no new infrastructure needed. `wastedBytes()` follows the
exact same baseline-plus-this-session's-own-accumulator shape `lifetimeUploadedBytes()` already
established.

## Wire exposure and display

- `PeerSnapshot`/`PeerView`/the frontend `Peer` model gain `incoming: boolean` and
  `source: 'TRACKER' | 'DHT' | 'PEX' | 'LSD' | 'UNKNOWN'`. Peers tab: a new pair of small icons
  next to the existing choke/interest pair (an arrow for direction, a letter/icon for source,
  both with a `title` tooltip) - the same compact-icon-with-tooltip pattern the tab already
  uses, not a new column (the drawer's 430px width has no room for one - [[0044-torrent-detail-drawer]]).
- `TorrentView`/the frontend `Torrent` model gain `wastedBytes: number` (lifetime). Joins the
  fact grid as a 10th cell, extending today's own 2026-09-10 [[0032-style-guide-and-primeng-theme]]
  addendum (already growing the grid from 8 to 9 cells for Active/Completed) rather than opening
  a separate revision for one more cell added the same day.

## Testing

- `PeerConnectionTest` - `connect()`'s result reports `incoming() == false`, `accept()`'s
  reports `true`.
- `TorrentSessionTest` - a peer discovered via `discoverPeersViaDht()` then connected reports
  `PeerSource.DHT`; the same address later also reported by PEX keeps `DHT` (first-source-wins);
  an incoming connection reports `PeerSource.UNKNOWN`; a hash-mismatched piece increments
  `wastedBytes()` by exactly the piece's length and doesn't affect `bytesDownloaded()`.
- `TorrentEngineTest` - wasted bytes survives a restart via the existing lifetime-stats marker
  round-trip, same shape as the existing lifetime-uploaded/active-time restart tests.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Bounded growth**: `knownAddresses` changing from a `Set` to a `Map` with the same keys is
  no change in growth characteristics - same unbounded-but-accepted-at-this-project's-scale
  reasoning `failedAddresses`/`inFlightAddresses` already carry (their own Javadocs).
- **No new locking**: `knownAddresses` stays a `ConcurrentHashMap` (`putIfAbsent` is already
  atomic); `wastedBytes`' counter is a plain `AtomicLong`, same idiom as
  `accumulatedUploaded`/`accumulatedReceived`.
- **No hostile-peer angle beyond what already exists**: source attribution and direction are
  both derived from this engine's own bookkeeping (which method added an address, which factory
  built a connection), not from anything a peer sends us directly - a malicious peer can't spoof
  its own recorded source or direction.

## Alternatives considered

- **A single combined enum mixing direction and source** (e.g. `OUTGOING_TRACKER`,
  `OUTGOING_DHT`, `INCOMING`) - rejected; conflates two orthogonal questions ("which way did
  this connection start" and "how did we learn of this address") into one type, forcing every
  consumer to unpack a compound value for what's really two independent booleans/enums.
  Real clients treat them separately too (qBittorrent's own flags: a direction indicator plus
  independent source letters).
- **Most-recent-source-wins for peer attribution** - rejected (user decision); see above.
- **Wasted bytes reset per session, not persisted** - rejected (user decision); would have been
  inconsistent with every other lifetime metric [[0064-persistent-lifetime-stats]] just built,
  for no real savings (the marker/flush machinery already exists).
