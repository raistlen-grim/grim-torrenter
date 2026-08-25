# 0040 — Peer Exchange (BEP 11)

**Status:** Accepted

## Decision

First Phase 3 item ([[0009-phased-scope]]): BEP 11 Peer Exchange, a BEP 10 extension
(`ut_pex`) letting already-connected peers gossip about who else they're connected to -
supplementing tracker/DHT discovery, especially useful once a swarm's tracker is slow or
the DHT backstop ([[0036-dht-backstop-for-tracker-bearing-torrents]]) hasn't found much yet.

Built directly on the existing BEP 10 extension-protocol machinery from magnet-link support
([[0028-magnet-links-and-dht]]) - `PeerConnection.remoteExtensionId`/`sendExtended` and the
extended-handshake exchange were already generic, `TorrentSession.handleMessage` already had
a `case Extended` placeholder anticipating exactly this ("a specific extension built on top
of it... will get its own case here once one exists").

**Three scoping questions confirmed with the user rather than assumed:**

- **IPv4 `added`/`dropped` only** - no `added.f` peer-flags byte, no IPv6
  (`added6`/`dropped6`). Matches every compact-peer format already in this codebase
  (tracker responses, DHT's `CompactPeers`/`CompactNodes`), which is IPv4-only throughout.
- **Session-wide delta, not per-connection.** One "who's newly connected / newly gone since
  last cycle" delta computed once per session per PEX cycle, broadcast identically to every
  ut_pex-supporting connected peer (each with its own address filtered out of "added" first).
  Simpler than tracking, per connection, exactly what's already been told to that specific
  peer - the real cost is a peer that reconnects mid-session won't get "caught up" on
  everything it missed, judged not worth the extra bookkeeping.
- **`dropped` is decoded but never acted on.** It's one peer's own opinion about a third
  party's reachability - not reliable enough to act on (e.g. deprioritizing or forgetting an
  address), and there's no real downside to still trying an address someone else claims is
  gone. `added` is the only signal that feeds into anything.

### `pex` package (new, mirrors `metadata`'s existing shape for ut_metadata)

`PexMessage` (record: `added`, `dropped`, both `List<PeerAddress>`) and `PexCodec`
(encode/decode against the bytes inside an `Extended` message's payload). BEP 11's compact
format is a **different shape** from BEP 5's `CompactPeers` (dht package, package-private
anyway): each of "added"/"dropped" is a single `BString` of concatenated 6-byte entries,
not a `BList` of separate 6-byte strings - so this is new code, not a reuse of the DHT
version, mirroring `UtMetadataCodec`'s own precedent of one codec per extension rather than
a shared "extension codec" abstraction.

### `TorrentSession` wiring

- **Advertises `ut_pex`** in the `extensionsToAdvertise` map both `attemptConnect()`
  (outbound) and `acceptIncomingConnection()` (inbound) already pass to
  `PeerConnection.connect`/`accept` - previously `Map.of()` (nothing advertised) for
  TorrentSession's own steady-state connections; BEP 9's `ut_metadata` is still only ever
  used by the separate one-shot `MetadataFetcher` during magnet resolution, unaffected.
- **`sendPexUpdates()`** (new, package-private for direct testing - same
  package-private-for-testing rationale as `reannounce()`) - scheduled alongside
  `reannounce`/`sendKeepAlives`/`updateChoking` in `enterDownloading()`, every 60s (BEP 11's
  own recommended minimum interval). Computes the added/dropped delta against
  `previousPexPeers` (a plain field, not a concurrent collection - only ever touched from
  the session's single scheduler thread, same reasoning as every other scheduled-task
  field in this class) from the **currently-connected peer set**, not `knownAddresses` -
  PEX shares who's actually in the swarm and reachable, not untested tracker/DHT
  candidates. Silently skips a connection with no advertised `ut_pex` support
  (`remoteExtensionId` empty).
- **`handleExtended()`** (new, dispatched from `handleMessage`'s `case Extended`) - the one
  extension TorrentSession's steady-state connections handle. Filters by
  `extendedMessageId() == PEX_EXTENSION_ID` (our own advertised id - id `0`, the handshake
  itself, is already consumed internally by `PeerConnection` before this is ever reached).
  `added` feeds straight into the existing `addKnownPeers()` - the exact same mechanism
  tracker- and DHT-discovered peers already use, so PEX-discovered peers get identical
  treatment (added to `knownAddresses`, picked up by the next `fillConnections()`). A
  malformed message is dropped silently, matching `MetadataFetcher`'s own tolerance for a
  peer sending garbage.

## Testing

- `PexCodecTest` (new) - encode/decode round-trip, empty lists round-trip as empty (not
  omitted keys), a missing key decodes as empty (a peer with nothing to report in one
  direction is expected to just omit it), malformed length and non-dictionary payloads throw.
- `TorrentSessionTest` gained two integration tests, both over real local sockets:
  - `sendPexUpdatesTellsEachConnectedPeerAboutTheOther` - two fake peers connect, each
    advertising a deliberately different `ut_pex` id (proving the per-connection
    `remoteExtensionId` lookup drives what's sent, not a shared/fixed one), one directly
    triggered PEX cycle, and each fake peer's own decoded message is asserted to contain
    only the *other* peer's address, never its own.
  - `receivingPexAddedFeedsIntoKnownPeersAndAttemptsConnection` - a connected peer sends a
    crafted `ut_pex` message introducing a third, previously-unknown address; the session is
    confirmed to actually attempt (and complete) a handshake to it.
  - Both fake-peer fixtures have to *decode* `TorrentSession`'s own advertised extension id
    from its extended handshake response, rather than assuming a fixed value - exercising
    the negotiation the same way a real peer implementation would have to, not just
    asserting against a hardcoded constant.

## Alternatives considered

- **Per-connection delta tracking** - rejected; see the scoping decision above.
- **Acting on `dropped`** - rejected; see the scoping decision above.
- **Full BEP 11 (IPv6 + peer flags)** - rejected; see the scoping decision above.
- **A shared "compact peer list" codec/abstraction across dht/pex** - rejected; the two
  BEP's wire shapes are genuinely different (list-of-strings vs. one concatenated string),
  and `dht`'s own version is package-private by design - not worth introducing a shared
  abstraction across packages for two call sites with different formats anyway.
