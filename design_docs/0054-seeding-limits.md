# 0054 — Seeding limits

**Status:** Accepted

## Decision

Picked from `TODO.md` (2026-08-25 user decision) over the two remaining rate-limiting backlog
items (per-torrent rate overrides, multi-rule schedule), judged a more common real-world need.
Stops a torrent from seeding once it crosses a **ratio** and/or **time** limit - a global
default, **with a per-torrent override** (an explicit user requirement - unlike
[[0042-rate-limiting]]'s global-only scope, this feature needed per-torrent overrides from the
start). Ratio (upload/download), not percentage - percentage implies a 100% ceiling that
doesn't exist for a value that routinely exceeds 1.0 in normal seeding use, and ratio is the
term every real client and private tracker already uses. Both ratio and time limits are
checked, whichever is reached first.

There was no per-torrent override mechanism anywhere in this codebase before this, and no
existing seed-ratio/seed-time concept at all - a genuinely new mechanism, not an extension of
something existing.

### Neither ratio nor seed time survives a process restart

`TorrentSession.accumulatedUploaded`/`accumulatedReceived` (the byte counters ratio is computed
from) are already plain in-memory fields that reset to 0 on every restart - no persisted
byte-count marker exists today. Making seed *time* persist across restarts while ratio doesn't
would be an inconsistent, half-fixed version of a limitation this feature isn't the place to
solve. Both are scoped as "since this torrent was last created/restored in the current
process," matching the byte counters they're built on - a deliberate, documented limitation,
not a silent one.

### `SeedingLimitOverride`'s sentinel convention

