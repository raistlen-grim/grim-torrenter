# 0099 - Engine clean-up: shared helpers, consistent markers, cheaper hot paths

Status: Accepted (2026-10-18). Implements the C items of the pre-release engine review
(`code_review/01-engine.md`): C2, C4, C5 and C6. C1 (telescoping overloads) and C3 (splitting
`TorrentEngine` and `TorrentSession`) stay in `TECH_DEBT.md`, because each touches most of the
test suite. Applies [[0051-stability-as-a-standing-consideration]].

No REST, WebSocket or on-disk format changes. One behaviour change (section 4).

## 1. One copy of each piece of shared machinery (C2)

- **Magnet fetch.** The tracker and DHT fetch loops were about 80% the same. Now there is one
  loop, `fetchMagnetMetadataThenAdd()`, which gets each round's candidates from a
  `MagnetCandidateSource`:
  - `trackerCandidates()` announces. A failed announce falls back to DHT, or to an empty round
    (as before, [[0028-magnet-links-and-dht]]'s addendum). It reports "Could not announce to
    any tracker" if no announce ever worked.
  - `dhtCandidates()` runs a DHT lookup. A failed lookup ends the fetch, as before.

  The deadline, the tried set, the blocklist filter, `raceOneRound`, the empty-round pause and
  the removal check exist once.
- **Session construction.** `addTorrent()` and `restoreOne()` both build the session through
  `buildSession()`. It reads the per-torrent markers, builds the per-torrent rate limiters,
  chooses `create()` or `restoreAsync()`, and wires labels, blocklist, proxy and the content
  path. A `SessionOrigin` (NEW, READDED, RESTORED) carries the one real difference: whether
  missing files are reported (restore) or recreated (re-add), per
  [[0088-missing-files-are-reported]].
- **Atomic writes.** `AtomicFiles.write()` (in the engine's root package) replaces five copies
  of "temp file in the same directory, then `ATOMIC_MOVE`": the per-torrent markers, DHT
  persistence, `LabelRegistry`, `ProxyConfig` and the `Blocklist` cache. A `Content` callback
  covers the cases that write the temp file themselves: the proxy password (owner-only before
  the password goes in) and the blocklist (downloaded and parsed in place). The blocklist's
  source file, previously a plain write, now goes through it too.
- **Marker files.** `KeyValueFile` holds the `key=value` format (split at the first `=`, skip
  lines without one; callers ignore unknown keys). On top of it:
  - `readMarker()` gives every reader the same tolerance: the fallback when the marker is absent,
    and the fallback, logged, when it can't be read or parsed.
  - `writeMarker()` gives every writer the same failure: a `TorrentEngineIoException` naming
    what couldn't be saved.

  The lifetime-stats writer stays best-effort on purpose
  ([[0064-persistent-lifetime-stats]]).
- **Inbound accept.** The TCP and µTP paths share `acceptInbound()`. The µTP copy had lost the
  refusal logging; it has it again.
- **Kademlia lookup.** `IterativeLookup<R>` holds the shortlist, the best-K window, ALPHA
  concurrency and `MAX_ROUNDS`. `NodeLookup` and `PeerLookup` supply the query and what to do
  with each answer. This reverses 0028's "keep them separate" call: the shared part turned out
  to be everything but about ten lines, and the copies had begun to differ in comments.
- **SHA-1.** `Hashing.sha1(byte[]...)` replaces five private copies.
- **"Running".** `TorrentState.isRunning()` (DOWNLOADING or SEEDING) replaces eight copies of
  the check in `TorrentSession` and `TorrentEngine.isDownloadingOrSeeding()`.
- **Watch folder.** The `.torrent` and `.magnet` handlers share one catch block.

The DHT backstop's two identical failure branches (also listed in C2) had already gone with
0092's announce rework.

## 2. Comments (C4)

The specific drift is fixed:
- `readFilePrioritiesMarker`'s Javadoc that sat on `writeLabelIdsMarker`;
- the orphaned Javadoc above `DirectoryResolution`, which called the method "synchronized";
- `fail()`'s "ERROR is terminal", which stopped being true with the retryable ERROR;
- `PeerConnection`'s "Phase 2" note about choking;
- `configDirectory`'s "just the two DHT marker files".

The comments rewritten in this change describe current behaviour. The wider hygiene pass, taking
change history out of every Javadoc, stays in TECH_DEBT: it touches thousands of lines for no
behaviour change, and it is safest done together with the C3 split.

## 3. Smaller practices (C5)

- Inline fully qualified names became imports across the engine.
- Constants and fields declared between methods moved to the field block (`TorrentEngine`,
  `DhtNode`).
- `KrpcException` keeps its cause. The other cause-dropping throws were already fixed by
  [[0094-session-revocation-error-shape-and-api-consistency]].
- `InFlightBlocks<O>` is generic, so `TorrentSession` no longer casts `Object` back to
  `PeerConnection`.
- `Blocklist.isUrl` lowercases with `Locale.ROOT`.
- **Write before acting, everywhere.** `pauseTorrent()` and `resumeTorrent()` now write the
  state marker before stopping or starting the session, like every other per-torrent setter
  (and the "persist before acting" rule of [[0069-persist-before-starting]]). If the write fails,
  the call throws with nothing changed. Before, a failed write left a paused torrent that the
  next restart quietly resumed, while the caller got an error anyway.

Not done: moving the app-only fields out of the engine's `Settings`. That changes the settings
JSON's shape between modules and belongs with C1's parameter objects (TECH_DEBT).

## 4. Behaviour change: unsupported-only trackers fall back to DHT (C5)

A torrent whose trackers were all of kinds this client can't use (`wss://`, say) used to be
rejected outright, even though DHT could find its peers. `selectTrackerTiers()` now treats it
like a torrent with no trackers: an empty tier list, a `NoOpTrackerClient`, and peers from DHT,
with an INFO log saying so.

**A private torrent is still rejected.** BEP 27 forbids it DHT, so with no usable tracker it
could never find a peer.

## 5. Hot paths (C6)

- **`PieceManager` keeps running totals**: completed bytes, wanted bytes, wanted-and-completed
  bytes, and wanted and wanted-completed piece counts.
  - They are updated in `verify()`, and recounted in full only when the priorities change or
    `resetCompletion()` runs.
  - `bytesDownloaded()`, `wantedBytes()`, `wantedBytesCompleted()` and `isWantedComplete()`
    used to walk every piece. They run on every announce, every `progress()` and every
    snapshot broadcast, and are now O(1).
- **`TorrentSession.files()`** does one pass over the pieces, crediting each completed piece to
  the files it overlaps, instead of a pass per file. It is now O(pieces + files) rather than
  O(pieces × files): a 5,000-file, 50,000-piece torrent drops from 250M steps per Files-tab poll
  to about 55k.
- **`peers()`** takes one snapshot of the still-needed pieces and works per peer with `BitSet`
  operations (`PeerConnection.peerPieces(count)` returns a copy). Before, it took one lock per
  piece per peer.
- **`SequentialPieceSelectionStrategy`** makes one pass instead of one per priority tier, and
  skips completed pieces a word at a time (`PieceManager.nextIncompletePiece()`). Late in a large
  download, that pass mostly lands on the few pieces still missing.
  - Same result as before: the lowest-index HIGH piece the peer has, else the lowest MEDIUM,
    else the lowest LOW.
  - It reads the tiers without re-taking the lock it already holds.

## Stability

- **No new threads, and no new retained state** beyond five counters per torrent.
- **Locking.**
  - The counters are guarded by `PieceManager`'s existing lock, in the same critical sections that
    change what they count.
  - The strategy's unlocked reads (`tierUnderLock`, `nextIncompletePiece`) are package-private,
    and they are only reached through `selectNextPiece()`, which holds the lock.
  - `peers()` holds the piece lock once per call rather than O(peers × pieces) times.
- **Cleanup on every exit path.**
  - `AtomicFiles` deletes its temp file in `finally`. The blocklist's temp download, which used to
    be cleaned up by hand, gets that too.
  - A failure inside the content callback leaves the target untouched.
- **Hostile input.** Marker reading is now uniformly tolerant: a corrupt marker falls back and
  logs; it never drops a torrent from restore. A hostile bitfield's spare bits can't inflate a
  peer's availability figure, because `peerPieces(count)` cuts it at the piece count.
- **The DHT fallback for unsupported-only trackers** adds no new peer source. It is the same
  `NoOpTrackerClient` path a trackerless torrent already takes, and it stays off for private
  torrents.

## Alternatives considered

- **One file per torrent instead of ~10 markers.** That is the TECH_DEBT end state. It changes
  the on-disk layout and needs a migration; the shared reader and writer get the consistency now
  without one.
- **Keeping `pauseTorrent()` stop-first**, so a pause works even on a full disk. It was rejected
  for consistency: the restart would undo it anyway, and the caller already saw an error.
