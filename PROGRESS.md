# GrimTorrenter — Progress

Snapshot of what's built and what's left. Rationale for any decision
mentioned here lives in `design_docs/` (one file per decision, linked
below) — this file is a status/TODO list, not a source of truth for *why*.

## Current state

**Phase 1 (MVP) and Phase 2 (usable day to day) are functionally
complete**, per the phased scope in [[0009-phased-scope]]:

- Full engine: bencode, metainfo parsing, HTTP + UDP tracker announce —
  concurrent to every configured tracker, aggregating whatever succeeds
  ([[0022-multi-tracker-fallback]], including its own 2026-09-06 revision
  away from BEP 12 tier fallback) — peer wire protocol, piece/block
  management with SHA-1 verification, disk I/O (single + multi-file
  torrents), sequential piece selection, real seeding (choking algorithm,
  block serving).
- Resume state persists across restarts — a restart re-verifies on-disk
  data in the background and picks torrents back up in whatever
  running/paused state they were left in ([[0026-resume-state-persistence]]).
- Magnet link support is fully usable end-to-end via embedded trackers —
  BEP 10 extension protocol, BEP 9 `ut_metadata` fetch, wired into the
  same `addTorrent` pipeline every `.torrent` upload uses
  ([[0028-magnet-links-and-dht]]).
- Web UI: upload a `.torrent` file or paste a magnet link, live
  progress/rate/peer-count/upload display, pause/resume/remove (with an
  optional "delete downloaded files too"), optimistic "Processing" row
  and an explicit "already added" signal on upload
  ([[0029-optimistic-upload-feedback]]).
- **Mainline DHT (BEP 5)** — node ID, k-bucket routing table, KRPC over
  UDP (ping/find_node/get_peers/announce_peer), bootstrap, iterative node
  lookup ([[0028-magnet-links-and-dht]]). Wired into `TorrentSession` as a
  routine, periodic, concurrent peer source (live-tunable interval,
  default 300s) for **any non-private torrent DHT is eligible for** —
  tracker-bearing or not, regardless of tracker health, not just a
  trackerless-only mechanism or a last-resort backstop for total tracker
  failure ([[0036-dht-backstop-for-tracker-bearing-torrents]], including
  its own 2026-09-06 revision). BEP 27's "private" flag is parsed and
  gates both DHT and PEX. A `GET /api/dht/status` endpoint exposes node
  count.
- Full per-torrent detail view: tabbed Pieces/Files/Peers/Trackers, each
  a self-contained on-demand endpoint
  ([[0031-torrent-detail-endpoints]]), plus per-entry pending/error
  feedback on Pause/Resume/Remove ([[0033-per-entry-action-feedback]]).
- **Visual design pass** — done, against a user-supplied style guide
  reconciled with vanilla PrimeNG
  ([[0032-style-guide-and-primeng-theme]], [[0033-per-entry-action-feedback]],
  [[0034-ink-weight-status-display]],
  [[0035-spacing-table-density-and-empty-state]]).
- Re-adding a previously-removed-but-data-kept torrent now reuses and
  re-verifies its existing data in the background instead of silently
  re-downloading it from scratch
  ([[0037-reuse-existing-data-on-readd]]).
- **Incoming peer connections** — a shared `PeerServer` (one per engine, not
  per torrent) accepts connections peers initiate to us and routes them to
  the right torrent by info hash, operator-toggleable like DHT
  ([[0038-incoming-peer-connections]]).