A single new record, `SeedingLimitOverride(double ratioLimit, long timeLimitMinutes)`, using
the same "0 or negative means X" idiom `Settings` already uses repeatedly, not a nullable
wrapper type: per field, `< 0` means "use the global default," `0` means "explicitly no limit
for this torrent regardless of the global default," `> 0` is a custom value. Resolved against
`Settings` by two small pure functions on a new `SeedingLimits` class (`torrent` package, same
shape as `RateLimitSchedule`'s own resolvers).

### The engine decides, not `TorrentSession`

Resolution and the actual stop decision live in `TorrentEngine`, not `TorrentSession` itself -
keeps `TorrentSession` from needing yet another `Supplier<Settings>` threaded through its
already-long `create()`/`restoreAsync()` overload chains; it only needed to expose two small
new pieces of state (`completedAtEpochMillis`, `seedingLimitOverride`) plus what it already had
(`bytesUploaded()`/`bytesDownloaded()`).

A new engine-wide, daemon-threaded `ScheduledExecutorService` ticks every 30 seconds, checks
every currently-`SEEDING` session against its effective limit, and **reuses
`TorrentEngine.pauseTorrent()` outright** for the actual stop - not `TorrentSession.stop()`
directly. This was a real design correction made mid-implementation: an early draft called
`stop()` directly from the per-check loop and would have left `STATE_MARKER_FILENAME` on disk
stale ("RUNNING"), since only `pauseTorrent()` writes that marker; reusing it gets correct
persistence for free instead of duplicating it.

Made daemon-threaded specifically because it runs unconditionally for every `TorrentEngine`
instance (matching `RateLimiters`, no opt-in flag) - most of the existing test suite constructs
throwaway engines that never call `shutdown()`, and a non-daemon thread there would have leaked
a live thread per test.

### `completedAtEpochMillis`'s re-stamping guard

A new, purely in-memory `TorrentSession` field (0 until first set), stamped inside
`checkForCompletion()`'s existing `justCompleted` block, guarded by
`if (completedAtEpochMillis == 0)`. The guard matters: `enterDownloading()` already calls
`checkForCompletion()` unconditionally on *every* `start()`, including every resume and every
restart of an already-complete torrent (see its own comment, "a restore()d torrent can already
be fully complete before its first start()") - without the guard, a routine pause/resume cycle
would keep resetting the seed-time clock to zero.

### Per-torrent override persistence

A new marker file, `.grimtorrenter-seeding-limit-override`, plain `key=value` lines
(`grimtorrenter-engine` has zero production dependencies - no JSON library available at this
layer, same reasoning as every other marker file). Read in `restoreOne()` and in
`addTorrent()`'s reused-directory path (so a torrent removed with "keep files" and later
re-added picks its previous override back up), and deliberately never deleted by that
keep-files removal path - a torrent-directory-scoped preference like this stays with the data,
same as the data itself does.

### REST and frontend

`GET`/`PUT /api/torrents/{infoHash}/seeding-limits`, following the exact
`parseInfoHash`/`requireSession` shape every other per-torrent endpoint already uses. No DTO
wrapper - `SeedingLimitOverride` has no engine internals to hide, same reasoning `Settings`
itself was given in [[0045-settings-page]].

A new **Seeding limits** settings group (global defaults - ratio as a decimal multiplier, time
displayed/edited in hours and converted to/from the backend's minutes at the form boundary,
same "convert only at the edge" pattern already used for KiB/s), both defaulting disabled,
matching the rate limits' opt-in convention rather than [[0052-message-stream-encryption]]'s
`PREFERRED`-by-default exception - a limit that can silently stop a torrent is a much bigger
surprise to default on.

The per-torrent override lives in a new **"Seeding limits…" context-menu item opening a
`p-dialog`** - the first modal-with-a-form in this frontend (only `ConfirmationService`'s
simple accept/reject prompt existed before). Matches this project's own previously-stated
intent: [[0043-app-shell-and-filtering]] explicitly reserved a context-menu slot for exactly
this, deferred at the time only because the backing feature didn't exist yet. The dialog's own
3-state "use default / custom / no limit" control (no prior precedent - only a 2-state
value-plus-"Unlimited"-checkbox pattern existed, in rate-limit-settings) is a `p-select`
resolved with the real current default baked into its "Use default" label (e.g.
"Use default (2x)") rather than via a custom item template, paired with a number field enabled
only when "Custom" is selected.

### Two PrimeNG overlay bugs found while testing this feature

- **The dialog's own `p-select` dropdown rendered clipped behind the dialog's border.** A
  `p-select` nested inside a `p-dialog` needs `[appendTo]="'body'"` so its popup panel escapes
  the dialog's own stacking context rather than rendering clipped within it. This is a new,
  previously-undocumented PrimeNG gotcha in this codebase, saved to memory alongside the
  existing `styleClass`-encapsulation one, since it's the same general class of "PrimeNG
  overlay content doesn't inherit the consuming component's DOM position the way you'd expect"
  problem.
- **Pre-existing, unrelated to this feature but found in the same testing pass**: each
  `TorrentRow`'s own `p-contextMenu` (design_docs/0043) rendered at the wrong position (same
  `appendTo="body"` fix - it was clipped/mispositioned by the torrent table's own
  `overflow-y: auto` wrapper) and didn't close a previously-open sibling row's menu when a
  different row was right-clicked (a right-click only fires `contextmenu`, not `click`, so
  PrimeNG's own "click outside closes the menu" logic never saw it). Fixed with the same
  `appendTo` change plus a new small `ActiveContextMenuRegistry` service that explicitly closes
  whichever menu was previously open before showing a new one - each row's menu is otherwise a
  fully independent component instance with no awareness of its siblings.

### A load-test flake fixed along the way

`ManyTorrentsRestoreLoadTest` ([[0049-many-torrents-load-test]]) started failing consistently
(not just occasionally) partway through this feature's work, and the user noticed it correlated
with `mvn clean`. Root cause: its 40 `TorrentSession.restoreAsync()` calls happened sequentially
in a loop on the main thread, and `restoreAsync()` does its own synchronous file-I/O setup
(`TorrentStorage.create()`) before ever spawning the verification thread actually under test.
With only 4 tiny (4 KiB) pieces per torrent, verification is fast enough that earlier torrents
could fully finish and release their semaphore permits before later ones even started - the
semaphore's observed peak concurrency never rose above 1, not because nothing was bounding it,
but because nothing had ever actually asked for more than one permit at a time. A cold JVM/
filesystem cache (right after `mvn clean`) made the per-call setup slower, widening that
stagger - explaining why it went from "occasionally flaky" to "reliably failing" there. Fixed
by having all 40 `restoreAsync()` calls happen from their own threads, released together via a
shared start gate, so they genuinely race for the shared pool/semaphore at once - unrelated to
seeding limits themselves, but found and fixed in the course of this work.

## Testing

- `SeedingLimitsTest` (new) - the two pure resolver functions: inherit/custom/explicit-no-limit
  for both ratio and time, both enabled and disabled global defaults.
- `TorrentEngineTest` - the engine-wide scheduled check actually calls `pauseTorrent()` (and
  therefore persists `STATE_STOPPED`) once a seeding session crosses its effective ratio/time
  limit; a per-torrent override correctly disables a limit the global default would otherwise
  trigger; an override survives a restart. (The "override *enables* a limit the global default
  leaves off" direction is covered at the `SeedingLimitsTest` resolver level instead of here -
  with no real peer connection, actual ratio is always exactly 0.0, so there's no way to make a
  positive custom override trigger deterministically without a real sleep or real uploaded
  bytes; the resolver-level test doesn't have that problem since it doesn't need the threshold
  to have actually been reached.)
- `SettingsResourceTest`/`TorrentResourceTest` - the four new `Settings` fields and the two new
  per-torrent endpoints round-trip through the real REST layer.

## Stability ([[0051-stability-as-a-standing-consideration]])

The engine-wide scheduled check is O(active session count) per 30-second tick - bounded and
cheap, no new unbounded growth. Daemon-threaded specifically to avoid leaking a live thread per
short-lived test-constructed `TorrentEngine` (see above) - the one thing about this addition
that needed active thought about resource behavior rather than being an obvious no-op.

## Alternatives considered

- **An absolute byte value instead of a ratio** - rejected; doesn't scale with torrent size (5
  GB is generous on a 1 GB torrent, stingy on a 50 GB one), needing per-torrent retuning to mean
  the same thing twice, defeating the point of a shared default.
- **A 5th detail-drawer tab instead of a context-menu dialog** - rejected (user decision); the
  drawer's tab strip and content width are already documented as tight
  ([[0044-torrent-detail-drawer]]), and every existing tab is a read-only data view, not a form.
- **`TorrentSession.stop()` called directly from the periodic check** - rejected mid-
  implementation once traced through; see the persistence-correctness reasoning above.
