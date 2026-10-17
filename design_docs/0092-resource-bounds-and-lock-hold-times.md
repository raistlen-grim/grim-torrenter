# 0092 - Resource bounds, tolerant persistence and lock hold times

Status: Accepted (2026-10-17). Found in the pre-release engine review
(`code_review/01-engine.md`, items B1-B10). Follows [[0090-untrusted-input-bounds]], which
bounded individual messages; this doc bounds what accumulates across many of them, and fixes
places that held a lock across slow I/O ([[0007-concurrency-model]]'s 2026-10-17 addendum).
Applies [[0051-stability-as-a-standing-consideration]] throughout.

## Decisions

### Bounded growth from other parties' traffic

| What | Before | Now | Where |
|---|---|---|---|
| DHT peer store (B1) | Any node with a token could add peers for any info hash. Expired entries were pruned only when that same hash was queried again. | At most 2000 info hashes. A new hash past the cap is refused, after one sweep for expired entries (at most one sweep a minute). At most 100 peers per hash; a new one replaces the least recently announced. Replies carry at most 50, newest first, which keeps a reply inside one datagram. Expired entries are also swept on every routing-table refresh tick. | `DhtNode.recordAnnounce/livePeers/pruneExpiredPeers` |
| DHT replacement pings (B2) | One virtual thread plus one outbound ping per packet from an unknown id landing in a full bucket. That made us an amplifier. | One ping in flight per stale contact; later candidates are simply not added, as if it had answered. Bounded by the number of buckets. | `DhtNode.replaceIfUnreachable` |
| Pending inbound handshakes (B4) | One socket and thread per TCP connection, and one `UtpSocket` with a retransmit thread per SYN (spoofable), each held for up to 10 s, with no cap. | 128 in progress for TCP and 128 for µTP. TCP connections past the cap are closed on accept; µTP SYNs past it are ignored (a real peer resends). | `PeerServer`, `UtpAcceptor` |
| Known candidate addresses (B5) | Grew without limit. PEX `added` was not capped inbound. | Inbound PEX is capped at BEP 11's 50 per message. Each torrent keeps at most 3000 candidates (libtorrent's `max_peerlist_size`). At the cap, addresses that have already failed and aren't being dialled are dropped (at most once a minute) to make room; if none can be dropped, new ones are ignored. The self-connection exclusion is kept. | `TorrentSession.recordKnownPeers` |

### µTP receive-side flow control (B3)

`UtpSocket` used to advertise a fixed 64 KiB window and queue every in-order packet without
limit. The download rate limiter works by blocking `PeerConnection`'s read loop, and TCP turns
that into backpressure. µTP's own receive loop kept ACKing, so a rate-limited µTP peer still
sent at full speed, and the excess piled up in the heap.

Now:
- Unread data is capped at **256 KiB** (`RECEIVE_BUFFER_LIMIT_BYTES`). The advertised window is
  whatever room is left in that buffer.
- An in-order packet that doesn't fit is dropped and not ACKed, so the sender resends it later.
- When `receive()` frees enough room for a full packet after the window had been too small, it
  sends a window-update ACK.
- The sender side is fixed to match: a rising peer window now wakes a sender blocked in
  `acquireWindowSlot()`. With nothing in flight and the window too small for a whole second,
  the sender sends one packet anyway as a probe, so a lost window update can't stall the
  connection forever.
- A resend caused by a full peer window is not counted as a loss (no `cwnd` cut), not counted
  toward `MAX_RETRIES`, and not used as an RTT sample. Those resends are capped separately at
  240 (`MAX_WINDOW_PROBES`), so a peer that has died with a full window is still given up on.

Why 256 KiB rather than 64 KiB: it's the same order as libtorrent's per-peer receive buffer, it
is generous next to one 16 KiB block request, and the existing 200 KiB send-then-read test
pattern still fits. The cost is 256 KiB per µTP connection in the worst case.

### Tolerant, atomic per-torrent markers (B6)

