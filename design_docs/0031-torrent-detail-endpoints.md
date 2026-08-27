# 0031 — Per-torrent detail view endpoints

**Status:** Build order step 1 ("cheap tier") done on both backend and frontend -
`pieces`/`files`/`peers` endpoints are live and each has a working detail-view tab
(`TorrentDetail`, routed at `/torrents/:infoHash`); `usesDht` exists as a `TorrentSession`
capability but isn't wired to a REST endpoint yet (see Implementation notes below for why).
Step 2 (rate tracking) turned out to be entirely client-side, not backend work as
originally scoped - done, for both session- and peer-level rate, via a shared windowed
`RateTracker` (see Implementation notes). Step 3 (ETA) is also done, entirely
client-side as expected, and shown in both the list (a new column) and the detail
header. Step 4 (file progress) is also done, on both backend and frontend (see
Implementation notes). Step 5 (tracker status tracking) is now done end-to-end - engine,
`GET /api/torrents/{infoHash}/trackers`, and a Trackers detail tab. The Summary endpoint
that was meant to come after it was reconsidered instead of built as originally scoped -
see the Summary section and its Implementation notes below - and this inventory is now
complete: every field originally scoped either has a home or was deliberately dropped.

## Decision

Established while scoping DHT's first bit of REST visibility ([[0028-magnet-links-and-dht]]'s
`GET /api/dht/status` addendum): detail-level torrent data — anything beyond what a list
row needs — gets its own small, self-contained, on-demand REST endpoint, fetched only
while the UI view showing it is actually visible, rather than growing `TorrentView` or the
2-second snapshot broadcast ([[0019-rest-and-websocket-layer]],
[[0027-table-row-identity-for-live-updates]]). Confirmed with the user this matches the
frontend's planned shape: a tabbed detail view (or separate self-contained components) —
Summary, Piece map, Files, Peers, Trackers — each backed by its own endpoint, polled on
its own cadence only while mounted, not pushed.

Originally scoped as five endpoints including a dedicated `/summary`. That didn't survive
contact with how the other four actually got built - see "Summary: no dedicated endpoint
after all" in Implementation notes for what changed and why. `TorrentView` (the list-row
DTO) gained two fields (`usesDht`, `trackerCount`) as a result; it's otherwise unchanged by
any of this.

### `GET /api/torrents/{infoHash}/summary` — not built; superseded

