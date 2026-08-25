# 0006 — Engine internal layering

**Status:** Accepted

## Decision

Within `grimtorrenter-engine`, packages are layered strictly top to bottom
— nothing lower depends on anything above it:

1. **bencode** — BEncode value model (BString/BInteger/BList/BDictionary) +
   encoder/decoder. No dependencies.
2. **metainfo** — parses `.torrent` bytes (via bencode) into
   `TorrentMetadata`: info hash, piece hashes, piece length, file list,
   trackers.
3. **tracker** — `TrackerClient` abstraction. `HttpTrackerClient` in Phase
   1; `UdpTrackerClient` in Phase 2 (see
   [[0009-phased-scope]]).
4. **peerwire** — wire protocol message model + codec (handshake,
   choke/unchoke/interested, have, bitfield, request/piece/cancel). Pure,
   operates on buffers, no sockets.
5. **peer** — `PeerConnection`: wraps a connection (one virtual thread per
   connection, see [[0007-concurrency-model]]), runs the peerwire codec,
   holds per-peer state (`am_choking`/`peer_choking`/`am_interested`/
   `peer_interested`, remote bitfield, in-flight requests).
6. **piece** and **storage** — independent siblings, neither depends on the
   other (see [[0016-piece-and-storage]] for why this list originally
   implied a dependency between them that doesn't actually hold):
   - `PieceManager` (piece): tracks needed/in-progress/done pieces, SHA-1
     verification, piece selection behind a swappable strategy interface.
     Storage-agnostic — callers hand it bytes to verify and read block
     offsets/lengths from it to drive their own I/O.
   - `TorrentStorage` (storage): maps global byte ranges onto one or more
     files on disk. Knows nothing about "pieces."
7. **torrent** — `TorrentSession`: aggregate tying metadata + tracker
   client + peer connections + piece manager + storage together. Owns the
   lifecycle state machine (`Downloading → Verifying → Seeding →
   Stopped/Error`).
8. **engine** — `TorrentEngine`: manages the set of active
   `TorrentSession`s (add/remove/pause/resume), publishes progress events.
   The facade `grimtorrenter-app` talks to.

## Context / Why

Keeps protocol parsing (bencode, peerwire) pure and unit-testable with no
networking or disk I/O involved. Keeps orchestration (torrent, engine) as
the only place session lifecycle is coordinated, so the web layer never
touches peer/piece/storage internals directly.

## Alternatives considered

None — this is a fairly standard layered decomposition for a protocol
implementation; the main design content was in *how many* layers to name
explicitly rather than choosing between competing shapes.