Two rules:
- **Every marker is written atomically** (temp file in the same directory, then
  `ATOMIC_MOVE`). The DHT routing table and lifetime stats already worked this way; that code
  is now shared as `writeAtomically`.
- **Every marker reader tolerates** a missing, unreadable or unparseable file. It falls back to
  that preference's default (INHERIT, normal priority, RUNNING), logs, and never throws.

Before this, a torn seeding-limit or torrent-limit marker threw during `restoreOne()`, and the
whole torrent silently vanished from the UI over a preference file.

### Magnet add/remove races (B7)

- `addMagnet` claims the info hash with `pendingMagnets.putIfAbsent` before writing the marker,
  so two concurrent adds start exactly one fetch.
- The fetch loops re-check `pendingMagnets` just before adding the fetched torrent. A removal
  during the winning round used to be ignored until the *next* round, by which time the torrent
  had already been added. What's left is a window of milliseconds between that check and
  `addTorrent`.

### No lock held across a tracker announce (B8)

- `TorrentSession.start()` now checks and claims under the session lock, does the announce
  (and any DHT fallback) **without** it, then re-takes the lock to apply the outcome.
- `stop()` bumps a `lifecycleGeneration`. A start whose announce straddled a stop discards its
  outcome, and sends a background STOPPED if the tracker had already accepted our STARTED.
- `recheck()` during an in-flight start resumes afterwards, as for a running torrent.
- A second `start()` during the network phase does nothing.
- `start()` is still synchronous for its caller. Making the first announce asynchronous (so a
  REST add returns immediately) is the separate UX item in TODO, and is unaffected.
- `TorrentEngine.addTorrent()` starts the session *after* `computeIfAbsent` returns instead of
  inside it.
- `checkForCompletion()` sends COMPLETED on its own virtual thread. It used to run on a peer's
  read loop, or under the session lock from `start()`.

### Directory resolution and tracker status (B9, B10)

- `resolveDownloadDirectory` now holds `directoryResolutionLock` across the whole
  check-marker, claim, write-marker sequence. Two concurrent adds of the same new torrent
  could previously claim two directories, with the marker naming one and the session using the
  other.
- `TrackedTrackerClient.recordSuccess/recordFailure` are `synchronized`. Overlapping announces
  for one torrent could race the failure-streak bookkeeping and duplicate or miss
  UNREACHABLE/RECOVERED events.

### Also fixed in passing

`PeerServer`'s accept loop used to exit permanently on any `accept()` failure, for example
running out of file descriptors, which ended inbound connections until a restart. It now logs,
pauses a second, and retries.

## Stability

This decision is entirely about stability. Every cap above is a fixed constant documented
beside its code; none is user-configurable, since none is something a user would sensibly
tune. The locks added are all off the hot path: the directory lock (taken only on add), the
tracker client (once per announce) and the DHT store (inside `ConcurrentHashMap.compute`). No
new long-lived threads. The new virtual threads (COMPLETED announce, an abandoned start's
STOPPED) are short-lived and bounded by the tracker client timeouts.

## Alternatives considered

- **Per-peer PEX rate limiting** (one accepted message a minute) on top of the per-message
  cap: not needed once the candidate list itself is capped. Kept in reserve.
- **A STARTING `TorrentState`** for start()'s network phase: clearer to read, but every client
  would have to learn a new state ([[0071-thin-frontend-as-a-standing-consideration]]), and
  several tests and the event listener key off exact transition sequences. The generation
  counter gets the same safety with no visible change.
- **Shrinking the µTP buffer to 64 KiB:** it would deadlock any caller that sends before it
  starts reading, which is the existing tests' pattern. Real peer connections always read on
  their own thread, but 256 KiB costs little and avoids the trap.

## 2026-10-17 addendum

Adding and resuming a torrent now start it in the background: `start()` is split into a locked
`beginStart()` and an unlocked `finishStart()`, and the session shows the new STARTING state
while the announce is in flight. See [[0098-background-start-and-starting-state]].
