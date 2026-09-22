# 0072 — Per-torrent bandwidth and connection limits

**Status:** Accepted

## Decision

Picked from `TODO.md`'s backlog: "Per-torrent bandwidth/connection limits - a control, not a
metric (only global + scheduled limits exist today, [[0042-rate-limiting]]/
[[0046-rate-limit-schedule]])... deserves its own decision if picked up, not a metrics-bundle
add-on." One combined feature covering both upload/download bandwidth and connection-count
limits per torrent - a single `TorrentLimitOverride` record, one marker file, one dialog -
mirroring [[0054-seeding-limits]]'s own `SeedingLimitOverride` shape exactly, including its
negative/0/positive sentinel convention (`< 0` inherit, `0` explicit unlimited/no cap, `> 0`
custom). Confirmed with the user as one feature rather than two, given how directly the
connections side's "an enabled toggle that means unlimited when off" ask maps onto the exact
3-state `LimitMode` control that dialog already established.

### Bandwidth: replaces the global cap, not layered under it

[[0042-rate-limiting]] scoped rate limiting as strictly global - "per-torrent overrides would be
a natural later addition ... not something this needed to include." This is that addition, with
a deliberate deviation from 0042's "one global cap over combined traffic" framing, confirmed
explicitly with the user: an overridden torrent's traffic draws from a dedicated, per-session
token bucket entirely separate from the engine-wide `RateLimiters` - it no longer counts against,
or is bounded by, the global cap at all while overridden. **Tradeoff, stated plainly rather than
buried**: an overridden torrent can push the engine's total bandwidth usage above the configured
global cap. The alternative (layering a per-torrent cap under the still-shared global bucket) was
rejected because it doesn't let a user actually guarantee a torrent X KiB/s regardless of what
else is running - the more common real want behind a per-torrent override.

`RateLimiters` (previously a bare `record RateLimiters(RateLimiter upload, RateLimiter
download)`) is now a small class backed by a `Supplier<RateLimiter>` per direction instead of a
fixed field - `PeerConnection` only ever calls `rateLimiters.upload().acquire(...)`/
`.download().acquire(...)` (`PeerConnection.java:369,508`), so this needed zero changes at any
existing call site. `forTorrent()`'s `upload()`/`download()` decide, live on every call: return
the literal shared global `RateLimiter` instance when the override is currently inherit (so
every inheriting torrent still shares one bucket, unchanged from today), or a dedicated
per-torrent `RateLimiter` otherwise. Explicit `0` (no limit for this torrent) needs no
special-casing - `RateLimiter.acquire()` already treats a limit `<= 0` as instant-return.

`RateLimiter` itself generalized from `(SettingsStore, ToLongFunction<Settings>)` to
`(LongSupplier limitBytesPerSecond, LongSupplier burstSecondsSupplier)`, with the original
constructor kept as a delegating convenience overload - needed so a dedicated per-torrent
`RateLimiter` can read its limit from a live `TorrentLimitOverride` supplier instead of a
`Settings` snapshot, without inventing a second, parallel `RateLimiter`-like class.

### Connections: resolved once, restart-or-re-add-only - a real deviation from bandwidth's live behavior

Unlike `RateLimiter` (re-reads its limit every `acquire()`), a `Semaphore`'s total permits are
fixed at construction - `TorrentSession.connectionSlots` has no cheap live-resize primitive.
Rather than build one (a real semaphore-replacement effort, out of proportion to this feature),
both the new global default (`Settings.maxConnectionsPerTorrent`) and any per-torrent override
are resolved once, at `TorrentSession` construction/restore time only - explicitly analogous to
this codebase's existing restart-required precedent for structural settings (`dhtEnabled`/
`acceptIncomingConnections`), not the fully-live precedent rate limits get in this very feature.
Confirmed with the user as the right scope, given the structural constraint.

**More precise than "restart required":** `pauseTorrent()`/`resumeTorrent()` reuse the same
`TorrentSession` object (`session.stop()`/`start()`, not recreation) - a connections change takes
effect only the next time this torrent is genuinely reconstructed: a full app restart, or a
remove-and-re-add. Pause+resume alone does not pick it up. Both the Settings-page row and the
per-torrent dialog's Max connections row say so explicitly, distinct from the two bandwidth rows
in the same dialog, which are fully live.

`MAX_CONNECTIONS` (a `static final int` constant) became `DEFAULT_MAX_CONNECTIONS` (the fallback
every lower-arity `create()`/`restoreAsync()` overload still defaults to) plus a genuine instance
field, `maxConnections`, sized once per session and used both by `connectionSlots`'s `Semaphore`
and `fillConnections()`'s scan-limit bound. Explicit `0` for the per-torrent override means
unlimited, resolved to `Integer.MAX_VALUE` (`TorrentLimits.effectiveMaxConnections()`) - the same
"effectively unbounded" convention `TorrentSession.create()`'s own lower-arity overload already
uses for `pieceVerificationLimiter`.

### Threading it through without touching existing tests

Same established pattern as every prior `TorrentSession`/`TorrentEngine` addition (`enableDht`,
`seedingLimitOverride`, `lsdActive`): every existing `create()`/`restoreAsync()` signature is
kept exactly as-is, delegating to a new sibling overload with the two new params defaulted
(`TorrentLimitOverride.INHERIT`, `DEFAULT_MAX_CONNECTIONS`) - zero existing call sites in
`TorrentSessionTest`/`PeerConnectionTest`/`ManyTorrentsRestoreLoadTest` needed to change,
including `neverDuplicatesOrExceedsMaxConnectionsEvenUnderABurstOfFailures`, which uses the
lowest-arity `create()` and still gets the same default-30 behavior it always has.

