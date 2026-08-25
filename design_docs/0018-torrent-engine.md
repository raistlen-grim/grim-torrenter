# 0018 — TorrentEngine

**Status:** Accepted

## Decision

`TorrentEngine` is the facade `grimtorrenter-app` will talk to: it owns
the things that must be shared/consistent across every torrent in one
running client instance, and manages the set of active `TorrentSession`s.

**Ownership**: `TorrentEngine` owns a single `PeerId` (generated once via
`PeerId.generate()`) and the base download directory, both shared across
every `TorrentSession` it creates - matching how a real BitTorrent client
presents one consistent peer identity to the whole swarm, not a different
one per torrent.

**`PeerId.generate()`** uses the Azureus-style convention: an 8-byte
client-identifying prefix (`-GT0100-`) followed by 12 random bytes. The
only actual protocol requirement is 20 bytes total - the prefix is a
convention (lets trackers/other clients identify GrimTorrenter for
debugging/statistics), not something enforced by the spec. Confirmed with
the user rather than assumed, since a UUID-backed alternative (16 bytes +
padding, no client identification) was a real option.

**Per-torrent download directories are namespaced by info hash**
(`baseDownloadDirectory/<infoHash-hex>/...`), not by the torrent's
declared name - two different torrents can declare files with the same
name (e.g. both name a single file `movie.mkv`), and without this they'd
collide if added to the same engine. Covered by
`differentTorrentsWithSameDeclaredFileNameDoNotCollideOnDisk`.

**Tracker URL selection** (`selectTrackerUrl`, package-private for direct
unit testing without a real HTTP server): prefers `announce`, then scans
`announce-list` (flattened, tier order preserved) for the first `http://`
or `https://` URL. A torrent with no usable HTTP(S) tracker anywhere
fails at `addTorrent` time with a clear `TorrentEngineException`, rather
than constructing a session that would only fail confusingly later inside
`start()`. UDP trackers and DHT-only torrents aren't supported yet (Phase
1, see [[0009-phased-scope]]), but a torrent that lists both an UDP
tracker and an HTTP fallback tier now works correctly rather than being
rejected just because its first-listed tracker isn't HTTP.

**`addTorrent` is idempotent** for the same info hash - adding the same
torrent twice returns the existing session rather than creating a second
one. Implemented via `ConcurrentHashMap.computeIfAbsent`, not a
`get`-then-`put` pair, to avoid a check-then-act race where two
concurrent `addTorrent` calls for the same info hash could each create
their own `TorrentSession` (the second `put` would silently orphan the
first one's storage/connections, still running but no longer reachable
through the engine).

**"Pause" and "resume" need no new logic in `TorrentSession`** - they
delegate directly to the existing `stop()`/`start()`. This works correctly
because `TorrentSession` never resets its `PieceManager`'s in-memory state
on `stop()`; calling `start()` again on the *same* session object
re-announces and reconnects without re-downloading already-completed
pieces. This only holds for as long as the `TorrentEngine` instance (i.e.
the JVM process) stays alive - a full process restart still starts fully
fresh per [[0017-torrent-session]]'s startup decision, since there's no
persisted resume state yet.

**`removeTorrent` stops and unregisters but does not delete downloaded
files** - simplest interpretation for now; a "delete files too" option
can be added to the API later without disruption if wanted.

## Alternatives considered

- **UUID-backed peer ID** - considered and explicitly rejected (confirmed
  with the user) in favor of keeping the client-identifying prefix.
- **`get`-then-`put` for idempotent add** - rejected due to the
  concurrent-duplicate-add race described above.
- **Reject a torrent outright if its *first* tracker isn't HTTP(S)** -
  rejected in favor of scanning all tiers, since UDP-primary/HTTP-fallback
  torrents are common in the wild and are usable under Phase 1's actual
  constraint (needs *an* HTTP tracker, not a first-listed one).