- The detail header shows `dhtBackstopActive` — now purely a tracker-health
  signal (true whenever the tracker's own last announce failed), distinct
  from `usesDht` (whether DHT is actually eligible as a peer source for
  this torrent at all) — the UI-visibility question left open in
  [[0036-dht-backstop-for-tracker-bearing-torrents]]
  ([[0039-dht-backstop-visibility]], reflecting that doc's own 2026-09-06
  revision).
- **Peer Exchange (BEP 11)** — connected peers gossip who else they're
  connected to (IPv4 `added`/`dropped` only, session-wide delta every 60s),
  supplementing tracker/DHT discovery ([[0040-peer-exchange]]). First
  Phase 3 item done.
- **Live settings store** — a JSON-backed `SettingsStore`
  (`grimtorrenter.config-directory`, independently mountable from
  `download-directory`) for user-editable settings that persist and take
  effect without a restart. `dhtEnabled`/`acceptIncomingConnections`
  migrated onto it as the first fields (both still apply on restart only,
  not live - see the doc) ([[0041-live-settings-store]]).
- **Upload/download rate limiting** — a global (not per-torrent) shared
  token bucket per direction, genuinely live via the settings store above;
  `PeerConnection` blocks on it before sending/after receiving real piece
  data ([[0042-rate-limiting]]). No REST endpoint or UI to set it yet -
  `settings.json` only, for now.
- **App shell** — a persistent header (aggregate ↓/↑ rate, a DHT status
  pill, a settings link), a left sidebar with a status filter nav
  (All/Downloading/Seeding/Paused/Error/**Harvest**, each with a live
  count), and a footer (torrent count, aggregate rate, ratio, disk free
  space via a new `GET /api/system/disk-usage` endpoint)
  ([[0043-app-shell-and-filtering]]).
- **Torrent list**: a name-search box composing with the sidebar's status
  filter, sortable columns (Name/Size/Status/Progress), a per-row
  right-click context menu (Pause/Resume, copy magnet link, remove), and
  global Pause all/Resume all toolbar actions ([[0043-app-shell-and-filtering]]).
- **Torrent detail is now a non-modal slide-out drawer**, not a full-page
  navigation — still routed at `/torrents/:infoHash` (bookmarkable, closes
  on back-button, survives a refresh) but as a *child* of the list route,
  so the list stays mounted and fully interactive behind it. The four
  detail tabs (Piece map/Files/Peers/Trackers) were reworked from wide
  tables to stacked cards to actually fit the drawer's ~430px width
  ([[0044-torrent-detail-drawer]]).
- **A real `/settings` page** — a `GET`/`PUT /api/settings` REST endpoint
  over the live settings store, and a frontend form grouped by topic
  (Network: DHT/incoming connections, restart-required; Rate limiting:
  upload/download caps in KiB/s, live). Built so a future settings group
  is a self-contained addition rather than a rework — each group is its
  own component/form-builder pair, saved together in one atomic `PUT`
  ([[0045-settings-page]]). The rate-limit fields use a paired
  "Unlimited" checkbox that disables the number field, rather than
  relying on a "0 = unlimited" hint text.
- **A daily off-hours rate-limit schedule** — a single time window (can
  cross midnight), the same every day, with its own upload/download
  limit pair (either direction, not required to be higher than the base
  limit) that takes over while it's active. Lives inline in the Rate
  limiting settings group; resolved live via the same
  `ToLongFunction<Settings>` seam `RateLimiter` already reads its base
  limit through, so no new polling/background component was needed
  ([[0046-rate-limit-schedule]]).
- **Message Stream Encryption (MSE)** — hand-rolled RC4 + Diffie-Hellman (no new dependency;
  `grimtorrenter-engine` still has zero production dependencies), a global
  `DISABLED`/`PREFERRED`/`REQUIRED` mode (default `PREFERRED`, live — takes effect on the next
  connection, no restart), wired into both outbound `PeerConnection.connect()` (with a
  fresh-connection fallback to plaintext in `PREFERRED` mode) and inbound `PeerServer`
  (peek-and-branch detection, SKEY matching to recover which torrent an obfuscated incoming
  connection is for). Last item from the original Phase 3 list
  ([[0052-message-stream-encryption]]). Last Phase 3 item done.
- **Rate-limit burst allowance** — a configurable `rateLimitBurstSeconds` widens
  `RateLimiter`'s token-bucket capacity beyond the previous hardcoded "one second's worth of
  the current limit," so bursty traffic can spend saved-up bandwidth faster than the
  steady-state rate alone would allow. One shared value for both directions and both the base
  and scheduled limit, live like the rate limits themselves; 0 (the default, and what any
  pre-existing `settings.json` resolves to) means the original 1-second behavior, not "no
  burst" ([[0053-rate-limit-burst-allowance]]).
- **Seeding limits** — stop a torrent from seeding once it crosses a ratio and/or time limit,
  whichever first. A global default (both disabled by default), **with a per-torrent
  override** — the first per-torrent-override mechanism in this codebase, via a new
  `key=value` marker file per torrent directory (`.grimtorrenter-seeding-limit-override`) and
  `GET`/`PUT /api/torrents/{infoHash}/seeding-limits`. Checked by a new engine-wide scheduled
  task that reuses `pauseTorrent()` for the actual stop, so persistence stays correct for
  free. Set from a new "Seeding limits…" row context-menu item opening a `p-dialog` — the
  first modal form in this frontend — with a 3-state "use default / custom / no limit"
  control per metric. Neither metric survives a process restart, matching the existing
  (already non-persisted) upload/download byte counters they're computed from
  ([[0054-seeding-limits]]).
- Along the way: fixed a pre-existing bug in the per-row right-click context menu
  ([[0043-app-shell-and-filtering]]) — wrong popup position (the table's own scrollable
  wrapper was clipping/mispositioning it) and a previously-open row's menu not closing when a
  different row was right-clicked (a right-click fires no `click` event, so PrimeNG's own
  "click outside closes it" logic never saw it). Both surfaced while testing the seeding-limits
  context-menu item, fixed alongside it ([[0054-seeding-limits]]).
- **Library events** (picked from `TODO.md`, 2026-08-26) — a curated feed for managing the
  library (torrent added/completed/errored/removed, auto-paused by a reached seeding limit),
  deliberately not a raw debug log. New engine-side `EventStore`/`LibraryEvent`/`EventType`
  (`grimtorrenter-engine`, `events` package), backed by `JsonLinesEventStore`
  (`grimtorrenter-app`) — one JSON-Lines file per calendar day under
  `{config-directory}/events/`, pruned hourly (and once at startup) against a new live, **never
  unlimited** `Settings.eventLogRetentionDays` (default 30; 0/negative is silently normalized
  to 30 by `Settings`' own compact constructor, not rejected — an unbounded event log is exactly
  what this field exists to prevent). Delivered over the existing `/ws/torrents` WebSocket (a
  new `"event"` message type) for live push, plus `GET /api/events` (optional `?infoHash=`
  filter) for scrollback. A new **Events** sidebar page (not a torrent-detail-drawer tab, since
  most of these are things a user wasn't watching when they happened) and a new **Event log**
  settings group. ([[0055-library-events]])
  - **Deferred from this pass**: `TRACKER_UNREACHABLE`/`TRACKER_RECOVERED` (no
    listener/callback seam exists yet on `TrackedTrackerClient`/`TrackerStatus` — only a
    poll-on-demand REST read — designing that seam is a real decision, not just plumbing) and a
    distinctly-labeled `MAGNET_RESOLVED` (a resolved magnet already produces an `ADDED` event
    via the shared `addTorrent()` pipeline, just not one that says "via magnet"). See
    [[0055-library-events]]'s own "Deferred from this pass" section.
  - **Real bug found in production and fixed (2026-08-26)**: the same already-long-since-
    complete torrent recorded a fresh `COMPLETED` event on every server restart, forever —
    `DOWNLOADING` → `SEEDING` alone turned out not to mean "just completed": restoring an
    already-complete torrent replays that exact transition on every restart too (`
    enterDownloading()` unconditionally re-checks completion on every `start()`). Fixed with a
    new `TorrentSession.wasCompleteOnRestore()` flag, set once during restore-time
    re-verification; `TorrentEventListener` now also requires `completedAtEpochMillis() == 0`
    and `!wasCompleteOnRestore()` before recording `COMPLETED`. New regression coverage:
    `TorrentEventListenerTest` (`grimtorrenter-app`) and three new `TorrentSessionTest` cases
    (`grimtorrenter-engine`). See [[0055-library-events]]'s own dated correction.
  - **Remaining known test gap**: `TorrentEventListener`'s `ERROR` mapping still has no
    dedicated test — no cheap, deterministic way to drive a real `TorrentSession` into `ERROR`
    the way seeding limits' degenerate-zero trick covers `SEEDING`/`STOPPED`.
  - **`SERVER_STARTED` added (2026-08-26, user request)** — the first genuinely engine-wide
    library event (`infoHash`/`torrentName` both null), recorded once at the end of
    `TorrentEngine`'s constructor (equivalent to "the app started," since exactly one engine
    exists per running process in production). Lets a timeline of events be correlated against
    process restarts — the motivating case was an auto-updater like Watchtower recreating the
    container unattended.
- **Watch folder** (picked as the explicit next thing to build, 2026-08-26, ahead of everything
  else on the list at the time) — drop a `.torrent` file into a new configurable
  `grimtorrenter.watch-directory` and it's auto-added, no manual upload needed. Polled every 30
  seconds (not `WatchService`/native filesystem events — unreliable through Docker bind mounts
  on macOS/Windows) on the same shared daemon thread `checkSeedingLimits()` already used,
  generalized from `seedingLimitScheduler` into a `maintenanceScheduler` both now run on. A file
  is only ever read once its size/mtime is unchanged across two consecutive ticks (a partial-
  write guard). Successes move to `watch-directory/added/`, failures to `watch-directory/failed/`
  (both cleaned up on a new live, **never-unlimited** `Settings.watchFolderRetentionDays`,
  default 7 days — same silent-normalize-anything-below-1 treatment as `eventLogRetentionDays`),
  with each move guarded by recreating the destination directory immediately beforehand in case
  either was deleted since the last tick. `addTorrent()` gained an internal, engine-only *source*
  concept so a watch-folder-triggered `ADDED` event's message reads "Added via watch folder,"
  distinguishing it from a direct upload; a failed add records a new `ERROR` event naming the
  file and reason. A new **Watch folder** settings group (enable toggle + retention field).
  ([[0056-watch-folder]])
  - **Deferred**: magnet-link files (e.g. a `.magnet` text-file convention) and a configurable
    poll interval — `.torrent` files only and a fixed 30-second cadence for this first pass.
- **Service status** (picked from `TODO.md`, 2026-08-30) — DHT and the inbound peer server
  failing to bind at startup previously only logged a `WARNING`, with no way for a user to know
  short of reading server logs. A new **Services** sidebar page lists both as a fixed
  RUNNING/DISABLED/FAILED checklist (`GET /api/system/services`, backed by a new
  `TorrentEngine.serviceStatuses()`), scoped deliberately to engine-wide singleton subsystems
  only — per-torrent status stays on the torrent itself. The same bind-failure catch block that
  drives this also records a normal library event (`DHT_UNAVAILABLE`/
  `PEER_SERVER_UNAVAILABLE`), so the same failure shows up with a timestamp in the Events tab.
  A sidebar nav badge shows the live failed-service count (hidden when zero); once loaded and
  all-clear, both the nav item and each `RUNNING` row on the Services page show an explicit
  accent-colored checkmark, not a literal green — this app's style guide deliberately avoids a
  red/green severity palette in favor of one reserved alarm color plus ink weight
  ([[0032-style-guide-and-primeng-theme]]), flagged and confirmed with the user before
  building it that way. DHT/peer server only ever bind once at construction (no retry), so
  "failed" is stable for the process lifetime — no live health-polling loop or
  begin/end event pairing was needed. ([[0059-service-status]])
  - Same pass, ahead of Services: a **JVM heap/CPU footer widget** — `GET
    /api/system/resource-usage` (`com.sun.management.OperatingSystemMXBean`, no new
    dependency) polled into the existing footer alongside disk free space, each stat behind
    its own icon (`pi-database`/`pi-server`/`pi-microchip`) once three numbers made the plain
    text ambiguous. Numbers only, not a graph — the endpoint is a stateless snapshot with
    nothing retained server-side, so a time series would mean either a client-side rolling
    buffer or new backend storage, deferred as a separate decision. ([[0043-app-shell-and-filtering]]'s
    own addendum)
  - **Known gap**: no cheap, deterministic way to force a real DHT/peer-server bind failure in
    a unit test today, so `serviceStatuses()`'s `FAILED` branch and the event recording it
    triggers have no automated coverage yet — same shape as the existing
    `TorrentEventListener` `ERROR`-mapping gap noted above.
- **Row selected highlight** (picked from `TODO.md`, 2026-08-30) — the row whose torrent the
  detail drawer currently has open now gets the style guide's full "Selected row" token (a 2px
  accent left edge plus an 8% accent wash, never a fully filled row), driven from the same
  `route.firstChild`/`NavigationEnd` pattern `TorrentList`'s existing `isDetailOpen` signal
  already uses, so it can't drift from the drawer's real open/closed state. The wash alone
  (the first cut, taken from `TODO.md`'s shorter paraphrase rather than the full
  `STYLE_GUIDE_NOTES.md` token) read ambiguously close to an in-progress torrent's own
  similarly-accent-washed progress underlay — the left edge, added once the user flagged it
  live, is what actually disambiguates the two. ([[0043-app-shell-and-filtering]]'s own
  addendum)
- **Magnet-add reliability, feedback, and a real MSE bug — a single long debugging session
  (2026-08-30)**, started from a user report that adding a magnet did nothing. Three real
  fixes came out of it, in the order they were found:
  1. **`MetadataFetcher` never actually respected the configured `EncryptionMode`** — it went
     through a `PeerConnection.connect()` convenience overload that silently hardcoded
     `EncryptionMode.DISABLED`, so every magnet metadata fetch connected in plaintext even
     with the default `PREFERRED` mode. Genuinely unrelated to the user's actual symptom (the
     block turned out to be connect-level, before any payload — encrypted or not — was ever
     sent), but a real, independently-worth-fixing gap; found and fixed along the way.
     ([[0052-message-stream-encryption]]'s own addendum)
  2. **Total silence on failure.** `TorrentEngine.addMagnet()` returns as soon as a background
     metadata fetch *starts*, not once it succeeds — a total failure (no reachable peer) was
     only ever logged server-side. Added a new `EventType.MAGNET_ADD_FAILED` library event,
     recorded at every failure point, plus a transient pending row in the torrent list
     (extending [[0029-optimistic-upload-feedback]]'s existing mechanism to magnets, which had
     never picked it up) that resolves — success or a toast naming the failure — once the real
     outcome is known, instead of the field just silently clearing either way.
     ([[0060-magnet-add-failure-feedback]])
  3. **The actual root cause, found via a live side-by-side with qBittorrent's own peer list**:
     GrimTorrenter was trying far too few peers to reliably beat ordinary swarm churn (most
     candidates in any real swarm are routinely unreachable at any given moment) — not a
     network-level block, the leading theory for most of the session until qBittorrent's own
     ~76-peer list (several on port 6881 itself) ruled that out directly. Reworked into a
     concurrent, retried, live-tunable design: candidates within a round now race via
     `ExecutorService.invokeAny()` instead of trying sequentially; `fetchMagnetMetadataViaTracker
     ThenAdd()`/`ViaDhtThenAdd()` are now bounded retry loops (re-announcing/re-querying DHT for
     fresh candidates) across an overall time budget, not a single batch; and all three tuning
     numbers (time budget, candidates per round, concurrency ceiling) are now live `Settings`
     fields (a new `LiveResizableSemaphore` makes even the concurrency cap resizable without a
     restart), editable from a new Magnet fetching settings group — confirmed live: the user's
     stalled magnet started downloading once rebuilt. ([[0028-magnet-links-and-dht]]'s own
     2026-08-30 addendum)
  - Also surfaced and fixed a real, unrelated flake in `ManyTorrentsRestoreLoadTest`'s own
    `PeakTrackingSemaphore` while running the full suite — see its own entry above.
  - **Two follow-on gaps identified**, both noticed via the same qBittorrent comparison: ongoing
    DHT peer discovery for an active torrent was one-shot, not periodic (unlike tracker
    reannounce) — **now fixed, see the periodic DHT re-query entry below** — and GrimTorrenter
    has no LSD (BEP 14) at all, still open, logged to `TODO.md`.
- **Periodic DHT re-query for genuinely trackerless torrents (2026-08-30)** — closes the
  one-shot-DHT-lookup gap surfaced above. Previously, a trackerless torrent's peer discovery
  was: one `dhtNode.findPeers(...)` call at add-time (`TorrentEngine.seedFromDhtIfTrackerless()`,
  now removed), then nothing further — the `NoOpTrackerClient` standing in for "no tracker"
  reported a deliberately huge (365-day) interval specifically so `reannounce()`'s own
  scheduling was a no-op for it. `TorrentSession` already had the machinery to do this properly
  for a *related* case — `startViaDhtBackstop()`/`reannounceViaDhtBackstop()`
  ([[0036-dht-backstop-for-tracker-bearing-torrents]]), built for a tracker-bearing torrent
  whose tracker is currently down — just never wired up for genuinely trackerless torrents.
  Added parallel `startViaDht()`/`reannounceViaDht()` methods (same shape, different failure
  semantics: no prior tracker success to consider "failed"), driven by a new live
  `Settings.trackerlessDhtReannounceIntervalSeconds` field (default 300s/5 minutes, exposed in
  the existing Network settings group) instead of reusing the fixed 1800s backstop interval —
  the user's own call, given how much faster qBittorrent's peer count grows; 300s balances that
  against DHT query-etiquette (re-querying the same info hash too often is poor citizenship, and
  the real qBittorrent-speed gap is mostly explained by a much richer routing table, not query
  frequency — see the DHT-sparseness item above). `dhtBackstopActive` is deliberately left
  untouched by the new path — a trackerless torrent doing DHT lookups is its normal operating
  mode, not a degradation. ([[0036-dht-backstop-for-tracker-bearing-torrents]]'s own 2026-08-30
  addendum)
- **DHT routing-table health: periodic bucket refresh + a real replacement policy (2026-08-30)**
  — closes the 21-vs-379-node gap identified in the same qBittorrent comparison. Checked
  directly against libtorrent-rasterbar's own `routing_table.cpp` rather than guessing from the
  BEP 5 spec alone. Two structural gaps this doc's own k-bucket section had already flagged as
  deliberately deferred: bootstrap's one self-lookup only ever explores the neighborhood near
  our own node id, and a full bucket never evicted a stale contact for a better one (the
  ping-then-evict contract existed and was tested, just never wired up). Turned both on:
  `DhtNode.seen()` now actually pings a full bucket's stale contact (off the receive-loop
  thread) and evicts it if unreachable; a new `RoutingTable.mostOverdueBucket()`/
  `randomIdInBucket()` pair drives a periodic `DhtNode.refreshRoutingTable()` tick — reusing
  `NodeLookup` exactly as this doc's own bootstrap section anticipated — on a new live
  `Settings.dhtRefreshIntervalSeconds` field (default 300s, engine-wide via
  `maintenanceScheduler`, a new row in the Network settings group). Persistence across restarts
  deliberately deferred to `TODO.md` — this fixes "stays sparse while running," not cold-start
  speed.
  - **Follow-up fix #1, same day**: the very next real run regressed to 1 DHT node — traced not
    to the replacement policy (initially suspected) but to a gap the fix itself didn't cover:
    this network's reachability to two of the three well-known bootstrap hosts is impaired,
    leaving bootstrap with only one contact, and `refreshRoutingTable()`'s per-bucket refresh
    can't recover from that — `NodeLookup` always seeds itself from what's already known, so it
    just re-queries the same starved handful forever. Fixed: below `MIN_HEALTHY_NODE_COUNT` (8)
    known nodes, `refreshRoutingTable()` now re-runs full bootstrap instead of a narrow bucket
    refresh, giving a poor first attempt a real repeated second chance every
    `dhtRefreshIntervalSeconds` — confirmed helping (1 → 2 nodes), just slowly.
  - **Follow-up fix #2, same day**: a second DEBUG capture showed the *identical* two hosts
    failing again — not flaky, a deterministic, persistent gap for this network, confirmed a
    third time via a direct manual KRPC ping test. Retrying the same 3 hosts could only ever get
    1 real vote. Added two more hosts to `Bootstrap.DEFAULT_HOSTS` (now 5, was 3):
    `dht.libtorrent.org` (a different port, 25401 — real bootstrap hosts don't agree on one;
    confirmed reachable, libtorrent's own host, likely why qBittorrent had hundreds of nodes on
    this same network) and `dht.aelitis.com` (from the actual list libtorrent/qBittorrent
    configures, supplied by the user — didn't respond from this network either, kept anyway for
    the same "may work elsewhere, never hurts" reasoning already applied to the two already-
    struggling defaults). `router.bitcomet.com`, also from that list, no longer resolves at all
    — confirmed independently by the user too, left out as permanently-dead weight.
  - **Follow-up fix #3, same day**: both fixes above still depend on the same handful of
    hardcoded hosts being reachable *right now*, every restart. Raised by the user directly,
    and already logged as a deliberately-deferred item — built the same day instead of waiting.
    Routing-table contacts now persist to a new `.grimtorrenter-dht-nodes` marker file (plain
    `ip,port` lines, no id — the real one always comes back fresh in the verification ping),
    loaded as a warm-start on `createDhtNode()` and saved on the periodic refresh tick plus on
    shutdown. Staleness is resolved for free: a persisted contact is only trusted once it
    actually answers a real ping, exactly like any hardcoded bootstrap host already is — no
    separate verification pass needed. Pinged **concurrently** (`DhtNode.bootstrap(List)`, a
    new overload), unlike `Bootstrap.seedFrom()`'s own sequential loop over the 5 hardcoded
    hosts — a persisted list could be far larger, so a warm start costs roughly one timeout
    regardless of how many contacts were saved, not time scaling with the count.
  - **Follow-up fix #4, same day**: the two DHT marker files (node id, and now the persisted
    routing table) sat directly at the download directory's root — visible clutter next to a
    user's actual torrent folders, unlike every per-torrent marker which correctly lives inside
    that torrent's own subdirectory. Raised by the user directly. Moved into the existing
    `grimtorrenter.config-directory` (already used by `settings.json`/`events/`) — `TorrentEngine`
    gained a new `configDirectory` constructor parameter, added the same way `watchDirectory`
    was ([[0056-watch-folder]]'s own precedent): a new widest constructor, the previous one
    delegating with `configDirectory` defaulted to `baseDownloadDirectory` so every existing
    caller/test is unaffected.
  ([[0028-magnet-links-and-dht]]'s own 2026-08-30 addendum)
- **DHT service status now distinguishes "healthy" from "bootstrapped but sparse"
  (2026-09-01)** — picked from `TODO.md`'s DEGRADED-state item, the one piece the DHT-health
  work above deliberately left open. `TorrentEngine.serviceStatuses()`'s DHT row now reports a
  new `DEGRADED` state (live, re-checked every call, not fixed at construction like
  `RUNNING`/`FAILED`/`DISABLED` still are) whenever `DhtNode.isDegraded()` — the routing table
  has fewer than `MIN_HEALTHY_NODE_COUNT` (8) known nodes, reusing the exact threshold
  `refreshRoutingTable()` already uses. Confirmed with the user: DEGRADED doesn't count toward
  the Services nav badge's failed-service count (it's running and self-healing, not a
  problem); no startup grace period (a flat live threshold check — a fresh DHT node
  genuinely is sparse until bootstrap/refresh catches up); and `GET /api/dht/status` (the
  header pill) stays unchanged, still just the raw node count — the Services page is the one
  place that interprets it qualitatively. Frontend reuses the existing `'dim'` `StatusTone`
  rather than adding a 4th tone (also confirmed — the style guide deliberately caps at 3), with
  its own explicit "Sparse routing table" text on the row so it doesn't read identically to
  `DISABLED`, the same "don't rely on tone alone" precedent `RUNNING`'s own checkmark already
  set. ([[0059-service-status]]'s own 2026-09-01 addendum)
- **Migrated off `@primeng/themes` onto `@primeuix/themes` entirely (2026-09-03)** — the last
  piece still coming from the deprecated package was the `Aura` preset object itself
  (`definePreset`/`@primeuix/themes` were already in use, see [[0032-style-guide-and-primeng-theme]]'s
  original "the preset" section). Diffed the two packages' shipped `Aura` exports directly
  before switching — structurally identical component-token maps, `@primeuix/themes`'s version
  just also bundles Aura's own base CSS (unused here either way). `package.json`'s
  `@primeng/themes` dependency dropped entirely; `npm install` (to catch the lockfile up) left
  for the user per this project's "builds run manually" convention. ([[0032-style-guide-and-primeng-theme]]'s
  own 2026-09-03 addendum)
- **Fixed: adding a `.torrent` file threw `crypto.randomUUID is not a function` in the Docker
  container specifically, not local dev (2026-09-06)** — `torrent-list.ts`'s `uploadFile()`/
  `submitMagnet()`/`submitMultipleMagnets()` used `crypto.randomUUID()` purely to key a pending-
  upload row locally in the UI (never sent to the backend). `Crypto.randomUUID()` is only
  defined in secure contexts (HTTPS, or `http://localhost`) - a self-hosted deployment reached
  over plain HTTP on a LAN IP/hostname (the ordinary way to reach the Docker container) isn't
  one, so the method is simply undefined there, while `ng serve` on `localhost` during local dev
  gets a browser secure-context exception and never hits it. Replaced with a new
  `generateLocalId()` helper (`shared/local-id.ts`, `Date.now()` + `Math.random()`) at all three
  call sites - no cryptographic randomness was ever needed for a client-local, ephemeral id.
  Prompted a sweep for the same class of bug: **"Copy magnet link" (torrent row context menu and
  the detail drawer) had the identical issue** - `navigator.clipboard` itself (not just
  `randomUUID`) is only defined in a secure context, so `navigator.clipboard.writeText(...)`
  throws synchronously in the Docker deployment, before either `.then()`/error handler ever runs
  - worse than the upload case, since it meant no error toast either, just a silent failure.
  Fixed with a new `copyToClipboard()` helper (`shared/clipboard.ts`) that falls back to the
  legacy `document.execCommand('copy')` (a hidden textarea, select, execCommand) when
  `navigator.clipboard` is unavailable - used at both call sites. A repo-wide grep for every
  other secure-context-gated API (service worker, geolocation, media devices, WebAuthn, share,
  Bluetooth/USB/HID, wake lock, `crypto.subtle`/`getRandomValues`) turned up nothing else.
- **`TRACKER_UNREACHABLE`/`TRACKER_RECOVERED` library events (2026-09-06)** — picked from
  `PROGRESS.md`'s own "Known gaps" list, the tracker-events half of [[0055-library-events]]'s
  originally-deferred pair. A new engine-only `TrackerStatusListener` callback on
  `TrackedTrackerClient` (the one place with a genuine before/after view of a single tracker's
  own status — `MultiTrackerClient` only aggregates), debounced against flapping:
  `TRACKER_UNREACHABLE` fires only after 2 consecutive failed reannounce cycles with no
  intervening success, `TRACKER_RECOVERED` fires on the very next success once unreachability was
  actually reported — asymmetric on purpose, confirmed with the user. Wired only for a torrent's
  own persistent tracker client (`addTorrent()`/`restoreOne()`), deliberately not the throwaway
  tracker client used to probe candidates during magnet metadata resolution.
  ([[0055-library-events]]'s own 2026-09-06 addendum)
- **`MAGNET_RESOLVED` (2026-09-06)** — the other half of that originally-deferred pair, now also
  closed. Resolved as reusing the existing `ADDED` event with a source-driven message (`"Added
  via magnet"`) rather than a new `EventType` - the same mechanism [[0056-watch-folder]] already
  built for `"Added via watch folder"`, just a second source value, avoiding a redundant second
  event per resolved magnet. `TorrentEngine.addFetchedTorrent()` (the single method both the
  tracker-based and DHT-based magnet metadata-fetch paths already funnel through) now passes
  `MAGNET_SOURCE` through the existing mechanism. ([[0055-library-events]]'s own 2026-09-06
  addendum)
- **Watch folder's two deferred items, both closed (2026-09-06)** — a configurable poll interval
  (a new live `watchFolderPollIntervalSeconds` `Settings` field, same "engine-wide scheduled
  task, takes effect on the backend's next restart" shape as `dhtRefreshIntervalSeconds`,
  replacing the previous fixed 30s constant) and `.magnet` file support (a dropped file is just a
  bare magnet URI as its whole text content; `addMagnet()` gained the same `source`-threading
  mechanism `addTorrent()` already had, so a watch-folder-dropped magnet's eventual `ADDED`
  event also reads "Added via watch folder" rather than the generic "Added via magnet"). A
  magnet add is fundamentally asynchronous (a background peer metadata fetch, not a synchronous
  outcome like a `.torrent` file) - "added" for a `.magnet` file means the fetch attempt was
  *accepted*, not that it actually resolved; a real background failure still surfaces as its own
  `MAGNET_ADD_FAILED` event rather than by the file's on-disk location, the same "success at
  request time isn't the same as success" shape already established for the REST magnet-add
  endpoint. ([[0056-watch-folder]]'s own 2026-09-06 addendum)
- **Settings page restyled: vertical section nav + one consistent row shell (2026-09-04/05)** —
  the 7 groups moved from a stacked single page (each its own `<fieldset>`, its own slightly
  different row CSS — cataloged in `SETTINGS_LAYOUT_PATTERNS.md`, 21 rows, 7 different control
  implementations for 4 conceptual types) to a `.blueprint`-framed two-column layout: a vertical
  nav on the left (matching the torrent list's own selected-row treatment), the active group's
  heading/hint/rows on the right, all sharing one global row shell and 4 control shapes (toggle,
  native select, number+unit, number+unit+enable-toggle) instead of per-group CSS. Built from a
  Claude Design handoff (`SETTINGS_PAGE.md` + a `.dc.html` prototype export), with several
  deliberate deviations from it — PrimeIcons kept over the handoff's Lucide icons, restart/live-
  timing caveats kept in row descriptions rather than dropped, `p-toggleswitch` kept (re-themed)
  rather than hand-rolled, and a real unit-label error in the handoff itself caught and fixed
  (Burst allowance mislabeled "KB/s", actually a duration) — all confirmed with the user rather
  than assumed. Rate limiting's `xUnlimited` fields renamed/inverted to `xEnabled` so its
  enable-toggle means the same thing as every other toggle on the page. Registration-mark corner
  decorations, previously exempted app-wide as not worth the maintenance cost
  ([[0032-style-guide-and-primeng-theme]]), turned out to need no PrimeNG-fighting at all once
  actually costed out — added to Settings' own frame specifically, with the wider rollout logged
  to `TODO.md` rather than done everywhere at once. Three real Aura/PrimeNG base-CSS conflicts
  found via live devtools verification, not guessed at — `p-toggleswitch` clipping under flex-
  shrink, `p-inputgroup`'s own `width: 100%`/`flex: 1 1 auto` fighting a fixed-width numeric
  field, and an equal-specificity cascade-order loss on the first fix attempt — all documented
  with the actual computed-box evidence in [[0045-settings-page]]'s own 2026-09-05 addendum.
- **Peer/seed-count investigation and fixes (2026-09-06)** — a real user report ("GrimTorrenter
  shows 1-2 peers for a torrent qBittorrent finds dozens of peers/hundreds of known peers for on
  the same system") root-caused and fixed end to end, verified against the user's own real
  torrent throughout, not just in tests. Three real, distinct root causes, found in sequence as
  each fix exposed the next bottleneck - see `TODO.md`'s own matching Performance entry for the
  full investigation trail (qBittorrent side-by-sides, DEBUG logging added along the way, exact
  before/after numbers):
  1. **DHT was only a last-resort backstop, never a concurrent peer source, for a tracker-bearing
     torrent** - fixed by making `TorrentSession.discoverPeersViaDht()` (new) a routine, periodic,
     independent task for any non-private torrent DHT is eligible for, regardless of tracker
     health, replacing the old backstop-only/trackerless-only special-casing
     ([[0036-dht-backstop-for-tracker-bearing-torrents]]'s own 2026-09-06 revision). Surfaced a
     real prerequisite gap along the way: BEP 27's "private" flag had never been parsed anywhere
     in this codebase - now parsed (`TorrentMetadata.isPrivate()`) and gates both DHT and PEX,
     closing what would otherwise have been a live private-tracker privacy leak once DHT became
     routine.
  2. **`fillConnections()` was a one-shot burst, not a continuously-replenishing pool** - fixed by
     having `attemptConnect()`'s failure path and `PeerListener.onDisconnected()` both trigger a
     fresh refill instead of waiting for the next external tracker/DHT/PEX batch
     ([[0017-torrent-session]]'s own 2026-09-06 revision). This fix shipped with two real,
     sequential production bugs of its own, both found and fixed the same night: an
     `OutOfMemoryError` (an uncontrolled concurrent-attempt cascade - fixed with a `Semaphore`
     bounding total in-flight-or-established connections atomically) and, once that was deployed,
     the same address being attempted a dozen-plus times within milliseconds instead of spreading
     across the known pool (fixed with a second atomically-claimed set, `inFlightAddresses`).
     Both have dedicated regression tests.
  3. **`MultiTrackerClient` implemented strict BEP 12 tier fallback** (stop at the first working
     tracker, never touch the rest) - fixed by announcing to every configured tracker concurrently
     on every call instead, aggregating a deduplicated union of peers and the minimum interval
     among the successes ([[0022-multi-tracker-fallback]]'s own 2026-09-06 revision). A qBittorrent
     screenshot of the same real torrent was the deciding evidence: several different tiers were
     independently "Working" with distinct, non-duplicate seed/peer/leech counts, proving real
     clients don't limit themselves to one tracker's slice of the swarm. Deliberately kept a
     shared single-announce-cycle model rather than per-tracker independent scheduling (the more
     correct, substantially bigger alternative) - confirmed with the user as the right scope for
     one night's work; logged as a follow-up in `TODO.md` if the shared-cycle model's soft
     politeness cost ever turns out to matter.

  **Verified end to end on the user's real system**: the same torrent went from 0-1 connected
  peers and 1 working tracker to 20 connected peers, 9 working trackers, multiple peers actually
  unchoking and sending real data, and genuine sustained throughput (96.0 KB/s at 2% and
  climbing) - deployed for a 24-hour stability test.
- **Authentication for the REST API/UI, built and test-verified (2026-09-07)** -
  picked from `TODO.md`, raised by the user: the REST API is one of this implementation's real
  strengths, but that's moot if it can't be safely exposed beyond localhost/LAN. A single
  shared password (no username - one torrent list per deployment, not one per account),
  persisted in its own `auth.json` (deliberately separate from `settings.json`, which
  `GET /api/settings` echoes back verbatim) and genuinely live-changeable via a new
  `PUT /api/auth/password` while the app keeps running - revised away from an original
  deploy-time-only credential once the user pointed out that can't support changing the
  password live. Bearer-token sessions (`POST /api/auth/login`/`logout`), a
  `AuthenticationFilter` gating every `/api/*` request once a new live `Settings.authEnabled`
  is true (default false; rejected by `SettingsResource` unless a password already exists, so
  it can never end up true with nothing to log in with), and a token passed as a WebSocket
  subprotocol (`Sec-WebSocket-Protocol: bearer, <token>`) rather than a URL query param -
  revised after an automated security review flagged that a reverse-proxy access log would
  otherwise capture the token in plain sight. Session length (`Settings.authTokenTtlDays`,
  sliding, default 30) is itself a live setting too, at the user's request. A global
  failed-login lockout with exponential
  backoff; deliberately no IP-allowlist/LAN-bypass feature (the exact mechanism behind a real
  qBittorrent CVE). Frontend: `AuthService`/`authInterceptor`/`authGuard`, a standalone
  `/login` route rendered outside the app shell entirely, and a new Security settings group.
  Explicitly **not** a substitute for TLS - a bearer token sent over plain HTTP is just as
  interceptable as any other credential; confirmed with the user that GrimTorrenter won't
  terminate TLS itself and operators are expected to front it with a reverse proxy before
  exposing it to the internet. The full `mvn test` reactor (364 tests) passes; getting there
  found and fixed four real bugs (a test StackOverflow from an overzealous find/replace, a
  Quarkus/SmallRye gotcha where a `String`-typed `@ConfigProperty` with an empty-string
  `defaultValue` isn't actually treated as having one - fixed via `Optional<String>` instead -
  a login-lockout policy that penalized a single honest typo, and a stale test assertion missing
  a bearer token). One real bug also came from an automated security review, not a test run: the
  WebSocket token originally rode as a `?token=` URL query param, which a fronting reverse proxy
  (recommended above, for TLS) would commonly capture in its own access log - moved to the
  `Sec-WebSocket-Protocol` header instead. A real UX issue surfaced by the user manually
  exercising the Settings page, also fixed same-day: the "Require a password" toggle was
  originally disabled until a password existed, sitting *above* the password field - a
  confusing, easy-to-miss precondition. Reordered (password field first) and, at the user's own
  suggestion, changed from a disabled control to a validity-based one - the toggle is always
  clickable, but toggling it on with no password set marks it invalid, disabling the page's own
  Save button (the same mechanism every other group's validation already uses) with a specific
  visible reason shown right at the toggle, rather than a disabled control or a generic
  post-Save error. ([[0061-authentication]])

**Not yet built** (the rest of Phase 3):

- Per-torrent rate limit overrides and multiple/day-of-week-specific schedule rules — the
  remaining natural additions to the rate-limiting settings group
  ([[0045-settings-page]], [[0046-rate-limit-schedule]]). **Deliberately pushed to the back of
  the backlog** (2026-08-25 user decision) — both have a plausible but marginal real-world
  case (per-torrent overrides is at least precedented in real clients, but largely substitutable
  by pause/resume; multi-rule scheduling is a narrow edge case the existing single daily window
  already mostly covers), and lower priority than seeding limits (now built), which reflected a
  much more common real-world need.
- The "multi-torrent global bandwidth budget" item from [[0009-phased-scope]]'s original list
  is now considered **retired as its own item** — it predates [[0042-rate-limiting]], which
  already delivered exactly that (one global cap shared across every torrent's combined
  traffic). If something more specific was meant by it (e.g. fair per-torrent allocation when
  the global cap is saturated), that's really the per-torrent-overrides item above, not a
  separate one.

### Engine stability/scale

A resource-usage audit (prompted by wanting the engine solid and stable under many
simultaneous torrents, since it's built to eventually stand as its own product) found the
concurrency model itself sound — virtual-thread-per-connection matches
[[0007-concurrency-model]], per-torrent connection caps and socket timeouts already exist,
cleanup on error is solid — but no bound at all on total open file descriptors: every
torrent's files were opened once and held open for its whole lifetime, even while paused.

- **Fixed**: a shared, bounded, LRU `FileHandlePool` — every read/write now borrows a
  channel from an engine-wide cache (configurable size, default 256) instead of holding one
  open forever. Bounds total fd usage regardless of torrent count or paused/running state,
  and structurally can't reintroduce [[0030-pause-resume-storage-lifecycle]]'s old
  ClosedChannelException bug, since every access is now a transparent reopen-on-demand
  rather than a one-way close ([[0047-bounded-file-handle-pool]]).
- **Fixed**: piece verification (a full-piece read plus a SHA-1 hash, both on restart
  re-verify and on normal completion) now goes through a shared, engine-wide `Semaphore` —
  bounds how many pieces can be mid-verification at once regardless of how many torrents are
  restoring or completing pieces simultaneously, instead of every restoring torrent's own
  unthrottled virtual thread piling on all at once. Defaults to the available processor
  count (configurable) since hashing is CPU-bound and parallelizing past that buys nothing
  but more buffers in memory ([[0048-piece-verification-throttling]]).
- **Fixed**: `ManyTorrentsRestoreLoadTest` restores 40 real torrents concurrently against a
  deliberately undersized shared pool (5 file slots) and verification limiter (4 permits),
  proving both hold their bounds and every torrent still verifies correctly under real,
  adversarial concurrent load — not just the individual scenarios each mechanism's own unit
  tests construct ([[0049-many-torrents-load-test]]).
- **Fixed**: `PieceManager`'s bookkeeping methods now use a `ReentrantLock` instead of
  `synchronized` - not a fix for a live bug (the audit found none: nothing blocking ever ran
  while the monitor was held), but it closed the doc/reality gap against
  [[0007-concurrency-model]]'s "avoid `synchronized` in the hot path" guidance outright,
  rather than leaving an explained exception to it. Reentrancy (`selectNextPiece()` calling
  back into `stateOf()`) is preserved - `ReentrantLock` supports it the same way
  `synchronized` did ([[0050-piece-manager-reentrant-lock]]).
- All four findings from the original stability/scale audit - unbounded file descriptors,
  unbounded verification bursts, no load test to prove either, and this `synchronized` usage
  - are now fully addressed. Remaining engine-level work is [[0009-phased-scope]]'s ordinary
  Phase 3 backlog below, not a stability gap.
- **Stability promoted to a standing consideration for every future decision**, not just a
  one-time audit - every new/revised `design_docs/` entry should now say something about
  resource/failure behavior, even briefly ([[0051-stability-as-a-standing-consideration]]).
  Also recorded in `CLAUDE.md`. See `STABILITY.md` for the full narrative this grew out of.
- **Fixed a real flake in `ManyTorrentsRestoreLoadTest`** ([[0049-many-torrents-load-test]]),
  found while working on seeding limits and confirmed to fail consistently right after
  `mvn clean`: its 40 `restoreAsync()` calls ran sequentially on the main thread, and with only
  4 tiny pieces per torrent, verification could finish and release its semaphore permit before
  the next torrent's call even started - so peak observed concurrency never rose above 1, not
  because nothing was bounding it, but because nothing had asked for more than one permit at a
  time. Fixed by launching all 40 `restoreAsync()` calls from their own threads behind a shared
  start gate, so they genuinely race for the shared pool/semaphore at once
  ([[0054-seeding-limits]]).
- **Fixed a second, different flake in the same test's `PeakTrackingSemaphore`** (2026-08-30,
  found as a spurious failure during unrelated magnet-fetch work) - its peak-tracking
  increment lived inside an `AtomicInteger.updateAndGet()` lambda, which can be re-invoked
  under real CAS contention; a side-effecting increment there could fire more than once per
  actual `acquire()`, inflating the observed peak above what the real `Semaphore` ever
  permitted. The bound itself was never actually violated - only miscounted. Fixed by
  incrementing once outside the lambda ([[0049-many-torrents-load-test]]'s own addendum).

## Known gaps / TODO

- **Upload/magnet-add latency has no deeper fix, only better feedback.**
  `TorrentSession.start()`'s initial tracker announce is still fully
  synchronous within the add request; 0029's optimistic "Processing" row
  covers the *feedback* gap, not the underlying latency. Revisit if it
  proves to matter in practice — would mean loosening `start()`'s
  synchronous contract, a bigger change than it looks given how much
  else assumes it.
- **A per-row pending action (Pause/Resume/Remove) clears its spinner on
  response, but the row's displayed state still only catches up on the
  next ~2s WebSocket snapshot** — a brief window where the row looks
  normal again but hasn't visually caught up yet. Flagged by the user as
  worth revisiting with an optimistic local update if it feels
  unresponsive in practice; see [[0033-per-entry-action-feedback]]'s
  Future work section.

## Suggested next steps, in rough priority order

Phase 2 is fully complete; Phase 3 has Peer Exchange, rate limiting (with a daily off-hours
schedule and a burst allowance), a real settings page, and MSE done — every item from the
original Phase 3 list is now built; the engine stability/scale audit is fully closed out;
seeding limits, library events, the watch folder, service status, the row-selected highlight,
magnet-add reliability/feedback, periodic DHT re-query for trackerless torrents, DHT
routing-table health, DHT routing-table persistence across restarts, the DHT healthy-vs-sparse
`DEGRADED` service state, the `@primeng/themes` → `@primeuix/themes` migration, the
peer/seed-count investigation (DHT as a concurrent source, BEP 27 private-torrent gating,
continuous connection refill, concurrent multi-tracker announce - all picked from `TODO.md`),
and REST/WebSocket authentication (picked from `TODO.md`, built and test-verified) are
done:

1. The remaining `TODO.md` items: a notification service (still fully unscoped), running a
   user-configured script automatically on torrent completion, LSD (BEP 14, minor), a
   per-tracker seeders/leechers/peers UI (summary + detail view, from a qBittorrent
   comparison), per-tracker independent announce scheduling (deferred as the bigger
   alternative to the shared-cycle tracker-concurrency fix above), and retrying a failed
   peer address after a cooldown (deferred as the simpler option when the connection-refill
   fix landed).
2. The pending-action-vs-2s-snapshot-lag gap noted above, if it proves to
   matter in practice.
3. The rate-limiting settings group's remaining natural additions (per-torrent overrides,
   multi-rule schedule) — pushed to the back of the backlog (2026-08-25), marginal real-world
   value relative to the items above.
4. Multi-select on the torrent list (checkboxes/shift-click for bulk Pause/Resume/Remove) —
   noted in `TODO.md`, 2026-09-03, unscoped.
