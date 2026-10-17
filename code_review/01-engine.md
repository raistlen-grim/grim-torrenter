# Code review 01 — grimtorrenter-engine

Reviewed 2026-10-17 against `main` @ 65790cc (0.9.3). Scope: everything under
`grimtorrenter-engine/src/main` (~17.8k lines, ~150 files). Tests were not reviewed.

Paths below are relative to `grimtorrenter-engine/src/main/java/com/grimtorrenter/engine/`.

Every finding was checked against the code it cites. **Confirmed** means the failure path was
traced through the code. **Plausible** means the code allows it, but it depends on runtime
behaviour that wasn't verified (JVM stack depth, timing).

Longer-term structural items are in `../TECH_DEBT.md`, not here.

---

## A. Fix before release: security and data integrity

**Status (2026-10-17): A1–A5 fixed, with tests. See design_docs/0090 (input bounds) and 0091
(announce accounting). The same change also stops `event=completed` from being re-sent on
every restore or resume of an already-complete torrent.**

### A1. Path traversal from torrent file names (Confirmed, critical)
`metainfo/MetainfoParser.java:153` accepts any `path` segment and any `name`.
`storage/TorrentStorage.java:64,66,71` then does `baseDirectory.resolve(name)` and
`.resolve(segment)` with no checks. `engine/TorrentEngine.java:2537-2545` does the same for a
single-file torrent's name. A crafted `.torrent` or magnet (the info dict is attacker-chosen,
and the info hash only proves it's the dict the magnet named) with a segment like `..` or
`/abs` makes `allocate()` (`RandomAccessFile "rw"` + `setLength`) and later `write()` truncate
and overwrite files outside the download directory. Any file the container user can write is
at risk: `settings.json`, the config markers, the proxy password file.

Removal makes it worse. `contentPathFor()` (`TorrentEngine.java:2356`) resolves the raw name
too, so "remove + delete data" on such a torrent calls `deleteRecursively` on a path outside
the torrent's own directory (`TorrentEngine.java:1833-1839`).

