# 0070 — Pending magnets as a first-class, restart-durable, REST-visible state

**Status:** Accepted

## Decision

Closes a real gap raised by the user (2026-09-11): a magnet's metadata-fetch is currently
entirely transient - nothing on disk, nothing in any REST response, says it's happening. A page
refresh mid-fetch loses the client's only record of it (a local Angular signal); an engine
restart mid-fetch loses it even harder, with no trace anywhere. The torrent then either silently
appears later (a success no one was told was pending) or never appears at all (a failure no one
saw coming) - confirmed against how other clients behave: qBittorrent/libtorrent persists resume
data for a magnet-only torrent immediately, before any metadata exists, specifically so it
survives a restart, shown as its own real, removable "Downloading metadata" row - not an
ephemeral client-side thing.

**Broader principle driving the shape of this fix, stated directly by the user**: keep the
frontend as dumb as possible - a new client built against the REST API alone should have to
recreate as little UI-side logic as possible. This ruled out a parallel "pending magnet"
concept live only in the frontend (what a narrower page-refresh-only fix would have looked
like) in favor of modeling a pending magnet as *the same kind of thing* a real torrent already
is, wherever that's achievable without a deep `TorrentSession` rewrite.

## `FETCHING_METADATA` - a state, not a separate entity

A pending magnet is **not** a `TorrentSession` - that class is built around having real
`TorrentMetadata` (piece count, file layout) from construction onward, and making metadata
optional throughout it would be an invasive, high-risk rewrite for what's fundamentally a
different, much smaller piece of state (an info hash, an optional display name, a tracker
list). Instead: a new, separate, lightweight `TorrentEngine`-owned registry
(`Map<InfoHash, PendingMagnet>`), and a `TorrentView.fromPendingMagnet(...)` factory that
produces the *same DTO shape* every other torrent already returns - `state = "FETCHING_METADATA"`
(a literal string, not a new `TorrentState` enum value - see below), `name` from the magnet's
own display name (or its info hash hex if none was given), every byte/piece/rate field zeroed.

**`TorrentState` (the backend enum, `DOWNLOADING`/`VERIFYING`/`SEEDING`/`STOPPED`/`ERROR`) is
deliberately untouched.** Every one of its values names a real `TorrentSession` state-machine
transition; a pending magnet has no session to transition. `TorrentView.state` was already a
plain `String` on the wire (decoupled from the Java enum's own identity, same reasoning
[[0031-torrent-detail-endpoints]] already used for `PieceState`) - `"FETCHING_METADATA"` rides
that same decoupling rather than needing a new enum member with no session behind it.

Because this rides the exact same `TorrentView`/`Torrent` shape and the exact same list/snapshot
plumbing, almost everything downstream - the initial `GET /api/torrents` list load, the periodic
WebSocket snapshot, `DELETE /api/torrents/{infoHash}` - needs little to no bespoke handling for
"is this actually pending." A new REST client reading `GET /api/torrents` sees a normal-shaped
row it can already render and delete; it doesn't need to know a second protocol exists.

## Persistence and restore

A new marker, `.grimtorrenter-magnet-pending`, written into `configTorrentDirectory(infoHash)`
the moment `addMagnet()` is called - before the background fetch thread even starts, same
"declare before acting" principle [[0069-persist-before-starting]] just established for a
regular add. Plain `key=value` lines (this codebase's standard marker shape): `displayName=...`
plus one `tracker=...` line per announce URL - enough to reconstruct the `MagnetLink` and
re-drive the exact same fetch path.

**The marker's lifetime is one attempt, not "until the user gives up."** It's deleted the
moment the attempt concludes, by whichever path concludes it - success (`addFetchedTorrent()`,
right where the real torrent-file marker gets written instead), failure
(`recordMagnetAddFailed()`), or explicit user removal. A crash/restart *during* an attempt
leaves the marker in place, and `restore()` (which now also scans for a magnet-pending marker
alongside its existing torrent-file-marker scan) re-drives the fetch with a **fresh time
budget** - the same "retry construction from scratch next restart" precedent every other
`restore()`-recovered failure in this engine already follows, not an attempt to persist and
resume elapsed time. A fetch that already ran to completion and failed within one process
lifetime stays failed - this doesn't turn the engine's existing bounded-time-budget failure
model ([[0028-magnet-links-and-dht]]) into an unbounded retry-forever one; it only protects an
attempt that was interrupted before it got to conclude on its own terms.

## Cancellation

`TorrentEngine.removeTorrent(infoHash, deleteData)` checks the pending-magnet registry first
(falling through to today's real-session removal if not found there). Removing a pending entry:
deletes the marker, removes the registry entry, and records a `REMOVED` library event
(`torrentName = null`, the same "no real torrent to name" convention `MAGNET_ADD_FAILED`
already uses) - no new `EventType` needed. `deleteData` is meaningless here (nothing was ever
downloaded - no `TorrentStorage` exists yet) and is ignored for this branch.

The in-flight fetch loop itself isn't interrupted mid-network-call - that would mean threading
cancellation into DHT/tracker I/O this engine doesn't otherwise support interrupting. Instead,
`fetchMagnetMetadataViaTrackerThenAdd`/`...ViaDhtThenAdd`'s own retry loop checks "is this info
hash still in the pending registry" once per round (the same cadence it already checks its
overall deadline at) and stops early if not - bounded, not instant, cancellation: worst case,
one round's worth of in-flight connection attempts (already bounded by their own per-candidate
connect timeout) finishes before the loop actually exits. Acceptable for a user-initiated
removal, the same way a `STOPPED` torrent's in-flight peer connections aren't force-killed
mid-handshake either.

## REST and WebSocket surface