`RateLimiters.forTorrent()` needs a live-readable override at construction time, before the
`TorrentSession` it belongs to exists yet - resolved with an `AtomicReference<TorrentSession>` at
the two `TorrentEngine` call sites (`addTorrent()`'s reused-directory branch, `restoreOne()`)
rather than any restructuring inside `TorrentSession` itself:

```java
AtomicReference<TorrentSession> sessionRef = new AtomicReference<>();
RateLimiters perTorrentRateLimiters =
    RateLimiters.forTorrent(rateLimiters, settingsStore, () -> sessionRef.get().torrentLimits());
TorrentSession created = TorrentSession.create(/* ..., */ perTorrentRateLimiters, /* ... */,
        torrentLimitOverride, effectiveMaxConnections);
sessionRef.set(created);
```

Safe with no real race: no peer connection, and therefore no `acquire()` call, can happen before
`create()`/`restoreAsync()` returns and `sessionRef.set(created)` runs.

### Per-torrent override persistence

New marker, `.grimtorrenter-torrent-limit-override`, stored via the existing
`configTorrentDirectory(infoHash)` helper ([[0065-config-side-per-torrent-storage]]'s config-side
location), same plain `key=value` shape, same forward-compatible unknown-line handling, and same
never-deleted-by-keep-files-removal treatment as `SeedingLimitOverride`'s own marker - a
torrent-config-scoped preference like this stays with the record, same as the seeding-limit
override and the resolved download path already do.

### REST and frontend

`GET`/`PUT /api/torrents/{infoHash}/limits`, exact mirror of the seeding-limits pair (no DTO
wrapper - same [[0045-settings-page]] reasoning already applied to `Settings`/
`SeedingLimitOverride`). A new "Torrent limits…" dialog (`torrent-limits-dialog`), reusing
`seeding-limits-dialog`'s `LimitMode`/`modeFor`/`sentinelFor`/`modeOptions`/`syncValueDisabled`
helpers - extracted to a shared `limit-mode.ts` rather than duplicated a second time - three rows
(Upload limit, Download limit, Max connections) instead of two, otherwise the identical
`p-select` + `p-inputnumber` shape, `[appendTo]="'body'"` on every `p-select`
([[0054-seeding-limits]]'s own documented PrimeNG-in-dialog gotcha). A new Network-group Settings
row for `maxConnectionsPerTorrent`, its own description stating the precise
restart-or-re-add-only scope rather than a bare "restart required."

## Testing

- `TorrentLimitsTest` (new) - `effectiveMaxConnections()`'s three sentinel branches.
- `RateLimiterTest` - the generalized `(LongSupplier, LongSupplier)` constructor behaves
  identically to the existing `(SettingsStore, ToLongFunction<Settings>)` overload for every
  existing case.
- `RateLimiters`-focused coverage - `forTorrent()`'s inherit case returns the literal shared
  global `RateLimiter` instance (not just an equal-valued one); overridden case gets a dedicated
  bucket; a live change between inherit/custom/unlimited takes effect on the very next
  `acquire()`.
- `TorrentEngineTest` - the new marker round-trips through `addTorrent()`'s reused-path branch
  and `restoreOne()`; `setTorrentLimits()` changes live bandwidth behavior immediately; a
  connections override has no effect on an already-running session after
  `pauseTorrent()`/`resumeTorrent()`, but does after a remove-and-re-add.
- `SettingsResourceTest`/`TorrentResourceTest` - the new `Settings` field and the new REST pair
  round-trip.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **The bandwidth tradeoff above** (total engine bandwidth can exceed the global cap once any
  torrent is overridden) is the one genuine new resource-behavior consideration this introduces -
  stated here, not just in the Decision section above.
- **No unbounded growth**: `forTorrent()` allocates two extra small `RateLimiter` objects per
  session unconditionally (no opt-in flag, matching `RateLimiters`' own existing precedent) -
  negligible, no threads, no growth that scales with anything but torrent count, which is already
  bounded by ordinary engine limits.
- **No hostile-peer angle**: both the override and the global default are driven entirely by
  local user input (the dialog, the Settings page) and this engine's own prior records - no
  peer/tracker input reaches this path.
- **Concurrency**: `torrentLimitOverride` is `volatile`, same pattern as `seedingLimitOverride` -
  a single reference swap, no partial-write visibility issue. `connectionSlots`'s `Semaphore` is
  unaffected in kind - still sized once, just from a variable rather than a constant.
- **Failure mode when misconfigured**: none new - every sentinel value (including `0`/negative)
  resolves to a well-defined, already-bounded behavior (unlimited via `RateLimiter`'s existing
  `<= 0` handling, or `Integer.MAX_VALUE` connections, the same convention already used elsewhere
  in this codebase for "unbounded").

## Alternatives considered

- **Layering a per-torrent bandwidth cap under the still-shared global bucket** - rejected; see
  the Decision section's bandwidth note above.
- **A live-resizable connection `Semaphore`** - rejected as disproportionate effort for this
  feature; restart-or-re-add-only accepted instead, with the precise
  pause/resume-doesn't-count caveat surfaced in both the Settings page and the dialog rather than
  glossed over as a generic "restart required."
- **An "unlimited" per-torrent connections state bounded by a new hardcoded safety ceiling** -
  considered, rejected in favor of reusing the existing `Integer.MAX_VALUE`-means-unbounded
  convention already established for `pieceVerificationLimiter` - no new constant needed, and
  consistent with how this codebase already expresses "no cap" elsewhere.

**Addendum (2026-09-19): dialog placement fix.** "Torrent limits..." shared the seeding-limits
dialog's bug of being mounted in a cell that is hidden while the details panel is docked, so it did
nothing in that state. Moved with the other row dialogs; see [[0054-seeding-limits]]'s addendum.
