# 0062 — Local Service Discovery (BEP 14)

**Status:** Accepted

## Decision

Adds BEP 14 Local Service Discovery: an engine-wide `LsdService` (new `lsd` package,
`grimtorrenter-engine`) that announces every active, non-private torrent to the local network
over IPv4 multicast (`239.192.152.143:6771`) and listens for the same from other clients on the
LAN, feeding discovered peers straight into the existing `TorrentSession.addKnownPeers()` path.
Picked up from `TODO.md`'s "No LSD implementation" item — the last unimplemented piece of this
engine's peer-discovery backlog now that DHT-as-a-concurrent-source, PEX, and concurrent
multi-tracker announce ([[0036-dht-backstop-for-tracker-bearing-torrents]],
[[0040-peer-exchange]], [[0022-multi-tracker-fallback]]) are all done.

Architecturally closest to `DhtNode`/`PeerServer` ([[0028-magnet-links-and-dht]],
[[0038-incoming-peer-connections]]): one shared singleton per `TorrentEngine`, not one per
torrent, owning one real socket, with construction failure logged and recorded as a library
event rather than failing the whole engine.

## Protocol scope

**IPv4 only.** Matches `PexCodec.requireIPv4`'s existing project-wide IPv4-only scope
([[0040-peer-exchange]]) — the IPv6 LSD multicast group is a deliberate, documented omission,
not an oversight. The BEP 14 message itself (`BT-SEARCH * HTTP/1.1` plus `Host`/`Port`/
`Infohash`/`cookie` headers) is a small HTTP-request-shaped text format over UDP, not bencode
like every other wire format in this codebase — `LsdCodec` parses it line-by-line, matching
header names case-insensitively since real-world implementations disagree on casing.

