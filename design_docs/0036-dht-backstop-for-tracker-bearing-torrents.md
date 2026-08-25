# 0036 — DHT backstop for tracker-bearing torrents

**Status:** Accepted

## Decision

Explicitly deferred when Mainline DHT was first wired in ([[0028-magnet-links-and-dht]]'s
slice 6 scope note): DHT was only ever consulted for a torrent with **zero** usable
trackers at all (a trackerless magnet, or a plain `.torrent` upload that genuinely lists
none). A torrent that *has* trackers but they're all currently unreachable got no DHT
fallback whatsoever - the exact failure mode that originally motivated multi-tracker
fallback in the first place ([[0022-multi-tracker-fallback]]: a real torrent with ~40
listed trackers, all but one dead, rescued only by a desktop client falling through to a
working UDP tracker). `MultiTrackerClient` already retries every tracker in every tier
before giving up (see 0022) - this closes the gap for when that entire retry still fails.

**Two call sites in `TorrentSession`, mirroring the two places tracker failure was already
handled differently:**

- **`start()`** - previously went straight to `ERROR` on any tracker failure. Now, on
  failure, falls back to a synchronous `dhtNode.findPeers(...)` call (bounded by the
  existing `DHT_QUERY_TIMEOUT`, 5s) before deciding `ERROR` vs. `DOWNLOADING` - consistent
  with `start()` already being a fully synchronous, blocking call end to end (see
  [[0017-torrent-session]]), so this doesn't introduce new async complexity, only a bounded
  extra step in the failure path. **An empty-but-successful DHT lookup still counts as
  success** (proceeds to `DOWNLOADING`, same as a tracker responding with zero peers
  already does) - `ERROR` is reserved for "no peer-discovery path worked at all," not
  "found nobody on this attempt." Only reachable when `dhtNode != null`; if DHT is also
  unavailable, or the DHT lookup itself throws, falls through to the original `ERROR`
  behavior unchanged.
- **`reannounce()`** - previously logged at DEBUG and did nothing further on failure
  (existing connections keep working; the next 30-second-minimum-interval retry was the
  only recovery path). Now also fires an async DHT lookup on its own virtual thread
  (`reannounceViaDhtBackstop()`), feeding any peers found into the existing
  `addKnownPeers()` - deliberately **not** blocking the session's single scheduler thread
  (shared with the keepalive/choking timers) for the lookup's multi-second duration, same
  reasoning as `TorrentEngine.seedFromDhtIfTrackerless`'s own background lookup.

**Refactored `start()`'s normal-success path into a shared `enterDownloading(peers,
reannounceIntervalSeconds)` helper**, used by both the tracker-success path (interval from
the tracker's own response, `Math.max(response.interval(), 30)`) and the DHT-backstop path
(a new fixed constant, `DHT_BACKSTOP_REANNOUNCE_INTERVAL_SECONDS` = 1800s, since there's no
`TrackerResponse` to read a real interval from when every tracker failed) - avoids
duplicating the scheduler/keepalive/choking setup across both paths. The 1800s backstop
interval still keeps retrying the *real* tracker on schedule too (`reannounce()` is
unconditional on which path started the session) - if the tracker recovers on some later
reannounce, that call's own success path runs exactly as normal.

**Reused the exact same `dhtNode.findPeers(infoHash, ourListenPort, false,
DHT_QUERY_TIMEOUT)` call already used elsewhere** (`TorrentEngine.seedFromDhtIfTrackerless`,
the magnet metadata-fetch paths) - no new DHT-layer surface needed. `findPeers` already
does both directions in one call (get_peers *and* announce_peer to the nodes queried along
the way, per its own Javadoc), so this backstop makes the torrent findable by other
DHT-fallback peers too, not just able to find them.

**`reannounce()` changed from `private` to package-private**, purely for direct
testability - same rationale as `TorrentEngine.selectTrackerTiers`'s own
package-private-for-testing precedent. Its real 30-second-minimum scheduled interval made
waiting for a natural reannounce cycle impractical in a test; this lets a test trigger
exactly one cycle directly instead.

**UI visibility was deliberately left open here, then built separately** - see
[[0039-dht-backstop-visibility]]. `TorrentView.usesDht` itself still means
`isTrackerless()`, unchanged by either that work or this - a torrent using this backstop
still genuinely has trackers, `isTrackerless()` is correctly `false` for it; 0039 added a
second, separate signal for "is the backstop currently active" instead of overloading
`usesDht`.

## Testing

`TorrentSessionTest` gained three cases, exercised over real local UDP sockets for the DHT
side (same style as `PeerLookupTest`, not faked) plus the file's existing
fake-peer/`ServerSocket` fixture for the peer-wire side:

- `startTransitionsToErrorWhenTrackerFailsAndDhtFindsNoPeers` - a `DhtNode` with an empty
  routing table (no known contacts) still reaches `DOWNLOADING` with zero peers, not
  `ERROR` - confirming the empty-but-successful-lookup distinction above.
- `fallsBackToDhtWhenAllTrackersFailOnStart` - the full path: every tracker fails on
  `start()`, a peer is discoverable via DHT, and the session downloads a piece and reaches
  `SEEDING` through a connection that only exists because of the DHT fallback.
- `fallsBackToDhtWhenReannounceFails` - drives one `reannounce()` cycle directly (see the
  package-private note above) after forcing the tracker to fail mid-session, confirming
  the DHT-discovered peer gets connected the same way a normal reannounce's tracker
  response would have fed one in.

Test DHT setup avoids `DhtNode.getPeers`/`announcePeer` (package-private return type
`GetPeersResult`, inaccessible from the `torrent` test package) in favor of a node calling
its own public `findPeers(...)` purely for that call's side effect of announcing itself to
the node it queries along the way - the same technique `PeerLookupTest`'s own
`findPeersAlsoAnnouncesUsToTheRespondingNodes` test confirms works, just reached through
`DhtNode`'s public surface instead of package-internal methods only usable from within the
`dht` package itself.

## Alternatives considered

- **Treat an empty DHT lookup result the same as a DHT failure** (still go to `ERROR`) -
  rejected; would make the backstop strictly worse than useless for a torrent where DHT is
  reachable but genuinely has no peers yet, and breaks symmetry with how a tracker
  responding with zero peers already doesn't trigger `ERROR`.
- **Block the scheduler thread for `reannounce()`'s DHT fallback too**, matching `start()`'s
  synchronous style - rejected; `reannounce()`'s thread is shared with keepalive/choking
  timers for the session's whole running lifetime, unlike `start()`'s one-time call, so
  blocking it repeatedly on every tracker failure would delay those unrelated timers.
- **A configurable/adaptive backstop reannounce interval** instead of a fixed 1800s
  constant - rejected as unnecessary complexity; no real interval exists to read once every
  tracker has failed, and 1800s matches common real-tracker defaults closely enough.
