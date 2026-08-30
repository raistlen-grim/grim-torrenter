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

## Addendum (2026-08-30): periodic DHT re-query for genuinely trackerless torrents

**The gap**: this doc's own backstop mechanism only ever covered a torrent that *has*
trackers, all currently unreachable. A genuinely trackerless torrent (magnet with no
announce-list, or `.torrent` upload listing none) took a completely different, much weaker
path: `start()` always called `trackerClient.announce(...)` first, and for a trackerless
torrent that's a `NoOpTrackerClient`, which never throws and reports a deliberately huge
(365-day) interval specifically so its own reannounce loop is a no-op. Actual peer discovery
for these torrents came from `TorrentEngine.seedFromDhtIfTrackerless()`: a *separate*,
one-shot `dhtNode.findPeers(...)` call made right after torrent creation, entirely outside
`start()`'s own reannounce scheduling. Net effect: one DHT lookup, ever, plus whatever Peer
Exchange ([[0040]], 60s cycles) could scrounge from peers already connected from that one
lookup. This was root-caused while investigating a real report of GrimTorrenter's peer count
climbing far slower than qBittorrent's for a trackerless magnet (see `PROGRESS.md`'s matching
entry and [[0028-magnet-links-and-dht]]'s own 2026-08-30 addendum for the sibling fix - too
few candidates tried, sequentially - found in the same investigation).

**The fix**: extend this doc's own backstop machinery to also cover the genuinely-trackerless
case from `start()`, instead of routing it through a no-op tracker announce that was never
going to do anything useful.

- **`start()`** gained an early branch: `if (trackerClient instanceof NoOpTrackerClient)`
  (the same runtime check `seedFromDhtIfTrackerless` itself used to identify a trackerless
  torrent) calls a new `startViaDht()` instead of the pointless no-op announce.
- **New `startViaDht()`**, parallel to `startViaDhtBackstop()` but not sharing its body,
  since the failure semantics genuinely differ: a `dhtNode == null` or a failed/empty lookup
  is *never* `ERROR` here, just "enter `DOWNLOADING` with zero peers so far" - there's no
  prior working state to consider "failed," the same way a regular tracker responding with
  zero peers isn't `ERROR` either (`startViaDhtBackstop()`, by contrast, has just watched a
  real tracker announce fail, so an empty-vs-failed DHT lookup there still means something).
  Feeds `enterDownloading(peers, trackerlessReannounceIntervalSeconds.get())`.
- **`reannounce()`** gained the same early branch, calling a new `reannounceViaDht()` (parallel
  to `reannounceViaDhtBackstop()`, same async-virtual-thread-into-`addKnownPeers()` shape)
  instead of the no-op tracker announce.