Originally scoped as the "at a glance" detail tab, a superset of `TorrentView` plus
`usesDht`, `trackerCount`, rate, ETA, and tracker seeders/leechers. Rate and ETA turned out
to be entirely client-side (steps 2-3), and the detail header turned out not to need a
dedicated fetch at all - it already reads the same live `TorrentView` data the list uses.
By the time tracker seeders/leechers existed (step 5's `/trackers` endpoint), a Summary
endpoint would have had exactly two fields left to justify itself (`usesDht`,
`trackerCount`) that duplicated nothing already on screen - not enough to earn a whole
endpoint and tab. See Implementation notes for where those two fields actually landed.

### `GET /api/torrents/{infoHash}/pieces` — done

Cheapest endpoint by far - `PieceManager.stateOf(pieceIndex)` (`NEEDED`/`IN_PROGRESS`/
`COMPLETE`) already exists exactly for this. Needs only a `TorrentSession` accessor
exposing it and a thin DTO.

| Field | Source |
|---|---|
| `pieceStates: List<String>` (one per piece, in index order) | Existing (`PieceManager.stateOf`) |

A plain string-per-piece array is the v1 shape; a compact bitset/run-length encoding is a
later optimization if a piece count in the thousands makes the plain array too large - not
worth pre-optimizing before it's a proven problem.

### `GET /api/torrents/{infoHash}/files` — done

| Field | Source |
|---|---|
| `pathSegments`, `length` | Existing (`TorrentFile`, via a new `TorrentMetadata.files()` default method that normalizes `SingleFileTorrent` - which has no "files" list of its own on the wire - and `MultiFileTorrent` into one shape) |
| `bytesDownloaded` per file | **Done** — `TorrentSession.files()` composes existing `PieceManager` primitives (`pieceOffset`/`pieceLength`/`isComplete`), splitting each piece's contribution proportionally across every file it overlaps, since files aren't piece-aligned. Piece-granular (whole completed pieces only, same verified-only basis as `bytesDownloaded()`/`progress()`), not block-granular. No `progress` percentage field - the frontend derives it (`bytesDownloaded / length`) rather than duplicating it over the wire. |

### `GET /api/torrents/{infoHash}/peers` — existing-field subset done

| Field | Source |
|---|---|
| `address`, `port`, `peerId` (hex) | Existing (`PeerConnection.remoteAddress`/`remotePeerId`) |
| `amChoking`, `amInterested`, `peerChoking`, `peerInterested` | Existing (`PeerConnection`) |
| `downloadedBytes`, `uploadedBytes` (cumulative, this connection) | Existing (`PeerConnection`) |
| — exposing the connection set at all | **New** — `TorrentSession` doesn't expose its `Set<PeerConnection>` today; needs a snapshot DTO built from it (not the raw `PeerConnection`, which stays an engine-internal type per [[0006-engine-layering]]) |
| `percentAvailable` | **New composition** — iterate `PeerConnection.peerHasPiece` across every piece index; cheap to write, not written |
| `downloadRateBytesPerSec`, `uploadRateBytesPerSec` (this connection) | **New** — same rate-tracking gap as Summary, applied per-connection instead of per-session |
| `clientName` (nullable) | **New, isolated utility** — decode a peer id's BEP 20 prefix (e.g. `-GT0100-`) against a small known-client table; independent of everything else here |

### `GET /api/torrents/{infoHash}/trackers` — done

Was the one real gap, not just an exposure problem - `TrackerClient`/`MultiTrackerClient`
retained **zero state** between announces (`announce()` was a one-shot call, nothing
recorded when it last ran, whether it succeeded, or what it returned beyond the peer list
`TorrentSession` immediately consumed and discarded the rest of). See "Tracker status
tracking (step 5)" below for the full engine-through-frontend writeup.

| Field | Source |
|---|---|
| `url`, `tier` | Existing (already the shape `selectTrackerTiers` works with) |
| `status` (`WORKING`/`ERROR`/`UNKNOWN`), `lastAnnouncedAt`, `nextAnnounceAt`, `lastError`, `seeders`, `leechers` | **Done** — `TorrentSession.trackers()` (see Implementation notes) |

## Build order

Confirmed with the user as the priority: cheap, independent items first; the two
genuinely-new subsystems (rate tracking, tracker status tracking) after, since several
endpoints above depend on one or the other.

1. **Pieces, Files (static fields only), Peers (existing-field subset), `usesDht`** -
   independent of each other and of any new subsystem; pure exposure/composition of what
   already exists. **Done** - see Implementation notes.
2. **Rate tracking** - one subsystem, built once, centrally, feeding both Summary's
   overall rate and Peers' per-connection rate rather than two separate implementations.
   Exact shape (sampling interval, rolling-window size, where the sampler lives) is an
   implementation-time decision, not fixed here.
3. **ETA** - trivial once rate tracking exists (`bytesRemaining / downloadRateBytesPerSec`).
   **Done.** Entirely client-side (`FormatEtaPipe`), matching the style guide's own rule:
   humanized to the largest two units (e.g. "4h 12m"), never an infinity symbol, and an
   incomplete torrent with a zero rate reads "Stalled" rather than a blank or `0s`.
   `bytesRemaining` is `totalLength - bytesDownloaded` (the verified metric, same basis as
   the progress bar) - not `bytesReceived`, even though the rate it's divided by comes
   from `bytesReceived`; remaining work should reflect what's actually still needed, not
   be polluted by not-yet-verified data that could still be discarded. Added as a new ETA
   column in the list (between Peers and Actions, matching the guide's own column order)
   and in the detail header.
4. **Files' per-file progress** - moderate composition work, no dependency on the above.
   **Done.**
5. **Tracker status tracking + the Trackers endpoint** - the biggest lift, done last.
   **Done**, end-to-end (engine, REST, frontend tab).

## Implementation notes (cheap tier)

- **`usesDht` was built as `TorrentSession.isTrackerless()` but deliberately not wired to
  any REST endpoint yet.** Shipping a `Summary` endpoint at the time with only 2 of its ~6
  fields real (the rest permanently absent until steps 2/3/5 landed) would have read as a
  half-finished feature rather than normal incremental API growth - better to wait until a
  complete, honest endpoint could ship at once. The capability itself was trivial (one
  `instanceof` check) and already covered by
  `TorrentSessionTest#isTracklessReflectsTheTrackerClientKind`. As it turned out, "wait
  until Summary can ship complete" resolved into "Summary never ships at all" - see below.
- **New engine-side surface added**: `TorrentMetadata.files()` (default method,
  normalizes single- vs multi-file shape), `TorrentSession.pieceStates()`,
  `TorrentSession.PeerSnapshot` + `TorrentSession.peers()`, `TorrentSession.isTrackerless()`.
  All package/method-level additions to existing classes - no new engine packages or types
  beyond the one small nested `PeerSnapshot` record.
- **App-side**: `FileView`, `PeerView` (new DTOs, following `TorrentView`'s own
  `from(...)` conversion-at-the-boundary pattern) and three new `GET
  /api/torrents/{infoHash}/{pieces,files,peers}` methods added directly to the existing
  `TorrentResource` - unlike `DhtResource`, these are torrent-scoped sub-paths of the
  torrent resource, not a separate global resource, matching how `pause`/`resume` already
  live there as `{infoHash}`-scoped sub-paths.
- **Pieces returns `List<String>`** (piece state names), not the raw `PieceState` enum
  directly - keeps the JSON contract decoupled from the engine enum's Java identity, and
  avoids inventing a parallel app-layer enum purely to re-declare three values that
  already mean exactly what a UI wants to show.
  **Revised in [[0032-style-guide-and-primeng-theme]] (task 7)**: `GET .../pieces` now
  returns `PiecesView {pieces: List<String>, pieceLength: long}`, a small wrapper record,
  instead of the bare array - the Pieces tab's redesigned caption needed a per-piece size
  that `TorrentMetadata.pieceLength()` already carried but nothing exposed before then. The
  decoupled-JSON-contract reasoning above still stands; only the top-level shape changed.

### Frontend (`TorrentDetail`, routed at `/torrents/:infoHash`)

- **No dedicated request for the header** - it reads the same `TorrentEventsService`
  signal the list view already keeps live (looked up by infoHash), confirming the
  no-Summary-endpoint-needed-yet reasoning from the Decision section above in practice,
  not just in theory.
- **Real bug hit and fixed**: polling an endpoint by reading a `input.required<string>()`
  signal directly inside an eagerly-subscribing RxJS chain
  (`interval(...).pipe(switchMap(() => fetch(someInput())))`, constructed as a field
  initializer) throws `NG0950` - the field initializer runs during the component's
  constructor, before Angular has actually bound the input, and `startWith`-style
  synchronous first emissions read it immediately. Fixed by building every detail tab's
  poll on `toObservable(inputSignal)` instead (specifically designed to defer the first
  read correctly), extracted into a shared `pollWhileInput()` helper once a second tab
  needed the identical pattern - centralizes the fix so a future tab can't reintroduce the
  same mistake by copying the naive version.
- **Files now polls, same as Pieces/Peers** (previously fetched once per mount - see
  step 4's own Implementation notes below for why that changed).
- **`p-tabs`'s `lazy` input needed setting explicitly** (`[lazy]="true"`) - it defaults to
  `false`, which would construct (and start polling in) all three tabs immediately on
  page load rather than only the one actually open, undermining the "on-demand, only
  while visible" point of self-contained detail endpoints in the first place.
- **Per-peer rate deferred** from the Peers tab pass itself - but before building it, the
  session-level rate calculation it would have copied was revisited first (see below),
  since duplicating it as-was would have meant two copies of the same noisy math.

### Windowed rate tracking (`shared/rate-tracker.ts`)

`TorrentEventsService`'s original rate calculation was a raw two-sample delta
(`(current - previous) / elapsed`) - noisy, since a single unusually slow or bursty
snapshot interval visibly swings the displayed number even when the real speed is steady.
Replaced with `RateTracker`: a small framework-agnostic class (not an Angular service,
since session-level and future per-peer tracking have different natural owners/lifetimes)
that keeps one bounded reading-history per key and derives a rate for each of several
configured time windows (default `5s`/`15s`/`60s`) from that same history - no per-window
history duplication. `TorrentEventsService` now owns two instances (download, upload),
replacing its old single-previous-reading `Map`.

**Extended beyond a single smoothed number while designing it**: rather than only
exposing the "primary" window's rate (still what's shown inline, unchanged consumer-facing
shape), `RateTracker` returns every configured window's rate together
(`RateSnapshot.byWindow`). `TorrentWithRate` carries both directions' full breakdown
(`downloadRateWindows`/`uploadRateWindows`), and the torrent list row now shows it as a
`pTooltip` on hover (via a new `FormatRateWindowsPipe`) - a short-window rate sitting well
below the long-window one is a visible signal of a recent stall or interruption, which a
single averaged number would otherwise hide. This was a deliberate scope addition during
design, not part of the original "just smooth the noisy rate" ask, because the
multi-window data was already sitting in `RateTracker`'s history for free - the marginal
cost of exposing it was small enough that building the single-window version first and
retrofitting this later would have been the more wasteful order.

**Per-peer rate is now built too** (`PeersTab`), reusing the exact same `RateTracker` and
`FormatRateWindowsPipe` - `PeersTab` owns its own `RateTracker` pair rather than sharing
`TorrentEventsService`'s, since a peer's key (`address:port`) only makes sense within one
torrent's currently-open tab, with no natural place in the app-wide singleton. One added
wrinkle session-level tracking didn't need: a peer that disconnects has to have its
history explicitly forgotten (`forgetDisconnectedPeers`, diffing each poll's key set
against the previous one) - otherwise a reconnecting peer, or a different peer that
happens to reuse the same address:port, would resume from stale history instead of a
fresh rate, and a long-running session would accumulate history forever for every peer
ever seen on that torrent. `TorrentEventsService` doesn't need this itself since
`removeLocal` already provides an explicit, targeted removal hook tied to the one action
(deleting a torrent) that retires a key - the Peers tab has no equivalent explicit signal
for "this peer is gone," only its absence from the next poll.

**Real bug found once the Peers tab made it visible**: the session-level download rate
was reading 0 for long stretches on real torrents, while per-peer rate worked fine.
Root cause: `TorrentSession.bytesDownloaded()` (what `TorrentView`/the list use) only
counts fully-verified-complete pieces - for any torrent with a piece length larger than
what completes within a rate window (common; pieces are often 1-16 MB), it can sit
completely flat for the whole window even while data is genuinely streaming in block by
block, which `RateTracker` correctly reported as a 0 rate given a flat input. Per-peer
rate worked because `PeerConnection.downloadedBytes` increments per received block,
continuously - unrelated to verification.

Fixed by adding a second backend metric, `TorrentSession.bytesReceived()` (new
`TorrentView.bytesReceived` field), mirroring `bytesUploaded()`'s existing
accumulator-plus-live-connections pattern exactly (an `accumulatedReceived` counter
absorbs each connection's tally on disconnect, so the total never drops when a peer
leaves) - sourced from raw per-block wire bytes, not verification state. **`bytesDownloaded`
itself is untouched** and still verified-only, since it drives the progress bar/% and
must never move for data that could still fail verification and get discarded; only
`TorrentEventsService`'s rate calculation switched to reading `bytesReceived` instead.
Confirmed with the user as a real design fork (three options discussed: track raw bytes
separately, compute from `PieceManager`'s existing partial-block state instead of new
tracked state, or accept the coarse-but-strictly-honest number as-is) rather than
resolved silently, since it touches what "downloaded" means for two different purposes
(progress vs. rate) that had previously been conflated into one field.

### File progress (step 4)

`TorrentSession.files()` returns a new `FileProgress` record (`pathSegments`, `length`,
`bytesDownloaded`) per file, computed by `downloadedInRange`: iterate every piece, and for
each one that's complete, add however much of it falls within the file's
`[fileStart, fileEnd)` byte range. Files are laid out contiguously with no alignment to
piece boundaries (standard BitTorrent layout), so a single piece routinely spans two
files - the whole reason this is "moderate composition work" rather than a one-line
lookup. Covered by `TorrentSessionTest#filesReflectsPerFileDownloadProgressAcrossAPieceBoundary`,
which drives a real download (via the file's established fake-peer/`ServerSocket` fixture
style) across a 2-file, 3-piece layout deliberately built so the middle piece straddles
the file boundary, and asserts the split mid-download (right after that piece completes,
not just at the fully-downloaded end state, which a wrong split could also satisfy).

`FileView` (app layer) gained the same `bytesDownloaded` field, and
`TorrentResource.files()` now calls the new `TorrentSession.files()` instead of
`TorrentSession.metadata().files()`.

**Frontend**: `FilesTab` switched from a single fetch per mount to `pollWhileInput` (same
pattern as Pieces/Peers) now that the response can genuinely change over time, and its
table gained a Progress column (`p-progressBar` + bytes/total text, mirroring the
list/detail-header pattern). No `progress` percentage field was added to the wire
contract - the frontend derives it (`bytesDownloaded / length`) rather than duplicating a
value the client can trivially compute, consistent with `bytesRemaining`/ETA already being
derived client-side rather than sent from the backend.

**Note (later superseded):** `FilesTab`, `PeersTab`, and `TrackersTab` were all originally
built as a `p-table` (as described throughout this doc). [[0044-torrent-detail-drawer]]
later moved `TorrentDetail` into a narrow (~430px) slide-out drawer and replaced all three
with a stacked card list instead - a wide multi-column table doesn't fit that width. The
underlying endpoints/DTOs documented here are unaffected; only the presentation changed.

### Tracker status tracking (step 5)

**`TrackedTrackerClient`** (new, `tracker` package) wraps one leaf tracker client
(`HttpTrackerClient`/`UdpTrackerClient`) and records a `TrackerStatus` snapshot
(`url`, `tier`, `state`, `lastAnnouncedAt`, `nextAnnounceAt`, `lastError`, `seeders`,
`leechers`) on every `announce()` call, without changing that call's own success/failure
behavior at all - it still returns/throws exactly as the delegate would. This means
`MultiTrackerClient`'s existing tier-fallback logic (see [[0022-multi-tracker-fallback]])
needed **no changes to its `announce()` method** - only a new `statuses()` that aggregates
every wrapped tracker's own status, including ones a given `announce()` call's
short-circuiting-on-first-success never reached (their status just reflects whenever they
were last, or never, attempted - an honest "this backup tracker hasn't been needed" rather
than a fabricated value).

Querying flows through a new `default List<TrackerStatus> statuses() { return List.of(); }`
on the `TrackerClient` interface itself (same "empty default, meaningful override" shape as
`TorrentMetadata.files()`) - `NoOpTrackerClient` inherits the empty default for free (a
trackerless torrent has nothing to report), and `TorrentSession.trackers()` is a one-line
delegate to `trackerClient.statuses()`. **No changes were needed to `TorrentSession`'s
constructor, `create()`/`restoreAsync()` signatures, or any of their call sites/tests** -
the session already held its `TrackerClient` opaquely, so the whole feature threads through
without touching that layer's wiring at all, unlike DHT's `TorrentSession`-constructor
change in [[0028-magnet-links-and-dht]].

`TorrentEngine.createTrackerClient` now wraps each leaf client it builds in a
`TrackedTrackerClient(url, tierIndex, delegate)` before handing tiers to
`MultiTrackerClient` - the only wiring change needed.

**Two behaviors confirmed with the user rather than assumed** (both affect what a stale or
erroring tracker looks like in the eventual Trackers tab):
- **A failed announce keeps the tracker's last-known `seeders`/`leechers`** rather than
  clearing them to null - `state`/`lastError`/`lastAnnouncedAt` already say how fresh the
  count is, so a stale-but-recent number reads as more useful than blanking it.
- **`nextAnnounceAt` is per-tracker, derived from that tracker's own last successful
  response's `interval`** (`lastAnnouncedAt + response.interval()`), not from
  `TorrentSession`'s actual reannounce schedule (a session-level value that would have
  needed passing down into a layer that doesn't otherwise know about session scheduling).
  It's `null` for any tracker that's never succeeded, or just failed - genuinely unknown,
  especially for a backup tracker that won't be retried at all while a higher-priority one
  keeps working.

Covered by `TrackedTrackerClientTest` (initial `UNKNOWN` state, success recording,
failure recording including the last-known-seeders/leechers behavior above, and that the
original exception is rethrown unchanged) and a new `MultiTrackerClientTest` case proving
`statuses()` aggregates across tiers including an unreached tracker. `TorrentSessionTest`
adds one thin delegation test - the actual tracking logic is entirely covered at the
tracker-package level, so this only proves the wiring.

**App layer**: `TrackerView` (new DTO, following the same `from(...)` conversion-at-the-
boundary pattern as `FileView`/`PeerView`) re-exposes `TrackerStatus.state` as a plain
`status: String` (the enum's `.name()`) rather than the Java enum directly - same
JSON-contract-decoupling rationale as Pieces returning `List<String>` instead of the raw
`PieceState` enum. `TorrentResource.trackers()` added as a fourth `{infoHash}`-scoped
sub-path, same shape as `pieces`/`files`/`peers`. `lastAnnouncedAt`/`nextAnnounceAt` are
`java.time.Instant`, serialized by `quarkus-rest-jackson`'s default ISO-8601 string
encoding - no custom (de)serialization needed.

Tested in `TorrentResourceTest` against a torrent with a deliberately unreachable tracker
(the file's existing convention) - since `TorrentSession.start()`'s initial announce is
synchronous, the tracker's `ERROR` status is already recorded by the time the upload
request returns, unlike the async DHT/piece-download tests elsewhere in that file that
need to poll for a background state change.

**Frontend**: `TrackersTab`, a fourth detail tab (`Pieces`/`Files`/`Peers`/`Trackers`),
polling via `pollWhileInput` like Pieces/Peers. Status renders as a `p-tag` (`WORKING` →
success, `ERROR` → danger, `UNKNOWN` → secondary, mirroring `torrent-row.ts`'s own
state-to-severity mapping) with `lastError` shown underneath when present.
`lastAnnouncedAt`/`nextAnnounceAt` render via Angular's built-in `DatePipe` (`'short'`
format) rather than a new custom pipe - no existing convention in the style guide called
for anything more elaborate, and a missing value (a tracker that's never announced, or a
non-`WORKING` one's `nextAnnounceAt`) shows as an em dash. No new `Tracker`-specific
rate/windowing concept was needed - unlike Peers, a tracker's own response already carries
absolute seeders/leechers counts, not something to average over time client-side.

### Summary: no dedicated endpoint after all

Once step 5 landed, every field the original `/summary` scoping listed existed somewhere -
the only open question was where it should be *displayed*, and by then the answer was
already sitting in front of us: `TorrentDetail`'s header (see "No dedicated request for the
header" above, from the cheap-tier pass) had been reading `TorrentEventsService`'s live
`TorrentView`-sourced data since the very first detail-view slice, and that already covered
`infoHash`/`name`/`state`/`progress`/`bytesDownloaded`/`bytesUploaded`/`totalLength`/
`connectedPeers`/`completedPieces`/`totalPieces`/`lastError` plus client-side rate/ETA. The
only fields a `/summary` endpoint would have contributed that weren't already on screen were
`usesDht` and `trackerCount` - two booleans-and-an-int, not a fifth tab's worth of content.

**Confirmed with the user rather than assumed**: added both fields directly to
`TorrentView` instead of building a `/summary` endpoint - they ride the existing
WebSocket-pushed snapshot the header (and list) already consume, no new fetch needed.
Both are now cheap: `usesDht` is `TorrentSession.isTrackerless()` (already built, see
above), `trackerCount` is `session.trackers().size()` (trivial once step 5 existed - the
original scoping's own note that this "needs a small new accessor on
`MultiTrackerClient`" turned out true, just satisfied by step 5's work rather than new
work of its own). This is a deliberate, acknowledged departure from the Decision section's
"detail-only data doesn't belong in the always-broadcast snapshot" principle - the
exception being that these two fields are cheap enough (no per-tick computation, just a
field read and a `.size()`) that the principle's actual concern (bloating a broadcast that
fires every 2 seconds with expensive-to-compute detail data) doesn't apply to them.

The detail header shows `usesDht` as a `p-tag` ("DHT") when true, or `{trackerCount}
tracker(s)` otherwise - mutually exclusive today since DHT is only ever used for a
genuinely trackerless torrent (see design_docs/0028's slice 6 scope note on the still-
deferred DHT backstop for regular torrents with dead trackers); this display will need
revisiting if that backstop is ever built. Not added to the list view - the list's row
count is already tight, and none of this was actually requested for the list, only for the
detail header the original Summary tab was meant to occupy.

## Alternatives considered

- **One combined "detail" endpoint returning everything** - rejected. Over-fetches for a
  tabbed UI where only one sub-view is visible at a time (computing a full peer list and
  piece map just to render the Files tab), and different tabs want different refresh
  cadences (peers change while connected; files/trackers barely change at all).
- **Folding detail fields into `TorrentView`/the 2-second snapshot broadcast** - rejected.
  Bloats the always-on payload pushed to every connected client regardless of what they're
  looking at, for data most clients aren't viewing at any given moment.
- **A per-torrent WebSocket subscription for live detail updates** (e.g. streaming piece
  completion or peer connect/disconnect events) - considered, deferred. Plain REST polling
  only while a view is mounted is simpler and matches this app's existing style; revisit
  if a detail view's polling cadence ever proves too coarse for something (peer
  connect/disconnect flicker, maybe).
