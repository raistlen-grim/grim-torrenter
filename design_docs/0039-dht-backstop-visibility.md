# 0039 — DHT backstop visibility in the UI

**Status:** Accepted

## Decision

[[0036-dht-backstop-for-tracker-bearing-torrents]] deliberately left one question open:
`TorrentView.usesDht` only ever means `isTrackerless()` (has zero trackers at all), so
once a tracker-bearing torrent could also fall back to DHT when its trackers are
unreachable, the detail header's mutually-exclusive "DHT" tag / "N tracker(s)" text had no
way to reflect that. **Confirmed with the user: worth building.**

**Two design forks resolved with the user rather than assumed:**

- **The signal means "currently active," not "ever used this session."** A new
  `TorrentSession.isDhtBackstopActive()` reflects whether the *most recent* tracker
  announce (`start()` or `reannounce()`) actually fell back to DHT rather than succeeding
  via the tracker - it reverts to `false` the moment a later reannounce succeeds normally.
  A sticky "this session needed DHT help at some point" flag was the alternative, rejected
  as a historical/diagnostic marker rather than the live "is this happening right now"
  status the header actually wants.
- **Show both, not one replacing the other.** For a tracker-bearing torrent with an active
  backstop, the header shows the existing "N tracker(s)" text *and* a separate "DHT
  backstop" indicator alongside it, rather than folding both facts into one combined
  string - both are independently true (it has trackers, and it's also currently using
  DHT), so both get their own display element. The pure-DHT "DHT" tag for a genuinely
  trackerless torrent is unchanged.

### `TorrentSession.dhtBackstopActive` (new field + `isDhtBackstopActive()`)

A `volatile boolean`, explicitly set (not left to infer) at every point `start()`/
`reannounce()` already know the outcome of a tracker-vs-DHT attempt:
- `start()`'s normal success path, and `reannounce()`'s success path → `false`.
- `startViaDhtBackstop`'s two `ERROR` outcomes (DHT unavailable, or DHT lookup also
  failed) → `false` - a torrent that isn't running at all isn't "actively" doing anything.
- `startViaDhtBackstop`'s success path (DHT lookup didn't throw, even if it found zero
  peers - same "lookup completed" bar the backstop's own `ERROR`-vs-`DOWNLOADING` decision
  already uses) → `true`, right before `enterDownloading`.
- `reannounceViaDhtBackstop`'s async virtual thread, only once `findPeers` itself returns
  without throwing → `true`. Deliberately left unset (not forced to `false`) if that DHT
  lookup *also* throws - a rare double-failure a single field flip either way wouldn't
  meaningfully improve, not worth the extra code for.

Never set at all for a trackerless torrent - `NoOpTrackerClient.announce()` never throws,
so neither `startViaDhtBackstop` nor `reannounceViaDhtBackstop` is ever reached for one;
`isTrackerless()` already fully covers that case.

### Wire/UI

`TorrentView` gained `dhtBackstopActive` (from `session.isDhtBackstopActive()`), riding the
same always-broadcast snapshot `usesDht`/`trackerCount` already do
([[0031-torrent-detail-endpoints]]/[[0035-spacing-table-density-and-empty-state]]'s
existing reasoning for why these ride `TorrentView` rather than a dedicated endpoint still
applies here). The detail header adds a second `app-status-indicator` (`pi-share-alt`,
tone `active`, labeled "DHT backstop" - distinct wording from the trackerless case's plain
"DHT" label, so the two are never visually confused) next to the tracker-count text, shown
only when `usesDht` is false and `dhtBackstopActive` is true. Not added to the list view,
consistent with `usesDht`/`trackerCount` never having been added there either - this is
detail-header-only information.

## Testing

`TorrentSessionTest`: `fallsBackToDhtWhenAllTrackersFailOnStart` gained an
`isDhtBackstopActive()` assertion once the backstop succeeds; `fallsBackToDhtWhenReannounceFails`
extended to also simulate the tracker recovering on a later `reannounce()` and confirm the
flag reverts to `false` - proving it tracks current reality, not a sticky session-lifetime
marker. `TorrentResourceTest`'s existing `usesDht`/`trackerCount` assertions gained a
`dhtBackstopActive: false` check for the ordinary (no backstop) case.

## Alternatives considered

- **A sticky "ever used this session" flag** - rejected; see the signal-meaning fork above.
- **Replace "N tracker(s)" with a combined string** ("N tracker(s) — DHT backstop active")
  instead of a separate element - rejected; see the display fork above.
- **A dedicated `/summary`-style endpoint for this** - not reconsidered; the reasoning in
  [[0035-spacing-table-density-and-empty-state]] for why `usesDht`/`trackerCount` ride
  `TorrentView` directly applies identically here, and revisiting it wasn't in scope.