- **`dhtBackstopActive` is deliberately left untouched by either new method.** That flag's own
  Javadoc (and [[0039-dht-backstop-visibility]]'s UI work) reserves it for a tracker-bearing
  torrent whose tracker is currently unreachable - a genuine degradation. A trackerless
  torrent doing DHT lookups is its *normal* operating mode, not a degradation; `usesDht()` /
  `isTrackerless()` already communicate that unconditionally, and the frontend's
  `trackers-tab.ts` already computes `usesDht() || dhtBackstopActive()` for display, so
  nothing is lost by not also flipping the second flag here.

**New live `Settings` field**: `trackerlessDhtReannounceIntervalSeconds` (default 300s / 5
minutes), read once per `start()` via a `Supplier<Long>` threaded through `TorrentSession`
exactly the way `Supplier<EncryptionMode> encryptionMode` already is - a change takes effect
on that torrent's *next* `start()`, not retroactively, for the same reason
`Math.max(response.interval(), 30)` already can't change mid-flight: a
`ScheduledExecutorService.scheduleWithFixedDelay` period can't be altered once scheduled
without cancelling and rebuilding it. **Deliberately a new field, not a reuse of
`DHT_BACKSTOP_REANNOUNCE_INTERVAL_SECONDS`** (which stays exactly as-is, 1800s, fixed, for
its own separate tracker-degraded case) - raised directly by the user, whose own reasoning
was that 30 minutes is far too slow given qBittorrent's peer count visibly grows within
seconds. Chose 300s over something closer to that qBittorrent-observed speed: shortening this
interval alone doesn't fully close the qBittorrent gap, which is mostly explained by a much
richer DHT routing table (379 vs. GrimTorrenter's 21 nodes at the time - a separate,
already-logged `TODO.md` item) making its *first* lookup far more fruitful, not by re-querying
DHT every few seconds. Querying `get_peers` for the same info hash that often is also poor DHT
citizenship - real clients typically re-query on a multi-minute cadence similar to tracker
announce intervals, and well-behaved remote nodes may deprioritize or ignore overly-frequent
repeat queries. 300s is meaningfully faster than the existing 1800s backstop interval while
staying reasonable, and is fully live-tunable (same never-degenerate-value normalization as
`eventLogRetentionDays` and the [[0028-magnet-links-and-dht]] magnet-fetch fields: `<= 0`
resets to the 300s default) so it can be experimented with directly - exposed in the frontend
as a new row in the existing `network-settings` group (`settings.model.ts`,
`network-settings.ts`/`.html`/`.scss`), not a new settings group, since it's a DHT-adjacent
tuning knob alongside `dhtEnabled`/`acceptIncomingConnections`/`encryptionMode` rather than a
topic of its own.

**`TorrentEngine.seedFromDhtIfTrackerless()` removed entirely**, along with its two call sites
(the `addTorrent()` new-torrent path and the restore path) - `TorrentSession.start()`'s own
new `startViaDht()` fully replaces what it did, eliminating the previous redundant
double-lookup (one pointless no-op announce plus one external one-shot DHT seed) in favor of
one consistent, genuinely periodic mechanism owned entirely by `TorrentSession` itself.

### Stability ([[0051-stability-as-a-standing-consideration]])

- **No new unbounded growth**: each `TorrentSession`'s reannounce timer already existed for
  every torrent (previously scheduled at a ~365-day interval for trackerless torrents, now a
  real one) - this changes an existing per-torrent scheduled task's *frequency*, not its
  *existence*, and the frequency is user-controlled with a normalized, never-degenerate floor.
- **No new concurrency pattern**: multiple torrents' independent DHT lookups running
  concurrently against the one shared `DhtNode` already had to work correctly before this
  change - `startViaDhtBackstop()`/`reannounceViaDhtBackstop()` already do exactly this for
  any number of tracker-bearing-but-degraded torrents simultaneously. This just makes that
  same already-supported pattern more frequent for trackerless torrents specifically, not a
  new category of load `DhtNode` hasn't already had to handle. Each lookup is still bounded by
  the existing `DHT_QUERY_TIMEOUT` (5s).
- **Cleanup unaffected**: `shutdownNetworking()`'s existing `scheduler.shutdownNow()` already
  cancels whatever's scheduled regardless of which reannounce path is in use - no new exit
  path to account for.

### Testing

`TorrentSessionTest` gained two cases, same real-local-UDP-socket DHT style as the three
cases this doc's Testing section above already lists:

- `startViaDhtFindsPeersImmediatelyForATrackerlessTorrent` - a `NoOpTrackerClient` session
  whose peer is already announced to DHT before `start()` runs finds and connects to it via
  `startViaDht()` alone (no `reannounce()` involved), and `isDhtBackstopActive()` stays
  `false` throughout.
- `reannounceViaDhtPicksUpANewlyAnnouncedPeerOnALaterCycle` - proves periodicity, not just a
  one-shot lookup: `start()`'s own DHT lookup runs *before* the peer is announced to DHT at
  all, so it finds nothing; the peer only becomes discoverable afterwards, and is picked up on
  a later *scheduled* cycle - `reannounce()` is never called directly here (unlike
  `fallsBackToDhtWhenReannounceFails` above, which drives one cycle manually) - using the
  widest `TorrentSession.create(...)` overload with `trackerlessReannounceIntervalSeconds`
  supplied as `() -> 1L` so the test doesn't wait out the real 300s default.

Existing trackerless coverage (`addKnownPeersSeedsAdditionalPeersAndAttemptsConnection`,
constructed with `dhtNode == null`) needed no change - `startViaDht()`'s `dhtNode == null`
branch still yields zero peers on `start()`, exactly matching what that test already asserted
before this addendum, just reached through a different internal path now.
`TorrentEngineMagnetTest`'s DHT-related cases (`addMagnetDoesNotThrowSynchronouslyWhenNoUsableTrackerButDhtEnabled`
and friends) only assert `addMagnet()` doesn't throw synchronously, never on
`seedFromDhtIfTrackerless()`'s specific one-shot behavior, so the removal needed no test
changes there.
