# 0009 — Phased scope

**Status:** Accepted

## Decision

**Phase 1 — MVP (can download a torrent)**
- Bencode decoding, HTTP tracker announce/scrape, peer wire protocol,
  piece/block management with SHA-1 verification, disk I/O (single and
  multi-file torrents), sequential piece selection.
- Web UI: upload a `.torrent` file, torrent list, per-torrent progress,
  peer count.
- No magnet links, no DHT, no UDP trackers, no seeding upload logic — but
  see [[0008-seeding-design-considerations]] for what Phase 1 still builds
  to support it later.

**Phase 2 — usable day to day**
- Magnet link support (BEP 9/10). The UI will need a "paste magnet URI"
  field regardless of whether `navigator.registerProtocolHandler()` is also
  pursued — that API needs HTTPS, a one-time browser permission grant, and
  has inconsistent browser support, so paste is the reliable fallback. Exact
  UI treatment is still open and will get its own design doc when Phase 2
  starts.
- UDP tracker support (BEP 15).
- Mainline DHT (BEP 5).
- Seeding: choking algorithm (tit-for-tat + optimistic unchoke).
- Resume state persisted across restarts.

**Phase 3 — polish**
- Peer Exchange (BEP 11), upload/download rate limiting, Message Stream
  Encryption, multi-torrent global bandwidth budget.

## Context / Why

Phase 1 is scoped to the smallest set of protocol pieces that produce a
genuinely working downloader. Magnet links, DHT, and UDP trackers are
real-world-important but not required to prove the engine works, so they're
deferred rather than expanding Phase 1 indefinitely. Seeding is deferred by
priority, not by difficulty of retrofit — see 0008 for why it's still
accounted for in Phase 1's design.
