# 0063 — Rate trend tooltip (Down cell sparkline)

**Status:** Accepted

## Decision

Replaced the torrent list's Down-cell rolling-average tooltip (raw 5s/15s/60s numbers via
`FormatRateWindowsPipe`) with a 60-second trend sparkline, per
`style/torrent_list/ADDENDUM_03_rate_tooltip.md`: one shape reads as "climbing / steady /
stalling" at a glance, where three numbers made the viewer do the trend math themselves.

Scope, confirmed with the user up front (the addendum only specs the torrent list's Down
cell):

- **Torrent list Down cell** and **Peers tab Down cell** (per-peer) both get the new
  sparkline tooltip — the addendum's "Down carries a trend worth surfacing" reasoning
  applies identically to a per-peer rate, even though the doc itself doesn't mention the
  Peers tab.
- **Torrent list Up cell** and **Peers tab Up cell** lose the old tooltip entirely — plain
  rate text, no hover detail. The addendum never specs a trend for Up ("only Down carries a
  trend worth surfacing for this app's core use case"), and the user explicitly asked for
  the old one gone there rather than left in place.
- `FormatRateWindowsPipe` is deleted — it had no other callers once both cells above stopped
  using it.

### New shared component: `shared/rate-trend/`

`<app-rate-trend [rateBytesPerSec] [trend]>` — a self-contained hover trigger + tooltip,
used identically by `TorrentRow`'s Down cell and `PeersTab`'s per-peer Down cell (previously
each hand-rolled its own `pTooltip` binding). Renders the plain rate (or an idle em dash,
unchanged behavior) and, only while `rateBytesPerSec > 0`, an absolutely-positioned tooltip
(rate repeated + sparkline + "Last 60s" caption, per the addendum's visual spec) revealed via
a bare `:host(:hover)` CSS rule — no JS show/hide delay logic: a plain `:hover` toggle already
gives "appears on hover, leaves immediately on mouseleave" for free, and avoids adding a
fourth transition beyond README.md's Motion section's deliberate "exactly three" budget (this
one has none at all, not even a fade).

### `RateTracker`: `byWindow` breakdown replaced with `trend` samples

`RateTracker` previously took a `RateWindows` map (`{'5s': 5000, '15s': 15000, '60s':
60000}`) and returned every window's rate in `RateSnapshot.byWindow`, used only to render the
old tooltip's text. With that gone, the tracker now takes a single `primaryWindowMs` (the
one rate actually displayed inline) and returns `RateSnapshot.trend: number[]` instead —
instantaneous rate between each consecutive pair of readings still in the key's history,
oldest first.

This reuses the same bounded reading history the tracker already kept (previously trimmed to
`max(...windows)` = 60s; now a fixed `TREND_WINDOW_MS = 60_000` floor regardless of
`primaryWindowMs`) rather than adding a second, separately-timed sample buffer. The addendum
suggests "sample every 5s, cap at 12 samples" as a concrete illustration, but this app's
actual snapshot cadence is 2s for torrents (`TorrentEventsService`) and 3s for peers
(`PeersTab`'s poll) — sampling on a fixed independent timer would need its own interval/
cleanup wired into two different components for no visible difference, since the sparkline
normalizes whatever number of samples it's given across the same 110×26 box either way.
Deviation flagged and implemented this way rather than assumed silently.

`PRIMARY_RATE_WINDOW_MS = 15_000` (`torrent-events.service.ts`) replaces the old
`RATE_WINDOWS`/`PRIMARY_RATE_WINDOW` pair as the one shared constant both `TorrentEventsService`
and `PeersTab` construct their trackers with.

### Token gap: `--shadow-md`

The addendum lists `--shadow-md` as "already defined; no new tokens," but only `ds/
industry.css` (the design handoff bundle) defined it — `styles.scss` had never ported it
(only the guide's color/font/motion tokens had been, piecemeal, by earlier passes). Added
verbatim from the guide's own value rather than treated as a new decision.

## Stability

No unbounded growth: `RateTracker`'s history stays capped at `max(primaryWindowMs,
TREND_WINDOW_MS)` = 60s of readings per key regardless of how often `record()` is called, same
bound as before this change; `trend` is derived from that same bounded array each call, never
accumulated separately. Per-peer trackers are still torn down via `delete()` on disconnect
(`PeersTab.forgetDisconnectedPeers`), unchanged by this decision. No new locking/concurrency
surface — `RateTracker` remains a plain synchronous class with no shared mutable state across
instances, and this change doesn't touch that. No hostile-peer angle: `trend` values are
derived from the same clamped-to-zero byte counters the primary rate always was.
