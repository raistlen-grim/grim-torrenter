# 0102 - Server-side transfer rates, ETA with a reason, rate caps shown, and transfer history

Status: Accepted (2026-10-19). Wanted in the beta, all three stages. Replaces the client-side rate
calculation of [[0020-frontend-torrent-dashboard]], [[0025-seeding-and-upload-logic]],
[[0031-torrent-detail-endpoints]] and [[0063-rate-trend-tooltip]]. Applies
[[0051-stability-as-a-standing-consideration]] and
[[0071-thin-frontend-as-a-standing-consideration]].

## Why

Every rate the UI shows is computed in the browser:
- **Torrent rates:** `TorrentEventsService` feeds each 2 s snapshot's `bytesReceived`/`bytesUploaded`
  into a `RateTracker` (a 15 s window, plus 60 s of samples for the Down cell's sparkline).
- **Peer rates:** `PeersTab` keeps its own `RateTracker` over the 3 s peers poll.
- **ETA:** `FormatEtaPipe` divides `totalLength - bytesDownloaded` by the download rate.
- **Totals:** the header and footer sum the rows; the footer computes the lifetime ratio.

What that costs:
- **Every other client must rebuild it.** The REST API carries byte counters, not rates.
- **The numbers depend on the viewer.** Two tabs sample at different moments and disagree. A
  freshly loaded page shows 0 B/s until two snapshots arrive, and an understated rate until the
  15 s window fills.
- **One window serves two jobs badly.** 15 s is too slow for "is it moving right now" (a stalled
  torrent shows a decaying rate for 15 s) and too short for a steady ETA.
- **ETA is wrong with skipped files.** `totalLength - bytesDownloaded` counts bytes of files the
  user skipped ([[0075-file-priorities]]), so the ETA is too long. The engine already has
  `bytesRemaining()`, which counts wanted bytes only.
- **"Stalled" explains nothing.** It only means the rate is 0. The engine knows whether there are
  no peers, whether no connected peer has the missing pieces, whether every peer is choking us,
  and whether our own cap is the bottleneck. None of that reaches a client.
- **History ends at 60 s and dies with the tab.** "Why was it slow last night?" and "did the 1 am
  schedule kick in?" ([[0046-rate-limit-schedule]]) have no answer.

The engine already has what it needs: per-connection byte counters on the block path
(`PeerConnection.downloadedBytes`/`uploadedBytes`), the rate limiters on the same path, per-peer
have-sets (what `relevance` is computed from, [[0067-per-peer-progress-and-tracker-metrics]]), and a maintenance
scheduler.

## Stage 1 - measure once, on the server

### 1.1 `TransferMeter`: per-second buckets from the byte counters

A new engine class, `stats/TransferMeter`, one per direction per counted thing:
- **Fed from the counters bytes already go through.** `PeerConnection` already does
  `downloadedBytes.addAndGet(...)` when a block arrives and `uploadedBytes.addAndGet(...)` when one
  is sent. The 1 s tick reads each connection's two counters and meters the change since the last
  tick. The block path gains nothing - no new counting site, not even a second `addAndGet`.
- **Buckets.** Each tick's change, divided by the real time since the last tick, goes into a
  60-slot ring of bytes/sec. Only the tick writes the ring. After each write it publishes an
  immutable `TransferRate` record (the samples as a `long[]` copy plus the derived figures below)
  through a `volatile` field. Readers (snapshot, REST) read that field and never lock. No
  `synchronized` anywhere on the path ([[0007-concurrency-model]]).
- **Three levels from one mechanism.** Each peer connection has a meter. A torrent's meter takes
  the sum of its connections' changes for the tick, so a torrent always equals the sum of its
  peers. The global meter is the sum of the torrents'. The header total equals the sum of the
  rows by construction.
- **Disconnects lose nothing, and count once.** `torrent/SessionTransfer` keeps each connection's
  counter readings between ticks. A connection that closed since the last tick is still there: its
  final change is counted, then it's dropped. Differencing `bytesReceived()` instead would not be
  safe: `TorrentSession` folds a closing connection into its accumulators *before* removing it
  from the live set, so for a moment `bytesReceived()` counts it twice.

The existing `PeerConnection.downloadRateBytesPerSec` (a 1 s average used to size the request
pipeline, [[0080-cross-peer-request-coordination]]) stays as it is. It is a control input with its own tuning,
not a display figure.

### 1.2 Two averages for two questions

Derived on each tick and stored in the published `TransferRate`:
- **`bytesPerSec`** - "how fast right now". The mean of the last 5 per-second buckets. A stalled
  torrent reads exactly 0 after 5 seconds. (An exponential average was the first idea; it never
  actually reaches 0, so a torrent that stopped would show a few B/s for a minute.)
- **`averageBytesPerSec`** - "how fast on average", used for ETA only. The mean over the last
  60 s, or over this run if it's younger than that. Steady across choke rounds.
- **`samples`** - the 60 per-second values, oldest first, for the sparkline and the per-second
  history.

A paused or stopped torrent reads 0 at once (the session returns an idle rate whenever it isn't
running), and the next tick clears its meters, so a resume starts fresh. The tick does the
clearing, which keeps the meters single-writer.

### 1.3 The sampling tick

One `GuardedTask` on `TorrentEngine`'s existing `maintenanceScheduler`, every 1 s: for every
running session, roll each connection's meter, then the torrent's, then the global one. No new
thread. It runs whether or not any browser is connected, so a REST-only client gets rates too
(the WebSocket snapshot is skipped when nobody listens, [[0094-session-revocation-error-shape-and-api-consistency]]).

Cost: one pass over sessions and their connections per second - the same walk the 2 s snapshot
already does for `bytesReceived()`, with no allocation beyond the published records.

### 1.4 ETA

`TorrentSession.eta()` returns an `Eta(Long seconds, EtaStatus status, int unavailablePieces)`:
`seconds = ceil(bytesRemaining() / averageBytesPerSec)`. `bytesRemaining()` counts wanted bytes
only, which fixes the skipped-files bug. Stage 2 fills in the status.

### 1.5 REST and WebSocket

- **`TorrentView`** gains `downloadRateBytesPerSec`, `uploadRateBytesPerSec`, `etaSeconds`,
  `etaStatus`, `unavailablePieces`, and `downloadRateTrend`.
  - `downloadRateTrend` is the 60 per-second samples for a torrent that moved data in the last
    60 s, and `[]` otherwise. The payload grows with active torrents, not library size, and the
    tooltip already only shows while the rate is above zero.
  - `bytesReceived` is removed: its only purpose was the client's rate calculation. Pre-beta, no
    other client exists to break.
- **`PeerView`** gains `downloadRateBytesPerSec`, `uploadRateBytesPerSec`, `downloadRateTrend`.
- **Session-wide figures.** `GET /api/stats/transfer` returns the global rates, the effective
  global limits (stage 2), and the lifetime ratio (sum of `lifetimeUploadedBytes` over sum of
  `bytesDownloaded`, as the footer computes today). The same object rides the 2 s WebSocket tick
  as a new `transfer` message, beside `snapshot` and `labels`. The selection summary's totals
  stay client-side: they're over rows the viewer chose, which is per-viewer state (0071).
- No upload trend, matching 0063's decision that only Down shows a sparkline. Adding one later
  is one field.

### 1.6 Frontend

Mostly deletions:
- **Deleted:** `shared/rate-tracker.ts` and its spec; `TorrentEventsService`'s `ratesByHash`,
  rate trackers and `recordReading()`; `PRIMARY_RATE_WINDOW_MS`; `PeersTab.withRates()` and its
  trackers.
- **Models:** `TorrentWithRate` and `PeerWithRate` go; their fields move onto `Torrent` and
  `Peer`, read straight from the server.
- **ETA:** `FormatEtaPipe` formats `etaSeconds`/`etaStatus` instead of doing arithmetic.
- **Header and footer:** read the `transfer` message instead of summing rows and computing the
  ratio.
- **Parsing:** `parseEventMessage` ([[0101-frontend-shared-actions-and-add-controller]]) gains the
  `transfer` message.

### What the user sees change in stage 1

- Rates are right on the first frame after a page load, the same in every tab and client.
- A torrent that stops reads 0 within a few seconds, not 15.
- ETA is steadier, and correct for torrents with skipped files.
- Sparklines have per-second resolution (today: one sample per 2 s snapshot).

## Stage 2 - say why, and show the cap

### 2.1 ETA status

`etaStatus`, in precedence order (the first that applies wins):

| Status | When | Row reads | Tooltip |
|---|---|---|---|
| `COMPLETE` | nothing wanted remains | — | |
| `NOT_RUNNING` | paused, stopped, error, verifying, fetching metadata | — | |
| `NO_PEERS` | running, no connected peers | No peers | Not connected to any peers |
| `UNAVAILABLE` | some wanted, incomplete pieces are held by no connected peer | 12 unavailable | 12 pieces not held by any connected peer - it can't finish with this swarm |
| `CHOKED` | peers have what we need, but every one is choking us | Waiting | Peers have what is needed, but none is sending yet |
| `STALLED` | none of the above, and `averageBytesPerSec` is 0 | Stalled | Nothing has arrived in the last minute |
| `ESTIMATE` | otherwise | 4h 12m | |

The row text is short because the ETA cell doesn't wrap (`white-space: nowrap`); the longer
wording first proposed here ("12 pieces unavailable", "Waiting for peers") moved into the cell's
tooltip. A paused torrent with data left now reads "—" (`NOT_RUNNING`) where it used to read
"Stalled".

`etaSeconds` is set only for `ESTIMATE`. `unavailablePieces` is set for `UNAVAILABLE` (0
otherwise).

**`UNAVAILABLE` wins over a moving rate on purpose.** A torrent can download steadily for an hour
and then stop at 97 % because no connected peer has the last pieces. A time estimate there is a
promise the swarm can't keep; "12 pieces unavailable" says what's actually going to happen.

**Computing it.** The OR of connected peers' have-sets, ANDed with our wanted-and-incomplete set,
counted. With `BitSet`s that is one word operation per 64 pieces per peer: a 50,000-piece torrent
with 50 peers is about 40,000 word operations. Computed on demand when a `TorrentView` is built,
and only for a torrent in `DOWNLOADING`. If profiling shows otherwise, it moves to every 5th
sampling tick with the result cached on the session.

### 2.2 Rate caps on the torrent

`TorrentView` gains, per direction:
- **`downloadLimitBytesPerSec`** / **`uploadLimitBytesPerSec`** - the limit currently applying to
  this torrent; 0 for none.
- **`downloadLimitSource`** / **`uploadLimitSource`** - `NONE`, `GLOBAL`, `SCHEDULED` (the
  [[0046-rate-limit-schedule]] window is active) or `TORRENT` (a per-torrent override,
  [[0072-per-torrent-limits]]).
- **`downloadCapped`** / **`uploadCapped`** - true when the limiter actually held this torrent's
  traffic back in the last 3 s.

**Why a "throttled recently" flag, not "rate equals limit".** Inheriting torrents share one
global bucket (0072). A torrent at 200 KB/s can be capped because the global 1 MB/s bucket is
full of other torrents' traffic; comparing its own rate to the limit would never show it.
`RateLimiter.acquire()` returns whether it had to wait (it returned `void`), and `PeerConnection`
keeps the time of its last throttled block per direction. A torrent is capped when any of its
connections was throttled in the last 3 s. No new lock: a `volatile long` per direction per
connection.

`GET /api/stats/transfer` and the `transfer` message carry the same three fields for the global
pair. There, capped means the global limiter itself made something wait in the last 3 s; the
limiter keeps that time. A torrent with its own limit doesn't draw from the global bucket, so it
can be capped while the global figure isn't.

**UI:** the rate cell shows a small cap marker when capped, with a tooltip naming the source
("Capped by the global limit, 1.0 MB/s", "Capped by the off-hours schedule", "Capped by this
torrent's limit"). The header's totals show the same marker. **Decided with the user
(2026-10-19):** a small gauge icon after the rate, quiet when not capped, so the number itself
doesn't change width; the tooltip carries the source and limit, and the icon has the same text
as its accessible label. Chosen over colouring the rate (colour alone carries the meaning) and a
"· capped" text suffix (widens every capped row).

## Stage 3 - transfer history

### 3.1 What's kept

Global, both directions:
- 60 × 1 s (the meter's own samples);
- 120 × 1 min (2 hours);
- 168 × 1 h (7 days).

Per torrent, both directions:
- 60 × 1 s (the meter's samples);
- 60 × 1 min (1 hour).

Minutes and hours live in `stats/HistoryRing`, fed by every tick. Its intervals are aligned to
the wall clock (minute n is [n × 60 s, (n + 1) × 60 s) since the epoch), and interval n sits in
slot n mod capacity: writing an interval whose slot holds an older one replaces it, so there's no
close or expiry step. Each interval keeps its bytes and how much of it was actually sampled, and
reads as bytes over sampled time. An hour the app ran for ten minutes averages over those ten
minutes; an hour it didn't run at all is a gap. The newest value is the interval in progress.

`HistoryRing` is `synchronized`: the tick writes it once a second and readers are rare (someone
opening a graph). Neither is the block path, so 0007's rule isn't in play, and the lock keeps its
three arrays consistent.

Memory is fixed: about 350 longs global, about 240 per torrent (≈2 KB). A 1,000-torrent library
is about 2 MB.

### 3.2 Surviving restarts

The **global hourly ring is persisted** to `.grimtorrenter-transfer-history` in the config
directory - the engine's own key=value marker format ([[0099-engine-shared-helpers-and-hot-path-counters]]),
one `hour=index,downloadBytes,uploadBytes,sampledMillis` line per hour, not JSON (the engine has
no JSON library). Written on the existing lifetime-stats flush ([[0064-persistent-lifetime-stats]]),
every 30 s, and at shutdown, with the same atomic write. "Last night" survives an upgrade or container restart, which is when it would otherwise
be lost. Gaps (hours the app was down) are recorded as gaps, not zeros, so a graph doesn't
pretend nothing moved. The minute and second rings, and all per-torrent history, are in memory
only.

A missing, unreadable or corrupt history file **starts empty with a warning; it never fails
startup**. Unlike settings.json and auth.json ([[0093-cross-site-protection-and-failure-isolation]]),
losing it costs nothing but a graph.

### 3.3 Endpoints

- `GET /api/stats/history?resolution=second|minute|hour`
- `GET /api/torrents/{infoHash}/history?resolution=second|minute`

Each returns `{ resolution, intervalSeconds, endEpochMillis, download: [...], upload: [...] }`,
oldest first, with `null` for a gap. Plain values; a client draws them as it likes.

### 3.4 UI

- **Global graph:** a "Transfer" section with a 1 min / 2 h / 7 d switch. Download and upload as
  two lines on one rate axis, with the limits applying *now* drawn as faint dashed reference
  lines labelled "Download limit now ...". Only the current limits are known: the history keeps
  bytes, not the limit each interval ran under, so a schedule window can't be drawn as a step.
  The traffic itself still shows the schedule biting. Keeping each interval's limit would be a
  small engine addition if it's ever wanted (TECH_DEBT).
- **Per-torrent graph:** in the details panel, with a 1 min / 1 h switch.
- Drawn as inline SVG like the existing `RateTrend` sparkline; no chart library. One shared
  `TransferChart` component serves both: 2px lines in two chart-only colours (see 0032's
  2026-10-19 addendum), solid hairline gridlines, a legend with line keys that carries each
  series' latest value, gaps drawn as breaks, a crosshair snapping to the nearest interval with
  one readout for both series, the same readout from the keyboard (arrow keys, Home/End), and a
  "Show as table" view of every value so nothing is hover-only. The window switch is a small
  shared `RangeSwitch` (buttons with `aria-pressed` in a labelled group). While a new window
  loads, the previous graph stays on screen, faded.

**Placement, decided with the user (2026-10-19)** - the style guide covers neither:
- **The global graph gets its own sidebar page, "Transfer"**, beside Health, Events and Settings:
  full width for a 7-day view, with the current rates and ratio above it. Chosen over a group on
  the Health page (a checklist; a large graph changes its character) and a popover from the
  footer's totals (too small and transient for 7 days).
- **The per-torrent graph is a fifth details-panel tab, "Speed"**, beside Files, Peers, Trackers
  and Pieces: the 430 px panel gives a tab its full width and height. Chosen over a strip in the
  fact grid, which is already dense and has no room for the 1 min / 1 h switch.

## Order of work

1. **Engine** (written 2026-10-19): `TransferMeter` and its tick, ETA and its status,
   `RateLimiter.acquire()` reporting waits, the applied limit and its source, the history rings
   and their persistence. Unit tests pass times in rather than reading the clock: bucket rollover,
   the rate reaching 0, 60 s mean on a young run, a disconnect between ticks, status precedence,
   the capped window, ring gaps and replacement, the history file's round trip, a corrupt history
   file.
2. **App** (written 2026-10-19): the view fields, `GET /api/stats/transfer` (`TransferView`, which
   also carries the lifetime downloaded/uploaded totals behind the ratio), the `transfer` message,
   `GET /api/stats/history` and `GET /api/torrents/{infoHash}/history` (`HistoryView`;
   `resolution` is required, any case, and an unknown value - or `hour` per torrent - is a 400),
   and their tests.
3. **Frontend, stage 1** (written 2026-10-19): `RateTracker` and every rate calculation deleted;
   `Torrent`/`Peer` carry the server's fields (`TorrentWithRate`/`PeerWithRate` are gone); the
   header and footer read the `transfer` message, ratio included. The ETA reasons (2.1) came
   along, since they're only the ETA cell's text and tooltip.
4. **Frontend, stages 2-3** (written 2026-10-19): `CapMarker` (a gauge icon with the source and
   limit as tooltip and accessible name) after the rate in the row's Down/Up cells, the details
   panel's Down/Up facts, the header's totals and the Transfer page; the Transfer page
   (`/transfer`, in the sidebar between Health and Events) with the now-figures and the graph;
   the panel's Speed tab.

Stages 1-2 change no UI layout. Stage 3 adds UI and needs the placement decision first.

## Thin frontend

After this, a client needs no arithmetic to show anything a GrimTorrenter user sees about speed:
rates, ETA and its reason, whether a cap is biting and whose cap it is, session totals, the
lifetime ratio and history are all fields or endpoints. What stays client-side is genuinely
per-viewer: totals over the viewer's own selection, and formatting.

## Stability

- **Bounded memory.** Every ring is fixed-size; nothing grows with uptime. Per-connection meters
  die with the connection (bounded by the connection cap); per-torrent meters and history are
  dropped on remove. The persisted file is a fixed 168 entries per direction.
- **No new threads, no locks on the block path.** One more `GuardedTask` on the existing
  maintenance scheduler; a throw in it is logged and the next tick runs. The block path is
  unchanged apart from `acquire()` returning a boolean. Meter readers read a `volatile` immutable
  record; `HistoryRing`'s lock is taken once a second by the tick and rarely by a reader.
- **Garbage.** Each tick publishes one record with a copy of up to 60 longs per meter: four meters
  per connection pair (down and up for the connection and its torrent). At 200 connections that's
  roughly 200 KB a second of short-lived objects - nothing for the JVM, and it buys lock-free
  readers.
- **The tick can't fall behind dangerously.** `scheduleWithFixedDelay`, so a slow tick delays the
  next rather than piling up. A late tick rolls the bytes of a longer interval into one bucket and
  divides by the real elapsed time, so a late tick reads as the right average, not a spike.
- **Cleanup on every exit path.** The tick counts a closed connection's last bytes and drops its
  meters the first time it finds the connection gone from the live set, whatever closed it. A
  removed torrent's meters and history go with its session. A stopped torrent's meters are cleared
  on the next tick.
- **Hostile peers.** Rates count bytes we actually moved, so a peer can't inflate them. A peer
  can lie in its have-set and make `UNAVAILABLE` read optimistic; that's a display figure only,
  and the same lie already skews `relevance`.
- **Failure.** A corrupt history file starts empty; it never blocks startup.

## Alternatives considered

- **Keep the client's calculation; just expose rates for other clients (parity only).** Moves the
  maths but keeps the single window, the bare "Stalled", and no history. About the same work as
  stage 1, without the improvements.
- **The client builds the sparkline from server rates** (what TECH_DEBT suggested). Every client
  would rebuild that history, and it would still reset on reload.
- **Fetch the trend on hover.** The tooltip would visibly lag.
- **A separate 1 Hz stats WebSocket stream.** The 2 s tick is enough for display; a second
  channel adds reconnection and ordering cases.
- **Payload vs protocol overhead** (DHT, tracker and handshake traffic, as qBittorrent shows).
  Real, but few users act on it, and it means metering in every protocol module. Not done.
- **Persist all history.** Per-torrent and minute history would mean a write every minute per
  torrent for figures that matter mostly in the moment. Only the global hourly ring is kept.
