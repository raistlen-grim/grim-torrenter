# 0022 — Multi-tracker fallback

**Status:** Accepted, confirmed working against a real torrent

## Decision

**Found via manual testing, not code review**: a real-world torrent
uploaded through the UI immediately went to `ERROR`. Tracing it down
(see [[0021-engine-logging-and-error-visibility]] for the logging that
made this diagnosable at all, and [[0013-http-tracker-client]] for the
User-Agent red herring along the way) led to the real cause:
`TorrentEngine` picked exactly one tracker URL from the torrent file and
gave up entirely if that one tracker failed. Real-world torrents
routinely list dozens of trackers, many of them dead or blocking
requests — the specific torrent tested had ~40 trackers, only 2 of them
HTTP(S) (the rest UDP, unsupported until Phase 2), and *both* HTTP
trackers turned out to be non-functional. A desktop client (qBittorrent)
completed the same torrent successfully via a UDP tracker
(`udp://exodus.desync.com:6969/announce`) — proving the torrent itself
was fine and the gap was purely "no fallback when the one tracker we
picked doesn't work."

**`MultiTrackerClient`** (`tracker` package) implements BEP 12 tier
fallback: tiers are tried in order; within a tier, trackers are tried in
order until one succeeds; a tier only counts as failed once *every*
tracker in it has failed. `TorrentEngine.selectTrackerTiers` builds the
tier structure from the torrent's metadata and wraps it in a
`MultiTrackerClient`, replacing the old `selectTrackerUrl` (single URL).

**Corrected an inaccuracy in the original tracker-selection logic along
the way**: the old `selectTrackerUrl` preferred the classic `announce`
field over `announce-list`. That's backwards per BEP 12 — a
spec-compliant torrent file already includes `announce` redundantly as
part of `announce-list` (for old clients that don't understand
`announce-list`), so a modern client should prefer `announce-list` when
present and only fall back to bare `announce` when `announce-list` is
absent entirely. `selectTrackerTiers` fixes this.

**Deliberately not implemented** (see `MultiTrackerClient`'s Javadoc):
shuffling trackers within a tier, and promoting a working tracker to the
front of its tier for subsequent announces. Both are real BEP 12
refinements, but they're swarm-politeness/latency optimizations, not
needed to fix the actual problem (a client that can't tolerate any dead
tracker at all). Can be added later without changing the interface.

## Testing

`MultiTrackerClientTest` covers: first tracker in a tier succeeding,
falling back within a tier, falling back across tiers, all trackers
failing (propagates the last failure), and rejecting an all-empty tier
list. `TorrentEngineTest`'s tracker-selection tests were rewritten against
`selectTrackerTiers` instead of the removed `selectTrackerUrl`.

Confirmed working end-to-end against a real Ubuntu (Linux ISO) torrent
file after this fix - the earlier real-world torrent's failure was
correctly diagnosed as "needs Phase 2's UDP tracker support," not a bug
in this fallback logic.

## Alternatives considered

- **Track dead trackers and skip them on subsequent re-announces** - not
  built; `MultiTrackerClient` re-tries every tracker in tier order on
  every single announce call. For Phase 1's re-announce cadence (every
  30+ minutes per [[0017-torrent-session]]), the wasted time re-trying a
  known-dead tracker before falling through is negligible.
