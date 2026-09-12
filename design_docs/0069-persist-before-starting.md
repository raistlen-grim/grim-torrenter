# 0069 — Persist a torrent's existence before starting it, not after

**Status:** Accepted

## Decision

Raised by the user (2026-09-11) while looking at the SPA-fallback bug ([[0068-spa-fallback-routing]]):
`TorrentEngine.addTorrent()` used to call `created.start()` - a synchronous initial tracker
announce over real network I/O - *before* writing the torrent-file/state/added-at markers that
[[0026-resume-state-persistence]] made `restore()`'s entire discovery mechanism depend on. A
crash (or a process kill) in that window left a torrent genuinely running - announced to its
tracker, possibly already connecting to peers or writing real piece data to disk via the
already-written download-path marker ([[0065-config-side-per-torrent-storage]]) - with *nothing*
on disk to say it had ever been added. The next `restore()` would never find it: not a stale
entry, not an orphaned-but-visible torrent, just gone, while its (possibly partially-downloaded)
files sat unexplained in the download folder.

**Fixed by writing every config marker before constructing or starting the session at all**, not
just before it finishes. This isn't only about crashes: confirmed with the user as the intended
behavior generally, the same way a tracker/DHT failure already leaves a torrent visible in
`ERROR` rather than silently un-adding it ([[0036-dht-backstop-for-tracker-bearing-torrents]]) -
"added, but not currently able to start" is a real, valid state a torrent can be in, not a
failure to reach a state that should have hidden it. If `TorrentSession.create()`/`restoreAsync()`
itself throws after this reordering, the markers are already on disk and the next `restore()`
will retry construction - self-healing, the same spirit as every other marker-driven recovery
path in this engine, rather than a caller-visible failure meaning the torrent was never
persisted at all.

Ordering within the three markers doesn't matter to each other - they're independent facts -
only that all three land before `TorrentSession.create()`/`restoreAsync()` and (for a genuinely
new torrent) `start()` do. The download-path marker was already written earlier, inside
`resolveDownloadDirectory()` ([[0065-config-side-per-torrent-storage]]) - this closes the last
remaining gap in "the whole config record exists before any real work begins."

**Metadata parsing and download-path resolution still come first, deliberately unchanged**: a
torrent whose bytes don't even parse, or whose directory can't be resolved, was never
successfully "added" in the first place - there's nothing valid to persist a record of. The
declare-before-acting boundary starts once there's a real torrent to declare, not before.

## Stability ([[0051-stability-as-a-standing-consideration]])

No new state, no new growth - this only reorders three writes (and the resulting session
construction) that already happened unconditionally on every `addTorrent()` call; nothing here
changes what's written or how often. The one behavior change is exactly the intended one: a
`create()`/`restoreAsync()`/`start()` failure after this point now leaves a recoverable,
`restore()`-visible record instead of nothing.

## Alternatives considered

- **Leave the ordering as-is, treat it as an accepted narrow-window risk** - rejected; once
  actually traced through, the user's own framing ("even if it fails it is still there with no
  progress") matches this project's existing philosophy for other partial-failure states
  (`ERROR`, `dhtBackstopActive`) closely enough that fixing it was clearly the right call, not
  just a theoretical crash-safety nitpick.
- **Only reorder for the crash-safety case, keep reporting `addTorrent()` as failed to the
  caller if `create()`/`start()` throws afterward** - this is already what happens (the
  `IOException` catch still sets `creationFailure` and the caller still sees a failure); the
  design accepts that the on-disk record and the synchronous API response can now disagree in
  that narrow failure case, in favor of the record being the more durable, recoverable source of
  truth.
