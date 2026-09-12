# GrimTorrenter — TODO

Running list of ideas/requests to come back to later. Not commitments, not scoped, not
scheduled - just a place to jot something down before it's forgotten. Add items freely;
nothing here gets acted on until it's explicitly picked up.

## Peers/Trackers/General detail — qBittorrent-parity backlog (2026-09-10)

Scoped by walking qBittorrent's General/Trackers/Peers screens field-by-field against what
this engine already tracks vs. what's new work - see `design_docs/0031` for the existing
endpoints these all build on. Grouped by actual value for a long-running, power-user-facing
server, not by build effort - a few of qBittorrent's own fields are closer to "because we can"
than something that'd change a decision someone makes.

### High value - real gaps for a long-running server

- ~~**Persistent lifetime stats** - lifetime Downloaded/Uploaded/Share Ratio, Time Active, and
  Completed On, all surviving an engine restart. Today `bytesDownloaded`/`bytesUploaded`/etc.
  reset to zero on every restart (`design_docs/0031`'s own note); `completedAtEpochMillis`
  already exists in `TorrentSession` (built for seeding limits) but isn't persisted or
  exposed. The main open design question is where/how often to persist (new marker file(s)
  per torrent, matching the existing one-marker-per-concern pattern - state/addedAt/
  seeding-limit-override markers already work this way) and whether it survives a
  "remove but keep files, re-add later" the same way the seeding-limit-override marker does.~~
  **Done (2026-09-10)** - see `design_docs/0064` (the three metrics and their marker) and
  `design_docs/0065` (relocated, alongside every other per-torrent marker, out of the download
  directory into config-side storage).
- ~~**Wasted bytes** - data received that failed a piece hash check and got discarded. Real
  diagnostic signal (bad peers, a flaky link, disk corruption), not decoration. New counter,
  not persistence - hooks into wherever `PieceManager` currently discards a failed piece.~~
  **Done (2026-09-10)** - see `design_docs/0066`. Persisted after all, joining
  `PersistedLifetimeStats` as a fourth field once that marker/flush machinery already existed.
- ~~**Connection direction (incoming vs. outgoing)** - not tracked in `PeerConnection` at all
  today. Genuinely diagnostic for a self-hosted box: seeing inbound connections is real
  evidence port-forwarding/reachability is working.~~ **Done (2026-09-10)** - see
  `design_docs/0066`.
- ~~**Peer-source attribution (tracker/DHT/PEX/LSD)** - which source found each connected peer,
  and per-source counts (unlocks qBittorrent's DHT/PeX/LSD pseudo-tracker rows with their own
  seed/peer counts, and the X/H/L-style peer flags). Real new state - `PeerConnection`/
  `PeerSnapshot` has no notion of discovery source today. Tells a power user whether their
  peer-discovery config is actually pulling weight.~~ **Done (2026-09-10)** - see
  `design_docs/0066`. First-source-wins, not per-source counts - a peer-discovery health
  summary (aggregate counts per source) remains unbuilt, see the still-open UI idea below.

### Medium value - useful, secondary

- ~~Per-peer Progress % (iterate `peerHasPiece` across pieces - cheap) and Relevance (pieces
  they have that we still need - moderate composition). Explains *why* a peer is slow.~~
  **Done (2026-09-10)** - see `design_docs/0067`.
- ~~Average lifetime speed - derived once the persistent lifetime stats above exist
  (lifetime bytes / lifetime active time), not a separate thing to build.~~ **Done
  (2026-09-10)** - see `design_docs/0067`.
- ~~Tracker "Peers" column - size of the peer list returned in that announce response
  (`TrackerResponse.peers().size()`, already available, just not recorded) - a rough
  popularity/health signal, cheap to add.~~ **Done (2026-09-10)** - see `design_docs/0067`.
- ~~Tracker "Re-announce In" countdown - `nextAnnounceAt - now`, derivable client-side from
  data the Trackers tab already has.~~ **Done (2026-09-10)** - see `design_docs/0067`.
- Per-torrent bandwidth/connection limits - a control, not a metric (only global + scheduled
  limits exist today, `design_docs/0042`/`0046`). Real feature value for a multi-torrent
  server (stop one torrent starving the others) but a different kind of work from the metrics
  above - deserves its own decision if picked up, not a metrics-bundle add-on.

### Low value - "because we can"

- Client name (BEP 20 peer-id decode) - informational only, doesn't change a decision.
- Comment / Created By / Created On (`.torrent` metadata, not parsed anywhere today) -
  provenance trivia about the file, not about how the download/seed is performing.
- Tracker "Updating..." transient status (a fourth `TrackerStatus.State` value) - cosmetic
  polish on a state that exists for about a second.
- Tracker "Times Downloaded" (BEP 3's optional `downloaded` response field) - many real
  trackers don't populate it; a column that's blank half the time.

### Skip entirely

- GeoIP country flags on the Peers tab - real ongoing cost (a GeoIP database to bundle/
  license/keep current) for zero operational insight.
- Peers tab "Files" column (which files a peer is currently sending/receiving) - real
  composition work (piece-to-file overlap × per-peer requested-piece state) for a niche
  display.
- Info Hash v2 on the General tab - always "N/A", this engine has no BitTorrent v2/hybrid
  support. Not worth a permanently-empty field.
- Peers tab "Connection type" (TCP/µTP) column - see the µTP item below; excluded today only
  because there's exactly one type to show.

- **µTP (BEP 29) transport support** - the peer connection layer has been TCP-only
  (`java.net.Socket`) since the very first peer-connection design doc (`design_docs/0015`);
  never a deliberate TCP-vs-µTP decision, just the natural default that was never revisited.
  Two real costs, not just a missing UI column: (1) can't reach/be reached by peers that are
  µTP-only for a given direction, shrinking the effective peer pool somewhat; (2) no LEDBAT
  congestion backoff, so this client is more likely than a µTP-capable one to saturate a
  shared home connection and cause latency spikes for other traffic on it - a real concern
  for a long-running self-hosted box. Raised while scoping the Peers tab's qBittorrent-parity
  columns (2026-09-10) - excluding a "Connection type" column made sense today only because
  we have exactly one type; this is the underlying gap that decision surfaced. A full BEP 29
  implementation (reliable transport over UDP with its own congestion control) is a
  substantial, self-contained subsystem - not something to bundle into any of the metrics/UI
  work also being scoped around the same time. Needs its own design doc if picked up.
  **When this is picked up, add the Peers tab's "Connection type" (TCP/µTP) column in the
  same pass** - deliberately excluded from the current peers-tab scoping precisely because
  there's only one type to show today; once µTP exists the column earns its place.
- Notification service (emails, or something else yet to be defined)
- Run a user-configured script automatically when a torrent completes
- ~~UI bug: refreshing the page while the torrent-detail side panel is open
  (`/torrents/:infoHash`) shows a "Resource not found" error instead of reloading the app with
  the panel still open.~~ **Done (2026-09-11)** - see `design_docs/0068`. Confirmed root cause:
  no SPA fallback to `index.html` for non-API routes, so a refreshed client-side route 404'd at
  the Quarkus level before ever reaching Angular's router.
- ~~Authentication for the REST API/UI - currently completely unauthed.~~ **Done
  (2026-09-07)** - see `design_docs/0061`. Raised by the user: the REST endpoint is one of
  this implementation's real strengths, but that's undermined if it can't be exposed to the
  internet safely. Manual browser verification of the WebSocket handshake-header check
  (`Sec-WebSocket-Protocol`) is the one remaining open item, per that doc's own notes.
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
  **Done (2026-09-06)** - see `design_docs/0036`'s own 2026-09-06 revision:
  `TorrentSession.discoverPeersViaDht()` now runs as an independent periodic task for
  any non-private torrent DHT is eligible for, regardless of tracker health, replacing
  the old backstop-only/trackerless-only special-casing. Included as a prerequisite in
  the same change: BEP 27's "private" flag was never parsed anywhere in this codebase
  before this - now parsed (`TorrentMetadata.isPrivate()`) and gates both DHT
  (`dhtEligible()`) and PEX (`extensionsToAdvertise()`/`sendPexUpdates()`), closing a
  real, previously-live privacy gap for private-tracker torrents.
  **Verified end-to-end (2026-09-06)**, after the two connection-layer bugs below were also
  found and fixed: the same torrent reached 20 connected peers (up from 0-1), multiple peers
  actually unchoking and sending real data (one alone: ~26 MB), and genuine throughput -
  96.0 KB/s at 2% and climbing. The full chain (DHT discovery, continuous connection refill,
  no duplicate/OOM waste, real unchoke/download) confirmed working together on the user's
  real system.

**Confirmed root cause #2 (2026-09-06)**: deployed the DHT-concurrency
fix above and re-tested - DHT peer discovery genuinely works now (`discoverPeersViaDht()`
found 275 peers for the same torrent, confirmed via its own new DEBUG log line), but
connected-peer count still only reached 1. Root cause: `TorrentSession.fillConnections()`
(and its `onDisconnected` counterpart) is a one-shot burst, not a continuously-replenishing
pool:
- `fillConnections()` only ever runs from four external triggers (initial `start()`, a
  successful tracker `reannounce()`, a `discoverPeersViaDht()` tick, a PEX/`addKnownPeers`
  batch) - never in response to an individual `attemptConnect()` failing or a connected peer
  disconnecting. `PeerListener.onDisconnected()` removes the connection from the set and
  updates byte counters, but never calls `fillConnections()` to backfill the freed slot.
  Given most candidates fail within 5-20s (the normal churn confirmed via root cause #1's
  logging), the very first burst of up to `MAX_CONNECTIONS` (30) attempts mostly fails fast,
  and nothing tries a replacement until the next external trigger - up to
  `dhtReannounceIntervalSeconds` (default 300s) away for DHT.
- **Compounds with a second gap**: a failed candidate is never excluded from future rounds -
  `fillConnections()`'s filter only excludes *currently-connected* addresses, not
  previously-attempted-and-failed ones, so a later refill can waste slots re-attempting known-dead
  addresses instead of reaching fresh candidates from the (large) known pool.
- **Net effect**: it doesn't matter how many candidates DHT/tracker/PEX hand over - the
  connection layer only ever actually *uses* about `MAX_CONNECTIONS` per external trigger,
  the rest sit idle in `knownAddresses`. This is why 275 known DHT peers produced 1
  connection, and it would equally undercut the tracker-concurrency item below (more
  candidate addresses hitting the same bottleneck, not more actual connections) - so this
  jumps ahead of that item; confirmed with the user (2026-09-06) as the next thing to fix.
  **Done (2026-09-06)** - `fillConnections()` now excludes a new permanent-for-the-session
  `failedAddresses` set (populated by `attemptConnect()`'s own catch block) alongside
  currently-connected addresses, and both `attemptConnect()`'s failure path and
  `PeerListener.onDisconnected()` call `fillConnections()` again, so a freed slot reaches a
  fresh candidate immediately rather than waiting for the next external batch.
  - **Caused a real production `OutOfMemoryError` within hours of shipping, fixed the same
    day** - see `design_docs/0017`'s own same-day correction. The refill-on-failure trigger
    above combined with `fillConnections()`'s old `connections.size()`-based slot check (which
    never counted in-flight attempts) let a burst of near-simultaneous failures each spawn up
    to `MAX_CONNECTIONS` more attempts independently - an uncontrolled cascade, not the small
    bounded overshoot that check's own comment had accepted. Fixed with a `Semaphore
    connectionSlots`, acquired atomically per attempt (outbound *and* inbound - the latter had
    its own separate, equally unaware size check) and released on failure/disconnect, so the
    total in-flight-or-established count structurally can't exceed `MAX_CONNECTIONS` no matter
    how many threads race to refill at once.
  - **That fix alone wasn't sufficient - a second bug, found the same day on the very next
    real run**: 0 connected peers despite a healthy tracker and DHT genuinely finding peers;
    logs showed the *same* address attempted over a dozen times within 24ms. `connectionSlots`
    bounded the total, but didn't stop several concurrent `fillConnections()` calls from each
    independently picking the *same* untried candidate before any of them had claimed it -
    wasting the budget on one address instead of spreading across the pool. Fixed with a
    second set, `inFlightAddresses`, claimed atomically at selection time via `Set.add()`'s
    own return value. `TorrentSessionTest`'s regression test was renamed
    (`neverDuplicatesOrExceedsMaxConnectionsEvenUnderABurstOfFailures`) and strengthened to
    assert every one of 60 candidates is attempted *exactly* once, not just that the peak
    stays under 30. See `design_docs/0017`'s own second same-day correction.
  - **Retry a failed peer after a cooldown** - raised and deliberately deferred while scoping
    the fix above: `failedAddresses` exclusion is currently permanent for the whole session,
    never retried, confirmed with the user as the simpler option over a
    timestamp-per-address/expiry mechanism. Real swarms do have transient failures (NAT
    timing, a peer briefly offline) that a cooldown-based retry would eventually recover from
    and this doesn't - worth revisiting if evidence shows it actually matters in practice,
    now that DHT/tracker/PEX keep the candidate pool large and continuously refreshed anyway.

**Confirmed root cause #3 (2026-09-06)**: a qBittorrent trackers-tab screenshot for
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
  **Done (2026-09-06)** - see `design_docs/0022`'s own 2026-09-06 revision:
  `MultiTrackerClient.announce()` now announces to every configured tracker concurrently
  on every call (virtual-thread-per-task, same pattern `TorrentEngine.raceOneRound()`
  already used for magnet peer racing), aggregating a deduplicated union of peers and the
  minimum interval among the successes - not BEP 12 tier fallback (stop at the first
  success) any more. Considered and explicitly deferred: giving each tracker its own
  independently-timed schedule (the same shape `discoverPeersViaDht()` uses for DHT) -
  correctly bigger scope and risk than confirmed with the user was worth taking on the same
  night as the DHT/connection-layer fixes above; kept the simpler shared-cycle model
  instead. **Verified (2026-09-06)**: the same real torrent (21 declared trackers, 1 working
  before this fix) now shows 9 trackers `WORKING` - the rest are genuinely dead (matching the
  qBittorrent screenshot's own "Host not found"/"timed out"/"Forbidden" trackers), not a
  regression.
  - **UI idea, still open** (2026-09-06, from a qBittorrent screenshot): qBittorrent's own
    trackers tab also lists DHT/PeX/LSD as rows alongside real trackers, each with its own
    peer/seed/leech counts. Worth considering a similar reshape here - a summary panel
    (aggregate counts, mirroring the current collapsed "N trackers working" line) plus a
    new detail view listing every individual tracker's own live seeders/leechers/peers
    (data already captured in `TrackerStatus` - see `design_docs/0031` - just never
    surfaced for a working tracker today, only hidden in a tooltip on non-working ones).
    More trackers now show real WORKING status (not perpetual UNKNOWN) after the fix above,
    so this UI gap is more visible/valuable to close than before.
  - **Per-tracker independent scheduling** - the more-correct alternative deferred above.
    Each tracker on its own interval (matching what it actually reports), architecturally
    the same shape as `discoverPeersViaDht()`, but a substantially bigger change:
    `MultiTrackerClient` would own scheduling and push peers back asynchronously instead of
    `TorrentSession` calling a synchronous `announce()`; STARTED/STOPPED/COMPLETED event
    fanout across independently-scheduled trackers needs real design; most of
    `TorrentSessionTest`'s tracker-related coverage (built around one synchronous
    `reannounce()` cycle) would need rethinking, not just renaming. Worth revisiting if the
    shared-cycle model's soft politeness cost (some trackers polled more often than their
    own stated interval) turns out to matter in practice.
- DHT routing-table sparseness — see the existing item below (21 vs. 379 node case).
  Revisit as part of this investigation: a sparse table would compound the item above.
- ~~No LSD implementation — see the existing item below.~~ **Done (2026-09-09)** — see
  `design_docs/0062`.

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
- ~~**No LSD (Local Service Discovery, BEP 14)** - noticed via the same 2026-08-30 comparison
  (qBittorrent reports DHT/PEX/LSD all active; GrimTorrenter has no LSD implementation at
  all). Only ever finds same-LAN peers, so it's a minor contributor to peer-count gaps at
  best, not a priority on its own - noted for completeness alongside the DHT item above.~~
  **Done (2026-09-09)** - see `design_docs/0062`. Picked up as the last remaining
  peer-discovery item, ahead of the queued style-guide/usability pass in `style/`.
- Multi-select on the torrent list — checkboxes (or shift/ctrl-click) to select several rows
  at once, then bulk Pause/Resume/Remove across the selection, rather than one row (or the
  existing global Pause all/Resume all) at a time.
- UI themes — user-selectable themes beyond the current light/dark split. Unscoped: how many,
  whether custom/user-defined, how they interact with the existing manual System/Light/Dark
  switcher (design_docs/0032's addendum).
- ~~Roll the blueprint registration-mark corner treatment out to other panels/cards
  app-wide.~~ **Done for Events/Services (2026-09-05)** — see `design_docs/0045`'s own
  2026-09-05 addendum. Torrent list and torrent-detail deliberately excluded, not deferred:
  the torrent list's full-bleed layout is load-bearing for the docked detail panel
  (`design_docs/0043`), and both are dense multi-column/data views where the guide's own
  "density is respect" principle argues against a narrower framed treatment.
