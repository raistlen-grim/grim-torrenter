# GrimTorrenter — TODO

Running list of ideas/requests to come back to later. Not commitments, not scoped, not
scheduled - just a place to jot something down before it's forgotten. Add items freely;
nothing here gets acted on until it's explicitly picked up.

- Notification service (emails, or something else yet to be defined)
- Run a user-configured script automatically when a torrent completes
- ~~Migrate off `@primeng/themes` (deprecated upstream, per its own `npm ci` warning) to
  `@primeuix/themes`, the maintained replacement.~~ **Done (2026-09-03)** - see
  `design_docs/0032`'s own addendum. Only `npm install` (to catch up the lockfile) remains,
  left for the user per this project's "builds run manually" convention.
## Performance: peer/seed count gap vs qBittorrent

Real user report (2026-09-06): the same torrent shows far fewer peers/seeds in
GrimTorrenter than in qBittorrent on the same system, and the Trackers tab only ever
shows one tracker as working — every other declared tracker sits at "Not yet
announced" indefinitely. Working through these one at a time rather than jumping
straight to a libtorrent-rasterbar comparison. Both test torrents are public
(non-private), from a popular torrent site.

**Confirmed root cause #1 (2026-09-06)**: a real side-by-side comparison for one
torrent - qBittorrent showed 29/305 seeds, 7/174 peers connected/known, 122 [sic -
GrimTorrenter's own DHT node count, separately confirmed] vs. qBittorrent's 360 total
DHT nodes; GrimTorrenter showed 0 connected peers after several minutes, one working
tracker (`udp://tracker.opentrackr.org:1337/announce`, self-reporting a healthy
308 seeders/140 leechers via its own announce response - ruling out "the tracker gave
us nothing"). Added temporary DEBUG logging to `TorrentSession.attemptConnect()`
(kept, low-risk, matches the existing DEBUG-tolerance pattern elsewhere in that class)
confirmed the connection attempts themselves are failing the *normal* way (mostly
`SocketTimeoutException`, one `EOFException` after a real TCP handshake succeeded) -
not a Docker networking block, just ordinary swarm churn where most tracker-supplied
addresses are unreachable at any given moment. The real problem: GrimTorrenter's
candidate pool is far too small to absorb that normal attrition, because:
- **DHT is only a backstop for tracker-bearing torrents, never a concurrent peer
  source** (design_docs/0036) - only consulted once *every* tracker fails. Confirmed:
  this torrent's Trackers tab also showed "DHT Disabled" for exactly this reason (the
  UI's `usesDht` label is accurate, not a separate bug - it's `isTrackerless()`, which
  is false here since a tracker exists and works). qBittorrent/libtorrent query
  tracker + DHT + PEX simultaneously for any non-private torrent, giving a
  continuously-growing pool; GrimTorrenter's pool here was ~50 candidates from one
  tracker, refreshed roughly hourly (that tracker's own announce interval).
  **In progress**: making DHT a concurrent source for any non-private torrent (not
  just trackerless/backstop) - revises design_docs/0036. Prerequisite surfaced along
  the way: BEP 27's "private" flag was never parsed anywhere in this codebase, so
  before DHT/PEX can run unconditionally they need a real gate to respect it (a
  private-tracker torrent must never be DHT/PEX-exposed) - folded into the same piece
  of work rather than done separately.

**Confirmed root cause #2 (2026-09-06)**: a qBittorrent trackers-tab screenshot for
the same torrent shows tiers 0, 2, 3, 4, 6, 7, 8, and 12 *all* independently
"Working," each with its own distinct, non-duplicate seed/peer/leech count (e.g.
397/230/174 for tracker.renfei.net vs. 200/309/139 for opentrackr) - proving
qBittorrent/libtorrent does not implement strict "stop at the first working tracker"
BEP 12 fallback the way `MultiTrackerClient` (design_docs/0022) does. It announces to
every reachable tracker independently, every cycle, and aggregates - the same
concurrent-sources-not-fallback-chain pattern as the DHT item above, just applied to
trackers. Raises this item's priority: it's not just "maybe diminishing returns" (the
original open question), it's a confirmed real contributor for this torrent
specifically. Sequencing decision (2026-09-06): land the DHT-concurrency work first,
then revisit this as a follow-up rather than bundling both into one change.
  - **UI idea to fold in when this is picked up** (2026-09-06, from a qBittorrent
    screenshot): qBittorrent's own trackers tab also lists DHT/PeX/LSD as rows
    alongside real trackers, each with its own peer/seed/leech counts. Worth
    considering a similar reshape here once tracker concurrency lands - a summary
    panel (aggregate counts, mirroring the current collapsed "N trackers working"
    line) plus a new detail view listing every individual tracker's own live
    seeders/leechers/peers (data already captured in `TrackerStatus` - see
    `design_docs/0031` - just never surfaced for a working tracker today, only
    hidden in a tooltip on non-working ones).
- DHT routing-table sparseness — see the existing item below (21 vs. 379 node case).
  Revisit as part of this investigation: a sparse table would compound the item above.
- No LSD implementation — see the existing item below. Minor, LAN-only contributor,
  low priority relative to the two items above.

- **DHT service status doesn't distinguish "healthy" from "bootstrapped but sparse."** —
  **Done (2026-09-01)**, see `design_docs/0059`'s own DEGRADED-state addendum:
  `TorrentEngine.serviceStatuses()` now reports `DEGRADED` (not `RUNNING`) whenever
  `DhtNode.isDegraded()` - the routing table has fewer than `MIN_HEALTHY_NODE_COUNT` (8) known
  nodes, the same threshold `refreshRoutingTable()` already uses to decide "still too sparse."
  Was: `TorrentEngine`'s DHT service status ([[0059-service-status]]) reported `RUNNING` as soon
  as `dhtNode != null` (construction succeeded), with no distinction from "enabled, but the
  routing table has stayed tiny ever since" - a real, now twice-observed state (design_docs/
  0028's "port 6881" debugging trail, and again 2026-08-30: 21 DHT nodes known vs. qBittorrent's
  379 on the same network).
  - **Root cause confirmed for one real case (2026-08-30)**: a dev machine's DHT node stayed at
    0-ish known nodes indefinitely (over a minute post-startup) while running via `mvn quarkus:dev`
    in IntelliJ, blocking every trackerless magnet's metadata fetch (`0 peer(s) tried` - DHT
    lookup had nothing to try). Diagnosed by sending a raw KRPC `ping` directly to two of the
    three hardcoded bootstrap hosts (`Bootstrap.DEFAULT_HOSTS`) on UDP port 6881 - both timed
    out with no response, while plain outbound UDP to unrelated hosts/ports (NTP, DNS) worked
    fine, isolating the block to port 6881 specifically (`DhtNode` reuses `ourListenPort`,
    default 6881, for its UDP socket too). **Confirmed fixed** by setting
    `grimtorrenter.listen-port=7881` in `application.properties` and restarting - DHT reached 16
    nodes and the same magnet found 9 peers. Router/ISP traffic-shaping specifically targeting
    port 6881 (BitTorrent's well-known default) is a plausible, fairly common explanation.
    Worth eventually surfacing as a hint somewhere a user would see it (a Services page tooltip,
    or a note next to the DHT status) when node count stays near zero for a while - "try a
    non-default listen port" is a cheap, high-value troubleshooting step that's currently
    undiscoverable without exactly this kind of manual investigation.
  - **The 21-vs-379 node-count gap itself is now addressed** (2026-08-30): periodic bucket
    refresh plus a real ping-then-evict replacement policy, see
    design_docs/0028's own 2026-08-30 addendum. The `DEGRADED`-state idea above is still open
    (a low count is no longer expected to persist indefinitely, but the status endpoint still
    doesn't distinguish "still filling in" from "genuinely stuck").
- **No LSD (Local Service Discovery, BEP 14)** - noticed via the same 2026-08-30 comparison
  (qBittorrent reports DHT/PEX/LSD all active; GrimTorrenter has no LSD implementation at
  all). Only ever finds same-LAN peers, so it's a minor contributor to peer-count gaps at
  best, not a priority on its own - noted for completeness alongside the DHT item above.
- Multi-select on the torrent list — checkboxes (or shift/ctrl-click) to select several rows
  at once, then bulk Pause/Resume/Remove across the selection, rather than one row (or the
  existing global Pause all/Resume all) at a time.
- ~~Roll the blueprint registration-mark corner treatment out to other panels/cards
  app-wide.~~ **Done for Events/Services (2026-09-05)** — see `design_docs/0045`'s own
  2026-09-05 addendum. Torrent list and torrent-detail deliberately excluded, not deferred:
  the torrent list's full-bleed layout is load-bearing for the docked detail panel
  (`design_docs/0043`), and both are dense multi-column/data views where the guide's own
  "density is respect" principle argues against a narrower framed treatment.