Fix: validate in `MetainfoParser`. Reject any segment that is empty, `.`, `..`, contains `/`
or `\` or NUL, or is absolute; apply the same to `name`. Also add a check in
`TorrentStorage.open()` as a second line of defence: `filePath.normalize().startsWith(torrentDir)`.

### A2. Any peer can overwrite verified data on disk (Confirmed, high)
`torrent/TorrentSession.java:2173-2199` (`onPieceBlockReceived`) writes every `Piece` message
it receives. It does not check that:
- the block was requested (nothing is checked against `pendingRequests` or `inFlightBlocks`),
- the piece is incomplete. It only checks `isBlockReceived`, and pieces verified at restore or
  recheck never set their block bits (`PieceManager.verify` sets only `completedPieces`), so
  for those pieces the check passes,
- `begin` is block-aligned and `begin + length` stays inside the piece.

A buggy or hostile peer can therefore overwrite the bytes of already-verified pieces of any
restored or seeding torrent. We then serve the corrupted data to the swarm, and the next
recheck finds the damage. Writes that run past the end of the torrent throw `StorageException`
and only disconnect the peer. Writes inside the torrent's range succeed.

Fix: drop the block unless `!pieceManager.isComplete(index)`, `begin % BLOCK_SIZE == 0`,
`length == pieceManager.blockLength(index, begin / BLOCK_SIZE)`, and this connection holds a
claim on that block in `inFlightBlocks` (or at least has it in `pendingRequests`).

### A3. Peer wire messages have no size cap: memory DoS (Confirmed, high)
- `peerwire/PeerWireCodec.java:82-90` trusts the 4-byte length prefix. `readNBytes(length - 1)`
  buffers up to ~2 GB per message, per connection, for any message type (Bitfield, Extended,
  Piece). Cap it at, say, 16 KiB + 13 for Piece and around 1–2 MiB for everything else.
- `peer/PeerConnection.java:471-475` calls `peerPieces.set(h.pieceIndex())` with an unchecked
  index. `Have(0x7FFFFFFF)` makes the `BitSet` grow to ~256 MB, from a 9-byte message.
  Bitfield has the same issue, bounded only by the missing cap above. Validate against the
  torrent's piece count (`PeerConnection` needs it passed in, or a bound check in the
  session's `handleMessage`).
- `metadata/MetadataFetcher.java:93` does `new byte[totalSize]` with the peer's advertised
  `metadata_size`. Up to 2 GB per magnet candidate, and up to 64 candidates run concurrently
  by default. Cap it (libtorrent uses 4 MiB by default; anything up to ~16 MiB is reasonable).
- **Plausible:** `bencode/BencodeDecoder.java:110-114` recurses without a depth limit. With the
  uncapped message length, a peer can send an extended handshake or PEX payload that is
  `llll…` a few hundred thousand deep. `StackOverflowError` is not caught by
  `PeerConnection.readLoop` (it catches only `IOException | RuntimeException`), so the read
  thread dies without calling `disconnect()`. The socket and connection slot are then held
  forever, and the idle timeout never fires because nothing is reading. Add a depth limit
  (e.g. 64), and consider catching `StackOverflowError` in `readLoop`.

### A4. `downloaded` is over-reported to trackers after every restart (Confirmed, high for private-tracker users)
`TorrentSession.reannounce()` (`:1472`), `stop()` (`:1360`) and the COMPLETED announce
(`:2262`) send `bytesDownloaded()`. That is the total of verified pieces on disk, including
everything restored at startup. `start()` sends 0 with `event=started`. BEP 3's `downloaded`
is the amount downloaded since `started`, and private trackers credit the difference. So
every container restart of a 50 GB seed adds a 50 GB "download" to the user's account on the
first reannounce, which damages their ratio. Track session-downloaded bytes separately (bytes
of pieces verified by live download this run, or `bytesReceived()`) and report that.

### A5. Unbounded tracker response body (Confirmed, medium)
`tracker/HttpTrackerClient.java:103` uses `BodyHandlers.ofByteArray()` with no limit. The
proxied path right next to it caps the body at 4 MiB (`MAX_PROXIED_RESPONSE_BYTES`). A hostile
or broken tracker can exhaust the heap. Use a size-limited handler on both paths (see
TECH_DEBT on unifying the three HTTP code paths).

---

## B. Should fix: stability and concurrency

**Status (2026-10-17): B1–B10 fixed. See design_docs/0092, plus an addendum to 0007. Also fixed
in passing: `PeerServer`'s accept loop no longer exits for good on a transient `accept()`
failure.**

### B1. DHT peer store grows without bound (Confirmed)
`dht/DhtNode.java:494` (`recordAnnounce`) accepts announces for any info hash from anyone
holding a token, which any node gets with one `get_peers`. Expired entries are pruned only
when that same info hash is next looked up (`livePeers`). Hashes nobody queries again stay
forever. A public DHT node receives a steady stream of these, so memory climbs over weeks of
uptime. Cap the total number of info hashes and the peers per hash, and sweep expired entries
on the existing DHT refresh tick.

### B2. DHT replacement pings are unbounded (Confirmed)
`DhtNode.seen()` → `replaceIfUnreachable()` (`:604`) starts a new virtual thread and an
outbound ping for **every** packet from an unknown id whose bucket is full. Pings for the same
stale node are not deduplicated. Many queries from spoofed ids landing in a full bucket mean
one thread and one ping each: an amplifier. Keep a "replacement pending" set keyed by the
stale node.

### B3. µTP bypasses download rate limiting and buffers without bound (Confirmed)
`utp/UtpSocket.java:521` always advertises the constant 64 KiB window, and `:477` appends to an
unbounded `LinkedBlockingQueue`. The download `RateLimiter` throttles by blocking
`PeerConnection`'s read loop, which works for TCP through flow control. For µTP, the socket's
own receive loop keeps ACKing and queueing while the read loop sleeps. So a rate-limited µTP
peer still sends at full speed, and the excess piles up in heap. Advertise
`max(0, 64 KiB − queuedBytes)` and stop ACKing new data once that reaches zero.

### B4. Inbound connection floods are unbounded (Confirmed)
- `peer/PeerServer.java:117` starts a virtual thread per accepted socket with no cap. Each
  socket is held for up to the 10 s handshake timeout (longer for slow MSE).
- `utp/UtpAcceptor.java:68`: every SYN, including one from a spoofed source, creates a
  `UtpSocket` with its own retransmit thread and an entry in an uncapped map, held for 10 s.

Add a semaphore for pending inbound handshakes, e.g. 256 for TCP and µTP combined, and reject
when it is full.

### B5. PEX lets one peer grow `knownAddresses` without limit (Confirmed)
`TorrentSession.handleExtended()` (`:1607`) passes every `added` entry to `addKnownPeers`.
`MAX_PEX_ADDED_PER_MESSAGE` is applied only to *outbound* messages. Combined with A3, a single
message can carry around 300k addresses, and `knownAddresses` and `failedAddresses` are never
pruned (they are already documented as growing). Cap inbound PEX at 50 per message as BEP 11
suggests, rate-limit it to one message per minute per peer, and put an overall cap on
`knownAddresses`.

### B6. A corrupt preference marker makes a torrent disappear on restart (Confirmed)
`engine/TorrentEngine.java:2363` (`readSeedingLimitOverrideMarker`) and `:2463`
(`readTorrentLimitOverrideMarker`) throw `NumberFormatException` or `IOException` on bad
content. `restoreOne()` (`:1771`) catches that and skips the **whole torrent**: it vanishes
from the UI over a cosmetic preference file. The markers are written non-atomically
(`Files.writeString`, `:2161`, `:2171`), so a crash mid-write can produce exactly that state.
The lifetime-stats and added-at readers are tolerant, so the code is already inconsistent.
Make every marker reader tolerant (fall back to INHERIT and log) and every writer atomic.
TECH_DEBT has a broader version of this.

### B7. Removing a magnet mid-fetch can bring it back (Confirmed)
`fetchMagnetMetadataViaTrackerThenAdd` and `…ViaDhtThenAdd` (`TorrentEngine.java:1527-1530`,
`:1579-1586`) call `addFetchedTorrent` as soon as a round wins, without re-checking
`pendingMagnets.containsKey(...)`. If `removeTorrent()` ran during that round, the torrent is
added anyway. Separately, `addMagnet` (`:1413-1434`) is check-then-act: two concurrent adds of
the same magnet, for example the watch folder and the UI at once, both pass the `get` checks
and start two fetch loops. Use `pendingMagnets.putIfAbsent` as the claim, and re-check before
adding.

### B8. Blocking network I/O while holding locks (Confirmed)
- `TorrentSession.start()` (`:1145`) is `synchronized` and does a full tracker announce. It
  can also `join()` the previous STOPPED announce and run a DHT lookup. Every other
  `synchronized` method waits behind it: `stop()`, `recheck()`, `fail()`, and
  `updateChoking()`, which peer read loops call on every Interested/NotInterested message.
  Pausing a torrent whose start is waiting on a 15 s tracker timeout blocks the REST call for
  that long.
- `TorrentEngine.addTorrent()` calls `created.start()` (`:1364`) **inside**
  `sessions.computeIfAbsent`. That holds the ConcurrentHashMap bin lock across the announce.
  It is also the root cause of the "upload blocks on tracker announce" UX issue already in
  memory/TODO. Move `start()` after `computeIfAbsent` returns, and make the first announce
  asynchronous: return the session in a STARTING/DOWNLOADING state and announce on its own
  thread.
- `checkForCompletion()` (`:2261`) sends the COMPLETED announce synchronously on whichever
  thread verified the last piece, usually a peer's read loop.

(On Java 25, `synchronized` no longer pins carrier threads (JEP 491), so 0007's "no
synchronized in the hot path" rule is now about lock hold times, not pinning. Worth a
one-line addendum to 0007.)

### B9. Two concurrent adds of the same *new* torrent can disagree on its directory (Plausible)
`resolveDownloadDirectory()` runs *before* `computeIfAbsent`, and its marker check and write
(`TorrentEngine.java:2530`, `:2576`) are outside `directoryResolutionLock`. Two simultaneous
adds of the same new torrent can both find no marker. The second then claims `name-2` and
overwrites the marker, while the session `computeIfAbsent` kept may be using `name`. On the
next restart the torrent points at an empty directory. Move the marker check inside the lock,
or resolve the directory inside `computeIfAbsent`. A failed add (IOException inside
`computeIfAbsent`) also leaves the placeholder file or directory and the download-path marker
behind.

### B10. `TrackedTrackerClient` mutable state isn't thread-safe (Confirmed, low)
`tracker/TrackedTrackerClient.java:41` onward: `consecutiveFailures`, `failingSince`,
`succeedingSince` and `reportedUnreachable` are plain fields. Several threads update them
concurrently: the scheduled reannounce, a forced reannounce, the background STOPPED announce
and the COMPLETED announce. The worst case is a duplicated or missed
UNREACHABLE/RECOVERED event. Guard them with a small lock, or use an `AtomicReference` to an
immutable state record.

---

## C. Code smells, duplication and bad practices

### C1. Telescoping overloads everywhere
- `TorrentEngine`: 13 constructors, from 3 to 13 parameters. The longest takes
  `boolean, boolean, …, boolean` positionally.
- `TorrentSession`: 13 `create(...)` overloads plus 13 `restoreAsync(...)` overloads. The
  longest takes 23 parameters, including adjacent booleans: `lsdService != null, true,` at
  `TorrentEngine.java:1349`.
- `Settings`: a 37-component record with **19** constructors.
- `PeerConnection.connect`: 5 overloads.

Each overload exists so old tests keep compiling. The Javadocs also count arguments ("Same as
the nineteen-arg overload below"), and those counts are already out of date in places. Fixing
this means a parameter object or builder; it's in TECH_DEBT because it touches every test.

### C2. Duplication
- **Magnet fetch loops**: `fetchMagnetMetadataViaTrackerThenAdd` and `…ViaDhtThenAdd`
  (`TorrentEngine.java:1497-1599`) are about 80% identical: deadline, `alreadyTried`, the
  fresh-candidate filter, `raceOneRound`, the empty-round sleep and the failure tail. Pull out
  one loop that takes a `Supplier<List<PeerAddress>> candidates`. That also gives B7 one place
  to fix.
- **Session construction**: the marker-read block and the post-construction wiring
  (`setLabelIds`, `setIpFilter`, `setProxyProvider`, `directories.put`) are duplicated between
  `addTorrent` (`:1310-1362`) and `restoreOne` (`:1736-1770`).
- **Marker files**: about 10 hand-written read/write pairs for `key=value` files
  (`TorrentEngine.java:2142-2487`), each with its own split/switch/tolerance logic. There are
  also five separate "temp file + ATOMIC_MOVE" implementations (`saveDhtRoutingTable`,
  `writeLifetimeStatsMarker`, `LabelRegistry`, `ProxyConfig`, `Blocklist`).
- **Inbound accept**: `acceptIncomingConnection` and `acceptIncomingUtpConnection`
  (`TorrentSession.java:1831-1916`) duplicate the same four rejection checks. The µTP copy has
  lost the `logInboundRefused` logging.
- **Kademlia lookup**: `dht/NodeLookup` and `dht/PeerLookup` repeat the same
  shortlist/`closestUnqueried`/`queryRound`/`merge` machinery.
- **SHA-1 helper**: copied into `PieceManager`, `MetainfoParser`, `MetadataFetcher`,
  `MseHandshake` and `DhtNode`.
- **Running-state check**: `state != DOWNLOADING && state != SEEDING` appears 8 times in
  `TorrentSession`. `TorrentEngine.isDownloadingOrSeeding()` already exists for the same
  thing.
- **Watch folder handlers**: `processWatchedTorrentFile` and `processWatchedMagnetFile`
  (`TorrentEngine.java:807-843`) have identical catch blocks.
- **DHT fallback failures**: `startViaDhtBackstop`'s two failure branches
  (`TorrentSession.java:1220-1241`) are identical apart from the log text.

### C3. God classes
`TorrentEngine` (2.7k lines) handles session registry, marker persistence, watch folder, magnet
resolution, DHT persistence, LSD wiring, proxy status, labels and service health.
`TorrentSession` (2.7k) handles lifecycle state machine, announce/retry, connection pool,
request scheduling and endgame, choking, PEX, verification, and the stats and snapshot API.
Both are hard to change safely; most of C2 follows from this. The split is planned in
TECH_DEBT.

### C4. Comments describe history rather than behaviour, and are going stale
A lot of the Javadoc is change history: "an earlier draft did…", "Real bug, fixed
2026-09-15", "confirmed with the user", "Found the hard way", overload arity counts, chains of
`design_docs` addenda. Some fields carry 20+ lines (`completedAtEpochMillis`,
`wasCompleteOnRestore`, `inFlightAddresses`). This history already belongs in `design_docs/`
and git. The volume is also causing drift that's visible now:
- `TorrentEngine.java:2403-2407`: `readFilePrioritiesMarker`'s Javadoc sits on
  `writeLabelIdsMarker`.
- `TorrentEngine.java:2507-2523`: two stacked Javadocs above `DirectoryResolution`. The first
  is orphaned and says the method is "Synchronized", which it isn't.
- `TorrentSession.fail()` (`:1395`): "ERROR is terminal – start() only ever resumes from
  STOPPED". That is no longer true: retryable ERROR exists (`:1156`).
- `PeerConnection` class doc (`:56-58`): "nothing yet drives am_choking to false… that's
  Phase 2". Choking was implemented long ago.
- The `configDirectory` field doc says it holds "currently just the two DHT marker files". It
  now holds every per-torrent marker.

Suggested rule going forward: a comment says what the code does and why, in the present
tense. Anything about how it came to be goes in the design doc.

### C5. Smaller bad practices
- Fully qualified names inline instead of imports: `java.util.concurrent.atomic.AtomicLong`
  (`TorrentEngine.java:921,931`), `java.util.Arrays` (`:2442,2459`),
  `java.util.concurrent.atomic.AtomicBoolean` / `Semaphore` / `TimeUnit`
  (`TorrentSession.java:411,417,1272`; `Semaphore` and `TimeUnit` are *already imported*),
  `java.net.InetAddress` (`:2603`), plus more in `MultiTrackerClient`, `PeerServer`,
  `UdpTrackerClient` and `PeerWireCodec`.
- Fields and constants declared mid-class, between methods: `incomingConnectionsSeen`/
  `Accepted` (`TorrentEngine.java:921-936`), `EMPTY_ROUND_RETRY_DELAY` and
  `SHUTDOWN_ANNOUNCE_GRACE` (`:1492-1495`), `DHT_QUERY_TIMEOUT`, `MIN_HEALTHY_NODE_COUNT`
  (`DhtNode.java:177`).
- Exception causes are dropped: `new TorrentEngineException("…" + e.getMessage())` throws
  away the stack trace (`writeStateMarker`, `deleteIfExists`, `deleteRecursively`,
  `resolveDownloadDirectory`, `restore`). Pass `e` as the cause.
- Inconsistent marker error handling. Some writers throw (`writeStateMarker`,
  `writeSeedingLimitOverrideMarker`), some log (`writeLifetimeStatsMarker`). Some readers
  throw, some tolerate. `pauseTorrent` stops the session *then* writes the marker (and may
  throw), while `setTorrentLimits` writes first.
- `InFlightBlocks` is typed on `Object owner`, so `onPieceBlockReceived` has to cast
  `(PeerConnection) holder` (`TorrentSession.java:2178`). Make it generic or use
  `PeerConnection` directly.
- `Settings` (engine) contains app-only fields: `theme`, `authEnabled`, `authTokenTtlDays`,
  `eventLogRetentionDays`. Nullable `Boolean`/`Integer` components are used as "missing in old
  JSON" markers.
- `Blocklist.isUrl` uses `toLowerCase()` without `Locale.ROOT`. It's the only one in the
  module that doesn't.
- `selectTrackerTiers` throws when every declared tracker is unsupported, even when DHT could
  run the torrent (`TorrentEngine.java:2696`). A `wss://`-only torrent is rejected outright.

### C6. Efficiency hot spots (not urgent, but cheap to fix)
- `TorrentSession.files()` costs O(files × pieces) per call (`downloadedInRange` loops every
  piece for every file). A 5k-file, 50k-piece torrent is 250M iterations per Files-tab poll.
- `bytesDownloaded()` and `PieceManager.wantedBytes()` / `wantedBytesCompleted()` are O(pieces)
  and are called on every announce, `progress()` and TorrentView broadcast. Keep running
  counters instead.
- `SequentialPieceSelectionStrategy` rescans from piece 0, one lock acquisition per piece,
  on every `requestMore` iteration (up to 128 per received block). Late in a large download
  that is O(pieces) per block received.
- `peers()` costs O(peers × pieces) per call.

---

## What looked good
- The `blocklist`, `proxy` (MiniHttp/Socks5), `mse` and `label` packages are careful: bounded
  inputs, atomic writes, clear failure modes.
- The `FailedAddresses` and `InFlightBlocks` claim and backoff logic is sound and lock-free.
- The DHT receive loop is properly defensive: it never throws, and µTP packets are routed
  ahead of KRPC decoding.
- `PieceManager` locking is tidy, and storage borrowing through `FileHandlePool` is correct.
