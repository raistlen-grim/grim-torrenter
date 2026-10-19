# 0102 - Server-side transfer rates, ETA with a reason, rate caps shown, and transfer history

Status: Proposed (2026-10-18). Wanted in the beta, all three stages. Replaces the client-side rate
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

### 1.1 `TransferMeter`: per-second buckets at the byte path

A new engine class, `ratelimit/TransferMeter` (or `stats/`), one per direction per counted thing:
- **Fed where bytes move.** `PeerConnection` already does `downloadedBytes.addAndGet(...)` when a
  block arrives and `uploadedBytes.addAndGet(...)` when one is sent. Those two lines also add to
  the connection's meters. No new counting site.
- **Buckets.** The current second's bytes accumulate in an `AtomicLong`. A 1 s tick rolls it into
  a 60-slot ring. Writers (connection threads) only ever `addAndGet`; the tick is the only thing
  that touches the ring, and after rolling it publishes an immutable `TransferSample` record
  (the ring as a `long[]` copy plus the derived figures below) through a `volatile` field. Readers
  (snapshot, REST) read that field and never lock. No `synchronized` anywhere on the path
  ([[0007-concurrency-model]]).
- **Three levels from one mechanism.** Each peer connection has a meter. A torrent's meter is fed
  by the tick summing its live connections' just-rolled seconds - not by a second count on the
  block path - so a torrent always equals the sum of its peers. The global meter is the sum of
  the torrents'. The header total equals the sum of the rows by construction.
- **Disconnects lose nothing.** The torrent's bucket for a second includes a connection that
  closed during that second: the connection's final partial second is added on close, the same
  accumulator-on-close pattern `bytesUploaded()` already uses ([[0025-seeding-and-upload-logic]]).

The existing `PeerConnection.downloadRateBytesPerSec` (a 1 s average used to size the request
pipeline, [[0080-cross-peer-request-coordination]]) stays as it is. It is a control input with its own tuning,
not a display figure.

### 1.2 Two averages for two questions

Derived on each tick and stored in the published `TransferSample`:
- **`rateBytesPerSec`** - "how fast right now". An exponential moving average with a ~5 s time
  constant over the per-second buckets. A stalled torrent reads 0 within a few seconds.
- **`etaRateBytesPerSec`** - "how fast on average", used for ETA only. The mean over the last
  60 s, or over this run if it's younger than that. Steady across choke rounds.
- **`trend`** - the 60 per-second values, oldest first, for the sparkline.

A paused or stopped torrent's meters are cleared, so it reads 0 at once and a resume starts
fresh.

### 1.3 The sampling tick

One `GuardedTask` on `TorrentEngine`'s existing `maintenanceScheduler`, every 1 s: for every
running session, roll each connection's meter, then the torrent's, then the global one. No new
thread. It runs whether or not any browser is connected, so a REST-only client gets rates too
(the WebSocket snapshot is skipped when nobody listens, [[0094-session-revocation-error-shape-and-api-consistency]]).

Cost: one pass over sessions and their connections per second - the same walk the 2 s snapshot
already does for `bytesReceived()`, with no allocation beyond the published records.

### 1.4 ETA

`TorrentSession.eta()` returns an `Eta(Long seconds, EtaStatus status, int unavailablePieces)`:
`seconds = ceil(bytesRemaining() / etaRateBytesPerSec)`. `bytesRemaining()` counts wanted bytes
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

| Status | When | Row reads |
|---|---|---|
| `COMPLETE` | nothing wanted remains | — |
| `NOT_RUNNING` | paused, stopped, error, verifying, fetching metadata | — |
| `NO_PEERS` | running, no connected peers | No peers |
| `UNAVAILABLE` | some wanted, incomplete pieces are held by no connected peer | 12 pieces unavailable |
| `CHOKED` | peers have what we need, but every one is choking us | Waiting for peers |
| `STALLED` | none of the above, and `etaRateBytesPerSec` is 0 | Stalled |
| `ESTIMATE` | otherwise | 4h 12m |

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
`RateLimiter.acquire()` returns whether it had to wait (today it returns `void`), and
`PeerConnection` stamps its torrent's meter with the time. Capped means that stamp is under 3 s
old. No new lock: a `volatile long` per direction per torrent.

`GET /api/stats/transfer` and the `transfer` message carry the same three fields for the global
pair.

**UI:** the rate cell shows a small cap marker when capped, with a tooltip naming the source
("Capped by the global limit, 1.0 MB/s", "Capped by the off-hours schedule", "Capped by this
torrent's limit"). The header's totals show the same marker. Exact visual form to confirm against
the style guide before building - the guide doesn't cover it.

## Stage 3 - transfer history

### 3.1 What's kept

Global, both directions:
- 60 × 1 s (the meter's own ring);
- 120 × 1 min (2 hours);
- 168 × 1 h (7 days).

Per torrent, both directions:
- 60 × 1 s (the meter's ring);
- 60 × 1 min (1 hour).

Coarser rings are fed by the same 1 s tick: every 60th tick closes a minute bucket from the last
60 seconds, every 60th minute closes an hour bucket.

Memory is fixed: about 350 longs global, about 240 per torrent (≈2 KB). A 1,000-torrent library
is about 2 MB.

### 3.2 Surviving restarts

The **global hourly ring is persisted** to a small JSON file in the config directory on the
existing lifetime-stats flush ([[0064-persistent-lifetime-stats]]), with the same atomic
write. "Last night" survives an upgrade or container restart, which is when it would otherwise
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
  two lines, with the active rate limits drawn as horizontal reference lines so a schedule
  window is visible as a step.
- **Per-torrent graph:** in the details panel, with a 1 min / 1 h switch.
- Drawn as inline SVG like the existing `RateTrend` sparkline; no chart library.

**To decide before building (style guide):** where each lives. Candidates for the global graph
are a new group on the Health page, its own page in the sidebar, or a popover from the footer's
totals. For the per-torrent graph, a fifth tab beside Files/Peers/Trackers/Pieces, or a strip in
the panel's fact grid. Neither placement is covered by the guide.

## Order of work

1. **Engine:** `TransferMeter` and its tick, ETA and its status, `RateLimiter.acquire()`
   reporting waits, the history rings and their persistence. Unit tests with an injected clock:
   bucket rollover, EMA decay to 0, 60 s mean on a young run, a disconnect mid-second, status
   precedence, the capped window, ring roll-ups, gap handling, a corrupt history file.
2. **App:** the view fields, `GET /api/stats/transfer`, the `transfer` message, the history
   endpoints, and their tests.
3. **Frontend, stage 1:** delete the client-side rate code; read the new fields.
4. **Frontend, stages 2-3:** ETA reasons, cap markers, graphs - after the placement questions
   above are settled.

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
- **No new threads, no locks.** One more `GuardedTask` on the existing maintenance scheduler; a
  throw in it is logged and the next tick runs. The block path gains one `addAndGet` per block per
  direction, beside the one already there. Readers read a `volatile` immutable record.
- **The tick can't fall behind dangerously.** `scheduleWithFixedDelay`, so a slow tick delays the
  next rather than piling up. A late tick rolls the bytes of a longer interval into one bucket and
  divides by the real elapsed time, so a late tick reads as the right average, not a spike.
- **Cleanup on every exit path.** A closing connection adds its partial second to the torrent
  and drops its meter in the same close path that already folds its byte counts into the
  torrent's accumulators. Pause and remove clear the torrent's meters.
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
