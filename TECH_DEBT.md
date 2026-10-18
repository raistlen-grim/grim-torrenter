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
  instead of ~10 markers. *Partly done in engine C (design_docs/0099):* `AtomicFiles` replaces
  the five copies, and `KeyValueFile` plus `readMarker`/`writeMarker` give the markers one
  format and one tolerance. The single file per torrent (a layout change, with a migration) is
  still open.
- **Unify HTTP fetching.** HttpTrackerClient (two paths), Blocklist (two paths) and MiniHttp
  each implement their own size and deadline limits. One `HttpFetcher` that handles both
  proxied and direct requests, with a mandatory size cap.
- ~~**Generic Kademlia iterative lookup** shared by `NodeLookup` and `PeerLookup`.~~ Done in
  engine C (`IterativeLookup`, design_docs/0099).
- ~~**Shared `Hashing.sha1()`** instead of the five copies.~~ Done in engine C.
- ~~**Typed `InFlightBlocks`** (`PeerConnection` or generic) instead of `Object` plus casts.~~
  Done in engine C (`InFlightBlocks<O>`).
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
- ~~**`selectTrackerTiers`:** a torrent whose trackers are all unsupported (e.g. `wss://`)
  should fall back to DHT instead of being rejected.~~ Done in engine C (design_docs/0099);
  a private torrent is still rejected, since it can't use DHT.

### Performance
- ~~Running counters for `bytesDownloaded`, `wantedBytes` and `wantedBytesCompleted` instead of
  O(pieces) scans on every call.~~ Done in engine C (design_docs/0099).
- ~~`TorrentSession.files()`: walk pieces once and attribute each to its files, instead of
  O(files × pieces).~~ Done in engine C.
- `peers()` snapshot: now BitSet arithmetic with one lock per call (engine C), but still
  O(peers × pieces / 64). Per-connection availability counts updated on Have/Bitfield would
  make it O(peers); worth it with rarest-first, which needs the same counts.
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
- ~~**One `@ConfigMapping`**~~ Done for what was actually duplicated: the three directory
  properties live in `AppDirectories` (design_docs/0100). The other `grimtorrenter.*`
  properties are each read in one place.
- ~~**`WebSocketBroadcaster` bean**~~ Done (design_docs/0100).
- ~~**`JsonFileStore<T>`**~~ Done as `AtomicJsonFile.readOrSeed()` plus the atomic `write()`
  (design_docs/0100).
- ~~**Split `SessionTokenStore`** into a token store and a login throttle.~~ Done in app B2
  (`LoginThrottle`, design_docs/0094).
- ~~**Collapse the four identical exception mappers**~~ Done: `EngineExceptionMappers`
  (design_docs/0100).
- ~~**Decide the DTO policy explicitly.**~~ Decided in design_docs/0100 section 6: the engine
  records on the wire are listed there as public API, and new endpoints default to a view.
- ~~**Error typing in the engine**~~ Done in app B5 (`TorrentEngineIoException`,
  design_docs/0094).

### Features and hardening (post-release)
- `GET /api/events` returns the whole retention window every time. Add `limit`/`before`
  pagination before an event-heavy install makes the Events page slow.
- Optional `grimtorrenter.allowed-hosts` (Host-header allowlist) for DNS-rebinding defence,
  if A1's Origin check isn't considered enough.
- Per-IP login throttling, deferred in design_docs/0061 because of the trusted-proxy-header
  question; revisit if the app is commonly exposed behind a reverse proxy.
- Comment hygiene pass for the app module (same as the engine item).

## Frontend (from code_review/03-frontend.md)

### Thin frontend (design_docs/0071)
- **Transfer rates are computed in the browser** (`TorrentEventsService` + `RateTracker`, from
  successive snapshots). Every other client would have to rebuild that, and the numbers depend
  on when snapshots happen to arrive. Expose `downloadRateBytesPerSec`/`uploadRateBytesPerSec`
  (and ETA) on `TorrentView` from the engine's own counters; the client keeps only the
  sparkline history.
- **Copy magnet link** is assembled client-side (now in one place, `magnetLinkFor()`,
  design_docs/0101). A `magnetUri` field on `TorrentView` would be one line server-side.

### Structure
- **Split `torrent-list.ts`**: mostly done. The magnet parsing moved out in design_docs/0096,
  and the add control's logic is `AddTorrentController` (design_docs/0101), leaving ~740 lines.
  What remains:
  - Moving the add control's markup into a component of its own. It needs browser
    verification, because its pieces sit in three places in the list's layout.
  - The keyboard shortcuts. They read most of the list's state, so a directive was judged not
    worth it (0101).
- **Component tests.** The pure logic has unit tests (design_docs/0096); no component has a
  TestBed test yet. Start with `TorrentList`'s selection and keyboard handling, and
  `TorrentEventsService`'s message handling and reconnect rules.
- ~~**One torrent action menu** shared by `TorrentRow` and `TorrentDetail`~~ Done:
  `TorrentCommands` (design_docs/0101).
- **Generated API types.** `models/*.ts` are hand-written copies of the backend DTOs, with no
  check that they match. An OpenAPI document from Quarkus (`quarkus-smallrye-openapi`) plus a
  generator, or at least a contract test, would catch drift.
- **Commit the style guide.** Comments no longer cite its missing files by name; they point at
  design_docs/0032 and 0045 (design_docs/0101). Committing the guide itself is still open.

### Features and hardening (post-release)
- **Events page renders the whole retention window** in one list. Pair with the app's events
  pagination item: load a page, fetch more on scroll.
- **Settings are saved as one whole object, last writer wins.** Two tabs, or the UI and a
  script, can silently undo each other's changes. An `ETag`/`If-Match` on `PUT /api/settings`
  would turn that into a 409.
- **Snapshots are full lists every 2 s.** Fine at hundreds of torrents. At thousands, send only
  the rows that changed.
- **Accessibility isn't checked automatically**, though `frontend/.claude/CLAUDE.md` requires
  AXE and WCAG AA. Add an axe check to the component tests, or a Lighthouse pass to the
  regression checklist.
- ~~**Validate WebSocket messages**~~ Done: `parseEventMessage()` (design_docs/0101).
- Comment hygiene pass for the frontend (same as the engine and app items).

