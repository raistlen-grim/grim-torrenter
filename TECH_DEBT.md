# Tech debt

Post-release work collected during the pre-release code reviews (`code_review/`). These are
not release blockers: the fix-now items live in each review file. Each entry says where it
came from so the context can be found again.

## Engine (from code_review/01-engine.md)

### Structure
- **Split `TorrentEngine` (2.7k lines).** Candidate extractions:
  - `TorrentConfigStore`: all per-torrent marker I/O.
  - `WatchFolderScanner`.
  - `MagnetResolver`: pending registry, fetch loop, `raceOneRound`.
  - `DhtPersistence`: node id and known contacts.
  - `ServiceHealth`: service statuses and proxy status.
  The engine itself would keep the session registry and lifecycle.
- **Split `TorrentSession` (2.7k lines).** Candidate extractions:
  - Lifecycle/state machine: start/stop/fail/recheck/retry.
  - `Announcer`: tracker, DHT backstop, start retry.
  - `ConnectionPool`: fill/attempt/adopt/accept, slots, known and failed addresses.
  - `RequestScheduler`: pipeline, InFlightBlocks, endgame.
  - Choking.
  - PEX.
  - Stats/snapshot views.
- **Replace telescoping overloads with parameter objects.** Applies to `TorrentEngine` (13
  constructors), `TorrentSession.create`/`restoreAsync` (13 each, up to 23 params),
  `Settings` (19 constructors) and `PeerConnection.connect` (5). Suggested shapes:
  - `EngineConfig`, a record with a builder.
  - `SessionDependencies`: engine-wide shared collaborators.
  - `SessionState`: per-torrent persisted state (overrides, priorities, lifetime stats,
    addedAt).
  Tests move to builders. Mechanical but touches every engine test.
- **`Settings` layering.** Move the app-only fields (theme, auth, event-log retention) out of
  the engine record. Replace nullable `Boolean`/`Integer` "absent in old JSON" components with
  defaults applied at deserialisation in the app.
- **Unify per-torrent persistence.** Use one tolerant, atomically written file per torrent
  (or a shared `KeyValueFile` helper) instead of ~10 bespoke markers with inconsistent
  throw/log behaviour. Share one `AtomicFiles.write(path, content)` helper across the five
  current copies.
- **Unify HTTP fetching.** HttpTrackerClient (two paths), Blocklist (two paths) and MiniHttp
  each implement their own size and deadline limits. One `HttpFetcher` that handles both
  proxied and direct requests, with a mandatory size cap.
- **Generic Kademlia iterative lookup** shared by `NodeLookup` and `PeerLookup`.
- **Shared `Hashing.sha1()`** instead of the five copies.
- **Typed `InFlightBlocks`** (`PeerConnection` or generic) instead of `Object` plus casts.
- **Test hooks.** Many package-private "for tests" methods and setters
  (`checkSeedingLimits`, `scanWatchFolder`, `setStartRetryInitialMillisForTesting`, …).
  Injecting a `Clock` and a scheduler would let tests drive time without widening visibility.

### Comments and docs
- **Comment hygiene pass.** Strip change history ("an earlier draft…", "fixed 2026-09-15",
  "confirmed with the user", arity counts, chained addenda references) out of Javadoc so it
  describes current behaviour; the history stays in `design_docs/`. Likely shrinks the two
  big classes by 30–40%.

### Protocol completeness
- **IPv6 everywhere.** Tracker `peers6`, PEX `added6`/`dropped6`, DHT (BEP 32), and the
  blocklist, which is currently IPv4-only and lets every IPv6 peer through.
- **Non-compact HTTP tracker responses** (dictionary-model `peers`): currently rejected.
- **Rarest-first piece selection.** Sequential selection hurts swarm health and is slow to
  scan (O(pieces) per request). Add rarest-first with sequential as an option; maintain
  per-piece availability counts from Have/Bitfield.
- **µTP:** handle out-of-order packets (they are dropped now, which costs a retransmit each)
  and add selective ACK (BEP 29 extension).
- **Choking:** real tit-for-tat or rate-based unchoke instead of rotation (design_docs/0025
  chose simplicity deliberately; revisit once there's real-world data).
- **Tracker tiers:** BEP 12 tier order isn't honoured (every tracker is announced to at
  once). Fine by design today, but worth noting.
- **`selectTrackerTiers`:** a torrent whose trackers are all unsupported (e.g. `wss://`)
  should fall back to DHT instead of being rejected.

### Performance
- Running counters for `bytesDownloaded`, `wantedBytes` and `wantedBytesCompleted` instead of
  O(pieces) scans on every call.
- `TorrentSession.files()`: walk pieces once and attribute each to its files, instead of
  O(files × pieces).
- `peers()` snapshot: O(peers × pieces) per call. Cache availability per connection, updated
  on Have/Bitfield.
- `InfoHash` stores hex and re-parses it on every `bytes()` call. Store the bytes and render
  hex lazily.
- `FileHandlePool.acquire` opens files while holding the pool-wide lock.

### Stability / resource bounds (beyond the review's fix-now items)
- `knownAddresses` is capped at 3000 per torrent (design_docs/0092), but eviction only drops
  addresses that have already failed. A smarter policy (age-based, or ranked by source) would
  stop a flood of never-tried junk from crowding out good tracker peers until those are tried.
- Per-peer PEX rate limiting, kept in reserve by design_docs/0092.
- µTP `RECEIVE_BUFFER_LIMIT_BYTES` (256 KiB) and the inbound handshake caps (128) are fixed
  constants; revisit them with real-world numbers.
- Per-torrent `maxConnections` override and engine-wide intervals (DHT refresh, LSD, watch
  folder) are read at construction, so changing them needs a restart or re-add. Make them
  live, or say "restart required" in the UI.
- Per-torrent bandwidth overrides *replace* the global limiter rather than nesting under it,
  so an override above the global cap bypasses it. Confirm that's intended (design_docs/0072)
  or chain both limiters.
- A failed `addTorrent` leaves behind its claimed placeholder file or directory and its
  download-path marker. Add cleanup on the failure path.

## App (from code_review/02-app.md)

### Structure
- **One `@ConfigMapping`** for `grimtorrenter.*` deploy-time properties, instead of
  `@ConfigProperty` with repeated default strings in six classes.
- **`WebSocketBroadcaster` bean** in place of `TorrentWebSocket`'s static `CONNECTIONS` set
  and static `broadcast()`.
- **`JsonFileStore<T>`**: shared read-or-seed / atomic write-then-swap code for
  `JsonSettingsStore` and `JsonAuthStore`.
- **Split `SessionTokenStore`** into a token store and a login throttle.
- **Collapse the four identical exception mappers** into one, alongside B6's unified
  `{"error": ...}` body for every 4xx.
- **Decide the DTO policy explicitly.** Either always wrap engine types in app-side views, or
  document which engine records are public API. Today it's case by case
  (design_docs/0045), so the REST contract has no single place to read.
- **Error typing in the engine:** separate "bad input" from "server failed"
  (`TorrentEngineException` is used for both), so the app can map 400 vs 500 without
  string-matching.

### Features and hardening (post-release)
- `GET /api/events` returns the whole retention window every time. Add `limit`/`before`
  pagination before an event-heavy install makes the Events page slow.
- Optional `grimtorrenter.allowed-hosts` (Host-header allowlist) for DNS-rebinding defence,
  if A1's Origin check isn't considered enough.
- Per-IP login throttling, deferred in design_docs/0061 because of the trusted-proxy-header
  question; revisit if the app is commonly exposed behind a reverse proxy.
- Comment hygiene pass for the app module (same as the engine item).
