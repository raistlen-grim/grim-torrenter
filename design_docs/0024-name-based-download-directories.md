# 0024 — Name-based download directories

**Status:** Accepted

## Decision

**Raised by the user during real-world testing**: download directories
were named by info hash (`downloads/<40-char-hex>/...`, per
[[0018-torrent-engine]]'s original collision-avoidance reasoning), which
is completely unbrowsable - a user has no way to recognize which opaque
hex folder corresponds to which torrent. Real clients (qBittorrent,
Transmission) use the torrent's own declared name instead.

**Switched to name-based directories** (`downloads/<sanitized-name>/...`),
while still solving the two problems info-hash naming solved incidentally:

1. **Two different torrents sharing a declared name** (e.g. both named
   `movie.mkv`) must not collide/overwrite each other.
2. **The same torrent being re-added** (e.g. after a process restart -
   there's no persisted resume state yet, see [[0009-phased-scope]], so
   every restart re-adds via the same `addTorrent` path) must reuse its
   existing directory, not get treated as a name collision with itself
   and shuffled into a new `-2` directory every time.

**Solved via a marker file** (`.grimtorrenter-infohash`, containing the
plain hex info hash) written into each torrent's directory the first time
it's claimed. `resolveDownloadDirectory` checks candidates in order
(`name`, `name-2`, `name-3`, ...): a candidate with a marker matching the
torrent's own info hash is reused; a candidate that doesn't exist yet is
freshly claimed (directory created, marker written); a candidate that
exists with a *different* (or missing) marker is treated as occupied by
something else, and the next candidate is tried. A directory that exists
with no marker at all (e.g. one of the old info-hash-named directories
from before this fix, or unrelated content) is treated as occupied rather
than risking a write into it - existing pre-fix downloads are left alone,
not migrated.

**`resolveDownloadDirectory` is synchronized** on a dedicated lock -
without it, two different torrents with the same name being added
concurrently could both observe the candidate directory as free and race
to claim it. `TorrentSession.create`'s own I/O (which can be slower) still
happens outside this lock, since the directory has already been reserved
via the marker file by the time it runs.

**`sanitizeDirectoryName`** strips characters unsafe on any common
filesystem (path separators, plus Windows' reserved set even though the
container likely runs Linux - downloads may end up on a mounted/shared
volume later), falling back to the literal name `torrent` for an empty,
`.`, or `..` result. Package-private for direct unit testing, same
rationale as `selectTrackerTiers`.

**Known minor cosmetic quirk, not fixed here**: single-file torrents still
get their own per-torrent directory (`downloads/movie.mkv/movie.mkv`)
rather than landing directly in `downloads/movie.mkv` the way some real
clients place single-file downloads. Fixing this would mean handling
single- and multi-file torrents asymmetrically in directory resolution
(disambiguating a *file* name directly in the shared root for single-file
torrents, versus a *folder* name for multi-file ones) - real added
complexity for a purely cosmetic difference. Not addressed unless it
becomes an actual problem.

## Alternatives considered

- **Keep info-hash directories** - rejected; confirmed with the user as a
  genuine usability problem, not just a style preference.
- **No collision/re-add handling, just use the name directly** - rejected;
  would silently merge or overwrite unrelated torrents that happen to
  share a name, and would treat every restart as a fresh collision.
- **Migrate existing info-hash-named directories to the new scheme** - not
  built; the project is still in active development/testing, so orphaned
  old-style directories are an acceptable, low-cost outcome rather than
  something worth a migration path right now.
