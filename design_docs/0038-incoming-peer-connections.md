# 0038 — Accepting incoming peer connections

**Status:** Accepted

## Decision

A known gap noted in passing during the magnet/BEP 10 work and tracked in `PROGRESS.md`
since: `PeerConnection` only ever connected outbound (`connect()`); nothing listened on
`ourListenPort` for peers initiating to *us*, despite that exact port being advertised to
every tracker and DHT node ([[0028-magnet-links-and-dht]]'s own `Port` message wiring
already assumes we're reachable there). A well-behaved peer that has us in its own peer
list but never gets an outbound connection from us (we're slow, temporarily unreachable,
or it simply hasn't been tried yet) had no way to reach us at all.

### `PeerServer` (new, `peer` package)

One instance per `TorrentEngine`, not one per torrent - `ourListenPort` is the single port
advertised regardless of which torrent a remote peer is trying to reach, mirroring how
`DhtNode` already owns one shared UDP socket rather than one per session. Binds a
`ServerSocket`, runs its own accept loop on one dedicated virtual thread (blocking-style
I/O per [[0007-concurrency-model]], same as `DhtNode`'s receive loop and
`PeerConnection`'s own read loop), and for each accepted connection, spawns a *separate*
virtual thread to read that connection's handshake and route it - so one slow or hanging
remote peer mid-handshake can't stall the accept loop from accepting the next one.

**Deliberately knows nothing about `TorrentSession`/`TorrentEngine`** - it's constructed
with a `Function<InfoHash, Optional<IncomingConnectionHandler>>` lookup, where
`IncomingConnectionHandler` (also new) is a minimal `(Socket, Handshake) -> void`
functional interface living in the same `peer` package. This keeps `PeerServer` at its own
layer (5) rather than reaching up to `torrent`/`engine` (7/8), per
[[0006-engine-layering]] - `TorrentEngine` is the one place that bridges the generic
lookup to its actual `sessions` map (`session::acceptIncomingConnection` as the handler,
once the info hash resolves to a live session). A connection whose info hash resolves to
nothing (unknown or removed torrent) is simply closed.

### `PeerConnection.accept(...)` (new factory, mirrors `connect(...)`)

The handshake exchange runs in the opposite order from an outbound connection: the
initiating peer speaks first, so by the time this factory is called, `PeerServer` has
already read the remote's handshake (it had to, just to know which session to route to) -
`accept(...)` only needs to write ours back. Everything past that point (starting the read
loop, sending our own extended handshake if the remote supports it) is identical code to
`connect()`'s own tail, so this doesn't duplicate that logic, only the handshake-order half.

### `TorrentSession.acceptIncomingConnection(Socket, Handshake)` (new, public)

The inbound counterpart to `fillConnections()`/`attemptConnect()`. Public (unlike those)
since `PeerServer` calls it from a different package via the `IncomingConnectionHandler`
method reference. Closes the socket itself rather than throwing when the session isn't in
a state that wants new connections (not `DOWNLOADING`/`SEEDING`) or is already at
`MAX_CONNECTIONS` - `PeerServer`'s job ends at routing, not deciding whether a session
actually wants what it's been offered, matching the existing "soft cap, no fancy handling"
treatment `MAX_CONNECTIONS` already gets on the outbound side ([[0017-torrent-session]]).
Otherwise identical bookkeeping to a successful outbound connection: added to
`connections`, then `onPeerConnected()` (send our listen port, bitfield if we have any
complete pieces, update interest) - a peer that connects to us gets exactly the same
treatment as one we connected to.

### Opt-in, not unconditional - `acceptIncomingConnections` (new engine constructor flag)

Same reasoning and shape as `enableDht`: binding a real listening socket is not what a
hermetic test suite should trigger just by constructing a `TorrentEngine`, so it's a new
boolean parameter on a 5-arg constructor, with the existing 3-arg and 4-arg constructors
unchanged and delegating with `false` - **every existing caller, production and test, is
completely unaffected**. `TorrentEngine.createPeerServer` mirrors `createDhtNode`'s own
soft-fail pattern (a bind failure - e.g. the port's already in use - logs a warning and
leaves the field `null`, rather than failing engine construction entirely; outbound-only
operation still works fine without it). `TorrentEngineProducer` gained a matching
`grimtorrenter.accept-incoming-connections` config property (default `true`), and the
grimtorrenter-app test `application.properties` sets it `false` for the same reason
`dht-enabled` already is - not real internet access like DHT's bootstrap, but still a real
listening socket, and OS-level "allow incoming connections?" firewall prompts on some
platforms are worth avoiding for a test run regardless. **Superseded**:
`accept-incoming-connections` moved from a plain `@ConfigProperty` to the live
`SettingsStore` alongside `dht-enabled` - see [[0041-live-settings-store]]; tests now get
`false` from a seeded `settings.json` instead. The underlying reasoning is unchanged, only
where the value now lives.

`TorrentEngine.peerServerPort()` (new, `OptionalInt`) exposes the actually-bound port -
needed for tests constructed with port `0` (ephemeral) to know what to connect a test
client to; production wiring already knows its own configured port so has no real use for
it.

## Testing

- `PeerConnectionTest` gained `acceptsInboundConnectionAndExchangesHandshake` - mirrors the
  existing `connectsAndExchangesHandshake` with roles reversed (the fake peer dials in and
  speaks first). Doesn't re-test the shared read-loop/extended-handshake code path `accept`
  and `connect` both end in - that's already covered by `connect()`'s own tests.
- `PeerServerTest` (new) - a real client `Socket` against a real `PeerServer`, confirming a
  known info hash routes to the right handler with the right `Socket`/`Handshake`, and an
  unknown one gets the connection closed (next read hits EOF).
- `TorrentSessionTest` gained `acceptIncomingConnectionAdoptsARemotelyInitiatedConnection` -
  the test itself plays `PeerServer`'s role (accepts the socket, reads the initial
  handshake) before handing off to `acceptIncomingConnection`, confirming the session-level
  bookkeeping (added to `peers()`, our `Port` message sent) without needing a real
  `PeerServer` in the loop.
- `TorrentEngineTest` gained `acceptsAnInboundConnectionForAKnownTorrent` - full end-to-end,
  a real external `Socket` against the engine's own bound port (constructed with port `0`,
  same ephemeral-port convention `dhtStatusReportsEnabledWhenDhtIsEnabled` already uses),
  confirming the connection reaches the right session purely by info hash.

## Alternatives considered

- **One `ServerSocket` per `TorrentSession`** instead of one shared `PeerServer` per
  engine - rejected; `ourListenPort` is a single port shared across every torrent (already
  true of the DHT UDP socket and the tracker/DHT-advertised port), and binding one port
  per torrent would mean either a different advertised port per torrent (wrong - trackers
  are told the one `ourListenPort` regardless) or every session fighting over the same
  port (impossible - only one listener can bind a given TCP port at a time).
- **`PeerServer` reading the handshake and directly holding a `Map<InfoHash,
  TorrentSession>` itself** - rejected in favor of the generic lookup-function injection;
  would pull `torrent`-layer types down into `peer`, inverting [[0006-engine-layering]]'s
  dependency direction for no real benefit over a one-line bridge method in `TorrentEngine`.
- **Always-on inbound listening** (no opt-in flag) - rejected for the same reason
  `enableDht` isn't unconditional either: real listening-socket side effects (including
  platform firewall prompts) aren't something a test suite should trigger just by
  constructing an engine.
