# GrimTorrenter — Progress

Snapshot of what's built and what's left. Rationale for any decision
mentioned here lives in `design_docs/` (one file per decision, linked
below) — this file is a status/TODO list, not a source of truth for *why*.

## Current state

**Phase 1 (MVP) and Phase 2 (usable day to day) are functionally
complete**, per the phased scope in [[0009-phased-scope]]:

- Full engine: bencode, metainfo parsing, HTTP + UDP tracker announce
  with multi-tracker/tier fallback, peer wire protocol, piece/block
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
  lookup ([[0028-magnet-links-and-dht]]). Wired into `TorrentSession` for
  trackerless magnets — peer discovery on `start()` *and* a periodic
  re-query while running (live-tunable interval, default 300s), mirroring
  **the same backstop mechanism built for regular (tracker-bearing)
  torrents whose trackers are all currently unreachable**
  ([[0036-dht-backstop-for-tracker-bearing-torrents]], including its own
  2026-08-30 addendum for the trackerless periodic re-query). A `GET
  /api/dht/status` endpoint exposes node count.
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
- The detail header now shows when a tracker-bearing torrent is actively
  leaning on the DHT backstop (distinct from the trackerless-only `usesDht`
  tag) — the UI-visibility question left open in
  [[0036-dht-backstop-for-tracker-bearing-torrents]] ([[0039-dht-backstop-visibility]]).
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
magnet-add reliability/feedback, periodic DHT re-query for trackerless torrents, and DHT
routing-table health (all picked from `TODO.md`) are done:

1. DHT routing-table persistence across restarts (`TODO.md`) — the periodic-refresh fix above
   closes "stays sparse while running," this would additionally close "starts cold every
   restart." Deliberately deferred, real scope of its own (storage format, staleness policy).
2. The DHT service-status "healthy vs. sparse routing table" distinction noted above — the
   routing-table-health fix means a low count is no longer expected to persist indefinitely,
   but the status endpoint still can't distinguish "still filling in" from "genuinely stuck."
3. The remaining `TODO.md` items: a notification service (still fully unscoped), running a
   user-configured script automatically on torrent completion, LSD (BEP 14, minor), and the
   `@primeng/themes` migration.
4. The pending-action-vs-2s-snapshot-lag gap noted above, if it proves to
   matter in practice.
5. Library events' two deferred event types ([[0055-library-events]]'s own "Deferred from this
   pass" section) — tracker unreachable/recovered, and a distinctly-labeled magnet-resolved —
   if they prove to matter in practice.
6. The watch folder's two deferred items ([[0056-watch-folder]]'s own "Alternatives considered"
   section) — magnet-link files and a configurable poll interval — if either proves to matter.
7. The rate-limiting settings group's remaining natural additions (per-torrent overrides,
   multi-rule schedule) — pushed to the back of the backlog (2026-08-25), marginal real-world
   value relative to the items above.