**A real correctness pitfall, caught and fixed in the codec**: a received `Infohash` header is
decoded via `InfoHash.of(byte[])`, never `new InfoHash(rawHexFromWire)` directly — `InfoHash.of`
canonicalizes to lowercase hex via `HexFormat`, matching how every `InfoHash` elsewhere in this
codebase is minted. A peer sending uppercase hex (spec doesn't mandate a case) would otherwise
mint an `InfoHash` whose `equals()`/`hashCode()` never matches our own lowercase-keyed session
map, silently dropping every discovered peer for that torrent.

## Self-suppression via cookie, not multicast-loopback options

Each `LsdService` instance generates a random per-instance cookie (`SecureRandom`, matching the
idiom `PeerServer`/`MseHandshake` already use for protocol-level randomness) at construction,
includes it in every outgoing announce (BEP 14's own optional `cookie` header), and drops any
received announcement carrying that same cookie before it ever reaches the peer-found callback.
Chosen over `IP_MULTICAST_LOOP`/loopback-mode socket options because those vary by platform and
interface; the cookie check works uniformly regardless, and is what real LSD implementations
already do for exactly this reason.

## Interface handling and outbound sends

Joins the multicast group for **receiving** on every interface that is up, supports multicast,
and isn't loopback (the public, auto-detecting constructor) — or on a caller-supplied list (a
package-private constructor `LsdServiceTest` uses to pin to the loopback interface for a real
loopback-multicast integration test, the same non-hermetic-but-accepted precedent
`DhtNodeTest`/`PeerServerTest`'s own real-socket tests already set).

**Outbound sends go via a single interface, fixed once at construction**
(`interfaces.get(0)`), not re-selected per send. `MulticastSocket`'s outgoing interface is
process-wide mutable socket state (`setNetworkInterface()`), and `TorrentEngine` dispatches each
periodic announce tick onto its own virtual thread (same pattern as `refreshDhtRoutingTable()`)
— if one tick runs long, two `announce()` calls could otherwise race on that same mutable state.
Fixing it once avoids a real race rather than adding synchronization for what's already
documented (`TODO.md`) as a minor, LAN-only contributor. A multi-homed host is still fully able
to *receive* LSD traffic on every suitable interface — only proactive announcing is limited to
the primary one. Worth revisiting if a real multi-NIC deployment shows this matters in practice.

## BEP 27 privacy gating

A private torrent's info hash must never be broadcast to the LAN, exactly the same reasoning
`TorrentSession.dhtEligible()`/`extensionsToAdvertise()` already apply to DHT/PEX
([[0028-magnet-links-and-dht]], [[0040-peer-exchange]]). Two gates, both on `!isPrivate()`:

- `TorrentEngine.activeInfoHashesForLsd()` — the outbound announce list only ever includes
  non-private, currently DOWNLOADING/SEEDING torrents.
- `TorrentEngine.onLsdPeerFound()` — re-checks `!isPrivate()` per-session before trusting an
  *incoming* announcement, rather than assuming the sender already filtered correctly. A
  misbehaving or malicious LAN peer could announce any info hash it likes; this is the one place
  that actually enforces the boundary regardless of what arrives on the wire.

## `usesLsd` and the CDI-circularity constraint

`TorrentView` (the REST/WebSocket DTO) exposes `usesLsd`, mirroring `usesDht`. The natural
implementation — ask `TorrentEngine` whether LSD is active, the same way `usesDht()` checks
`dhtNode != null` — doesn't work here: `TorrentEventListener` (`grimtorrenter-app`), the one
place `TorrentView.from()` is called on every live state-change broadcast, deliberately has **no**
`TorrentEngine` reference, to avoid a circular CDI dependency with `TorrentEngineProducer`
(which injects the listener to construct the engine in the first place).

Resolved by giving `TorrentSession` a plain, construction-time-snapshot `boolean lsdActive`
field (unlike `dhtNode`, not a live reference — LSD has no per-session scheduled work of its
own; it's entirely orchestrated by `TorrentEngine`'s `maintenanceScheduler`), threaded through
`create()`/`restoreAsync()`'s widest overloads the same way `dhtNode` already is, and set from
`TorrentEngine`'s own `lsdService != null` at each of its three session-construction call sites.
`TorrentSession.usesLsd()` then mirrors `usesDht()`'s exact shape
(`lsdActive && !metadata.isPrivate()`) fully self-contained, no engine reference needed.

## Settings and constructor wiring

`Settings` gains `lsdEnabled` (default `true`, matching `dhtEnabled`/`acceptIncomingConnections`)
and `lsdAnnounceIntervalSeconds` (default 300s, matching `dhtReannounceIntervalSeconds`/
`dhtRefreshIntervalSeconds`'s existing LAN/DHT-etiquette cadence) — both restart-required, same
shape as `dhtEnabled`/`dhtRefreshIntervalSeconds`: `LsdService` is a real socket resource created
once at `TorrentEngine` construction, and the announce interval drives a `maintenanceScheduler`
period that can't change mid-flight.

**`TorrentEngine`'s new `enableLsd` constructor parameter deliberately defaults to `false` in
every backward-compat overload**, not threaded through all of them the way `enableDht`/
`acceptIncomingConnections` were from the start. Those two are original constructor parameters
every one of this class's 50+ existing test call sites already passes explicitly; `enableLsd` is
new, and defaulting it to `true` in the pre-existing narrower overloads would have silently
started binding a real UDP multicast socket in every one of those tests. Only the widest
constructor (the one `TorrentEngineProducer` calls, passing `settings.lsdEnabled()`) exposes it.
Same "real, non-hermetic socket activity, opt-in only" reasoning `enableDht`/
`acceptIncomingConnections`'s own constructor Javadoc already documents.

## Service status and events

`TorrentEngine.serviceStatuses()` gains a third `"lsd"` row, reusing the existing
`serviceState(boolean running, boolean failed)` helper verbatim — no `DEGRADED` concept needed,
since LSD binds once at construction like the peer server, not incrementally like DHT's routing
table ([[0059-service-status]]). A bind failure is caught, logged, and recorded as a new
engine-wide `EventType.LSD_UNAVAILABLE` library event (`infoHash`/`torrentName` both null, fires
at most once per process lifetime), the same pattern as `DHT_UNAVAILABLE`/
`PEER_SERVER_UNAVAILABLE` ([[0059-service-status]]).

## Frontend

- Trackers tab's peer-sources line becomes the guide's full `[DHT] · [PeX] · [LSD]`
  ([[0032-style-guide-and-primeng-theme]] originally dropped `[LSD]` specifically because it
  didn't exist yet).
- Services page gets a third row via the existing generic `SERVICE_DISPLAY` map — no template
  change needed, that page already renders whatever `GET /api/system/services` returns.
- Settings page's Network group gets a toggle + interval row, same control shapes as the
  existing DHT rows ([[0045-settings-page]]'s four control shapes).

## Stability (per [[0051-stability-as-a-standing-consideration]])

- **Resource bounds**: one bounded `MulticastSocket`, one receive-loop virtual thread. No
  retained per-peer state of its own — a decoded announcement is forwarded once (to
  `TorrentSession.addKnownPeers()`, whose own `knownAddresses` set is an existing, pre-shared
  concern with DHT/PEX/tracker discovery, not a new one this feature introduces) and then
  discarded.
- **Hostile-LAN-peer angle**: a malformed announcement (bad first line, missing headers,
  non-hex info hash) is caught in `LsdCodec.decode()` and dropped without killing the receive
  loop (`LsdServiceTest` covers this directly). No new rate-limiting on the receive loop beyond
  what `DhtNode`'s equally-unthrottled KRPC receive loop already accepts as precedent — LSD is
  LAN-scoped by construction (multicast doesn't route off-subnet), which meaningfully narrows
  the real-world abuse surface compared to DHT's open-internet exposure.
- **Concurrency**: one virtual thread for the receive loop; announces dispatched via the shared
  `maintenanceScheduler` onto their own virtual thread, same "don't block the shared scheduler
  thread" reasoning as `refreshDhtRoutingTable()`. No locks — the outbound-interface-fixed-at-
  construction choice above exists specifically to avoid needing one. Matches
  [[0007-concurrency-model]]'s no-`synchronized`-in-the-hot-path rule (nothing here uses it).
- **Cleanup**: `LsdService.close()` is wired into `TorrentEngine.shutdown()` alongside
  `dhtNode`/`peerServer`, on every exit path that already closes those two.

## Addendum: a freshly-activated torrent doesn't wait for the periodic sweep (2026-09-09)

Caught during review, prompted by the user asking whether this implementation repeated a real
mistake DHT/tracker discovery already hit and fixed. It did: the original design above described
`announceViaLsd()`'s `maintenanceScheduler`-driven sweep (fixed `lsdAnnounceIntervalSeconds`
period, default 300s, anchored to `TorrentEngine`'s own construction time) as the *only* LSD
announce path. That repeats exactly the class of gap [[0036-dht-backstop-for-tracker-bearing-torrents]]'s
own 2026-09-06 revision already found and fixed for DHT: `discoverPeersViaDht()` got a
zero-initial-delay schedule specifically because "a freshly-started torrent shouldn't wait a full
`dhtReannounceIntervalSeconds` for its first DHT lookup." The tracker side avoids the same problem
structurally - `TorrentSession.start()`'s own synchronous first announce happens before any
periodic `reannounce()` is even scheduled. LSD had neither mechanism: a torrent added, resumed, or
restored well after engine construction would sit for up to 5 minutes before its first LSD
announce - and since LSD is engine-wide rather than per-session, this wasn't even anchored to any
individual torrent's own activation moment the way DHT's per-session schedule was.

**Fixed with a `TorrentSessionListener` decorator**, `TorrentEngine.announceOnLsdActivation()`,
wrapping the caller-supplied listener at construction (`this.listener = announceOnLsdActivation(listener)`)
rather than adding explicit calls at `TorrentEngine`'s own `addTorrent()`/`resumeTorrent()` call
sites. That distinction matters: `TorrentSession.verifyThenSettle()`'s background-thread autoStart
(used by both `addTorrent()`'s reused-directory branch and, critically, `restoreOne()` - **every
torrent restored at engine startup, the ordinary case on every container restart**) transitions a
session into DOWNLOADING/SEEDING on a thread `TorrentEngine` doesn't otherwise see or synchronize
with. `onStateChanged()` is the one signal that fires from all five activation paths uniformly.
On any `!isDownloadingOrSeeding(oldState) && isDownloadingOrSeeding(newState)` transition (for a
non-private torrent, same BEP 27 gate as everywhere else in this doc), the decorator dispatches a
single-info-hash `lsdService.announce()` on its own virtual thread - the periodic sweep remains as
the ongoing safety-net re-announce, now genuinely analogous to `reannounce()`/
`discoverPeersViaDht()`'s own steady-state role rather than carrying the "first announce" job it
was never suited for.

New regression test: `TorrentEngineTest.addingATorrentAnnouncesItViaLsdImmediatelyRatherThanWaitingForThePeriodicSweep()`
- a second, independent real `LsdService` (standing in for a second LAN client) confirms the
announce arrives within 2 seconds of `addTorrent()`, not after any multi-minute wait. Real,
non-hermetic multicast socket activity, same accepted precedent as this project's existing DHT
bootstrap tests and `LsdServiceTest`'s own loopback tests.

**Follow-up, found the hard way when `mvn test` actually ran this on a real build machine**: the
test above originally used `LsdService`'s auto-detecting constructor on *both* ends (the engine's
own internal service and the test's stand-in second client), sending real multicast over
whichever physical interface `NetworkInterface.getNetworkInterfaces()` picked first. It failed -
not because the fix above is wrong, but because a real physical interface is exactly the kind of
thing a local firewall or switch can silently swallow multicast traffic on, even between two
sockets on the same host; loopback doesn't have that problem, since the kernel always delivers on
it locally regardless of anything in between - the same reason `LsdServiceTest` deliberately used
loopback from the start. Fixed by widening `LsdService`'s interface-list constructor from
package-private to **public** (a reasonable, low-risk widening - it only takes a standard JDK
`List<NetworkInterface>`) and adding a package-private, test-only `TorrentEngine` constructor
overload (`lsdInterfacesForTesting`, `null` meaning "auto-detect," same as production) so the
test can pin *both* ends to loopback deterministically. Same "package-private, purely for test
access" precedent this codebase already uses elsewhere (`TorrentEngine.dhtNode()`,
`checkSeedingLimits()`).

## Alternatives considered

- **Threading `enableLsd` through every backward-compat `TorrentEngine` overload** (matching
  `enableDht`/`acceptIncomingConnections` exactly) — rejected: those two are original
  constructor parameters, not a later addition: every existing test call site already opts in
  explicitly. Silently defaulting a *new* real-socket feature to on across 50+ pre-existing test
  call sites (as itemized above) would be a real, if narrow, correctness/flakiness risk for no
  benefit — those tests don't exercise LSD anyway.
- **Computing `usesLsd` from `TorrentEngine` directly at `TorrentView`-assembly time** —
  rejected outright: `TorrentEventListener` has no `TorrentEngine` reference by design (see
  "CDI-circularity constraint" above), so this isn't reachable from the one call site that
  matters most (every live WebSocket state-change broadcast).
- **Per-send `setNetworkInterface()` for genuine multi-NIC outbound fan-out** — rejected as
  unnecessary complexity/risk for a feature already scoped as a minor LAN-only contributor; see
  "Interface handling" above.
- **Explicit immediate-announce calls at `TorrentEngine`'s own `addTorrent()`/`resumeTorrent()`
  call sites**, instead of the `TorrentSessionListener` decorator (2026-09-09 addendum) —
  rejected: misses `TorrentSession.verifyThenSettle()`'s background-thread autoStart entirely
  (both `addTorrent()`'s reused-directory branch and `restoreOne()`, the latter being every
  torrent restored at engine startup), a real, common case rather than an edge case.
