# 0008 — Designing Phase 1 to accommodate seeding

**Status:** Accepted

## Decision

Seeding (uploading back to the swarm after download completes) is deferred
to Phase 2 (see [[0009-phased-scope]]), but Phase 1 builds the following
symmetrically so seeding doesn't require a rearchitecture later:

- **Peer connection state is bidirectional from the start.** The wire
  protocol already exchanges bitfields and choke/interested state in both
  directions per connection, so `PeerConnection` models the full
  four-flag state (`am_choking`, `peer_choking`, `am_interested`,
  `peer_interested`) in Phase 1, even though Phase 1 logic just always
  chokes/never serves.
- **`TorrentStorage` has a read path from Phase 1**, not just write — serving
  blocks to peers means reading arbitrary byte ranges back off disk, and
  that capability exists before it's ever exercised.
- **`TorrentSession` lifecycle includes `Seeding` as a real state**
  (`Downloading → Verifying → Seeding → Stopped/Error`) rather than
  treating "download complete" as terminal, so Phase 2's choking/upload
  algorithm is a new transition on an existing state machine, not a bolt-on.

## Context / Why

Seeding was confirmed as a planned (not speculative) addition, just not
Phase 1 scope. Building the above pieces symmetrically now costs little
and avoids reworking `peer`, `storage`, and `torrent` layers later.