- `GET /api/torrents` (`TorrentResource.list()`): merges `sessions` and `pendingMagnets` into
  one list.
- `GET /api/torrents/{infoHash}`: checks pending magnets too, not just real sessions.
- `POST /api/torrents/magnet`: **now returns the pending entry's `TorrentView` synchronously**,
  instead of an empty 200. Writing the marker and registering the pending entry is itself fast
  (local disk I/O, no network wait) - the *fetch* is what's slow, and that still runs on its own
  background thread exactly as today. This mirrors the file-upload endpoint's own shape (`add()`
  already returns a real `TorrentView` synchronously) and is what actually lets the frontend
  drop its bespoke magnet-pending placeholder logic (see below) - the caller gets a real,
  insertable resource back immediately, the same as any other add.
- `DELETE /api/torrents/{infoHash}`: unchanged signature: - `TorrentEngine.removeTorrent()`'s own
  new pending-first check (above) is all that's needed.
- `TorrentSnapshotScheduler`'s periodic broadcast, and any other consumer of
  `TorrentEngine.listTorrents()`-shaped data for the always-on push, includes pending magnets
  too - so a *different* already-open client sees a magnet another client just added (or one
  reappearing after a restart) via the exact same push mechanism as any other torrent, no
  separate channel.

## Frontend simplification

Because `addMagnet()` now returns a real `Torrent` synchronously, `submitMagnet()`/
`submitMultipleMagnets()` collapse to the same shape `uploadFile()` already has: a brief
`{id, fileName}` pending-row flash while the POST is in flight (existing, unchanged mechanism -
this is about masking normal request latency, not metadata-fetch latency), `events.upsert(response)`
on success, an error toast on failure. **Removed entirely**: `PendingUpload.infoHash`,
`pendingInfoHashEffect` (the whole "watch for a matching real torrent or a `MAGNET_ADD_FAILED`
event to resolve this placeholder" mechanism), and `magnetDisplayName()`'s use for placeholder
naming - none of it is needed once the server hands back a real, already-correctly-named,
already-correctly-stated resource up front instead of an acknowledgement to wait on.

`frontend`'s `TorrentState` union gains `'FETCHING_METADATA'` alongside the five real values,
with its own entry in `status-display.ts`'s `TORRENT_STATE_DISPLAY` map (TypeScript's own
exhaustiveness check on that `Record` is what forces this, not a convention that could be missed)
- own icon/label, `tone: 'dim'`. `TorrentRow`'s Pause/Resume toggle and "Seeding limits…" menu
item are both hidden/disabled for this state (there's no session to pause/resume or set a
seeding-limit override on) the same way Pause/Resume already is for `VERIFYING`; Remove is
unaffected and simply already works, since `FETCHING_METADATA` was never gated by the existing
`isVerifying()`-based disable in the first place. `navigateToDetail()` also no-ops for this
state - the detail panel's tabs (Files/Peers/Trackers/Pieces) all poll session-scoped endpoints
that don't exist yet for a pending magnet, so opening the panel would just show empty/erroring
tabs for no benefit.

## Testing

- `TorrentEngineTest` - `addMagnet()` immediately makes the info hash visible via
  `listTorrents()`-equivalent in `FETCHING_METADATA`; resolving replaces it with the real
  torrent; a failed fetch removes it and records `MAGNET_ADD_FAILED`; `removeTorrent()` on a
  still-pending info hash removes the marker and stops it from reappearing, and records
  `REMOVED`; a fresh engine's `restore()` picks a still-pending marker (with no matching
  torrent-file marker) back up and re-attempts the fetch.
- `TorrentResourceTest` - the merged `list()`/`get()` shape; `POST .../magnet` returns a
  `FETCHING_METADATA` `TorrentView` synchronously.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Bounded growth**: one small marker file per pending magnet, same footprint class as every
  other per-torrent marker; the in-memory registry entry is removed on every conclusion path
  (success, failure, explicit removal) - nothing accumulates across a long-running process.
- **No hostile-peer angle beyond what already exists**: the registry and marker are populated
  from this engine's own accepted request (a magnet URI a local user submitted), not from
  anything a peer or tracker sends.
- **Concurrency**: the pending registry is a `ConcurrentHashMap`, same idiom as `sessions`/
  `directories`; the cancellation check inside the fetch loop is a single map-membership read,
  no new locking.
- **Restart behavior under repeated failure**: a magnet that fails its fetch budget every single
  restart (e.g., a genuinely dead swarm) will re-attempt - and re-fail - once per restart
  indefinitely, since nothing here changes the underlying bounded-per-attempt model into a
  backed-off one. Accepted: restarts are infrequent, deliberate events for this app, not a tight
  loop, and the existing per-attempt budget already bounds the cost of each one.

## Alternatives considered

- **In-memory-only registry, exposed via a dedicated new endpoint the frontend polls once on
  load** (the originally-scoped, narrower "just fix the page refresh" version) - rejected (user
  decision) once the restart-durability and REST-modeling goals were raised; would have solved
  refresh but not restart, and kept a second, parallel, frontend-only concept alive instead of
  folding pending-ness into the same shape real torrents already have.
- **A fully generalized, metadata-optional `TorrentSession`** - rejected; touches the class this
  entire engine is built around for a comparatively small amount of new state, for no benefit
  over a separate lightweight registry that happens to render through the same DTO.
- **A new `MAGNET_PENDING`/similar `EventType` for the accept-time moment** - rejected; the
  existing `ADDED` (on resolution)/`MAGNET_ADD_FAILED` (on failure) events already cover the
  two outcomes an activity log needs to show; the moment-of-submission itself is already
  visible immediately via the synchronous REST response and the resulting list/snapshot entry,
  with nothing an events-log entry would add.
