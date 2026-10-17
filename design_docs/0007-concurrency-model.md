# 0007 — Concurrency model for peer connections

**Status:** Accepted

## Decision

Java virtual threads (requires Java 21+). One virtual thread per
`PeerConnection`, written as ordinary blocking-style I/O code.

## Context / Why

The engine needs to handle dozens to hundreds of simultaneous peer sockets.
Virtual threads get that scale with straight-line, top-to-bottom code per
connection instead of a callback/event graph — simpler to write, read, and
unit-test. This project isn't chasing the concurrency ceiling a
competitive production torrent client would need, so the simplicity
trade-off favors virtual threads over an async runtime.

Care point: avoid `synchronized` blocks in the per-connection hot path, as
they can pin a virtual thread to its carrier thread and defeat the
scalability benefit.

## Alternatives considered

- **Netty** — event-loop/non-blocking I/O, already present transitively via
  Quarkus/Vert.x so no extra dependency footprint. More headroom at extreme
  peer counts, but callback/async-style code is more complex to write and
  reason about for this project's needs.
- **Raw NIO Selector, hand-rolled event loop** — full control, but
  reimplements what Netty already provides, for no benefit here.

## Addendum 2026-10-17 - Java 25 and what the `synchronized` rule is really about

The project runs on Java 25. Since JEP 491 (Java 24), a virtual thread blocking inside
`synchronized` unmounts from its carrier like any other blocking call, so the pinning concern
behind "avoid `synchronized` in the per-connection hot path" no longer applies. `synchronized`
and `ReentrantLock` are now equivalent for virtual threads, and either is fine.

The rule that still matters, and the one this decision now states: **never block on I/O (network,
or anything that can take a timeout) while holding a lock that other threads need promptly.**
The pre-release review found `TorrentSession.start()` holding the session monitor across the
tracker announce, which stalled `stop()`, `recheck()`, and every peer's read loop that called
`updateChoking()`. `TorrentEngine.addTorrent()` was doing the same inside
`ConcurrentHashMap.computeIfAbsent`. Both are fixed in [[0092-resource-bounds-and-lock-hold-times]].
