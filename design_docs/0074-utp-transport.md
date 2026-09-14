# 0074 — µTP transport (BEP 29)

**Status:** Accepted (full commitment, sliced delivery)

## Decision

Picked from `PROGRESS.md`'s suggested-next-steps / `TODO.md`'s µTP item: the peer connection
layer has been TCP-only since [[0015-peer-connection]], never a deliberate decision. This is new
scope beyond [[0009-phased-scope]]'s original three phases - every item on that list is now
built, so this is framed as standalone scope, not "the next Phase N item."

**A full cost/benefit review was done with the user before committing** (not just a scoping
pass): DHT ([[0028-magnet-links-and-dht]]) is the closest precedent in size, and it took ~7
independently-shipped slices plus several post-ship bug-fix rounds for a protocol simpler than
µTP (stateless-ish request/response, no congestion control, no retransmission timers). µTP
requires reimplementing a meaningful slice of what TCP does in kernel space - sequence numbers,
cumulative + selective ACK, RTT estimation, a retransmission timer, a congestion window, LEDBAT's
delay-based control law on top of that - by hand, in user space. It also introduces a genuinely
new resource-accounting shape this codebase hasn't had before: [[0007-concurrency-model]] is
"ordinary blocking I/O on a virtual thread, no timers," and per-connection retransmission/
congestion-window state doesn't fit that model as-is. The measured benefit is real but modest -
`TODO.md`'s own framing says µTP-only peers "shrink the effective peer pool *somewhat*," and this
project's own peer/seed-count investigation (2026-09-06) found DHT-concurrency and
connection-refill bugs mattered far more in practice than transport type. The citizenship half of
the benefit (not saturating a shared connection) is also already partially covered today by the
existing manual rate limits ([[0042-rate-limiting]]) - LEDBAT is a nicer, adaptive version of the
same goal, not the only lever for it. **Confirmed with the user regardless: full commitment,
sliced delivery** - the completeness/citizenship benefit is worth the cost, but the size means
this ships in independently-landable slices, the same way DHT did, not as one drop.

**Hand-rolled, no new dependency** - `grimtorrenter-engine` has a standing zero-production-
dependency rule ([[0005-module-structure]]), and there's no known maintained pure-Java µTP
library (the reference implementations are C/C++, e.g. libutp). Same reasoning
[[0052-message-stream-encryption]] already used for hand-rolling DH+RC4 rather than pulling in a
crypto library.

**Disabled by default until real LEDBAT ships (slice 5) - then enabled by default for new
installs.** Wire-level interoperability (does a real client even attempt/accept a connection) was
fully gated on slices 1-4 being correct regardless of congestion-control sophistication - a
partially-compliant wire format gets zero interop benefit, there's no "mostly speaks µTP" middle
ground. Shipping an interim, unsophisticated congestion window as the *default* would have risked
the exact bufferbloat/unfriendliness problem µTP exists to solve, before the part that actually
solves it had landed. `Settings.utpEnabled` (restart-required like `dhtEnabled`/`lsdEnabled`) let
an interested user opt in early once slice 4 was testable, without exposing every user to an
unpolished congestion window by default. **Now that slice 5's real LEDBAT has landed, confirmed
with the user: `Settings.defaults()` sets `utpEnabled=true`** - see slice 5's own implementation
notes below for why this could only be done at that one factory method, not via the compact
constructor's usual absent-vs-explicit normalization (a primitive `boolean`, unlike `lsdEnabled`'s
boxed one), and why that means only genuinely new installs pick it up - any existing install's
already-persisted `settings.json` keeps `false` regardless.

**Outbound strategy: µTP first, TCP fallback on timeout** - confirmed with the user, matching
real clients (µTorrent/libtorrent both default to preferring µTP for new outbound connections).
Peer discovery never reveals transport support ([[0066-peer-diagnostics]]/this doc's own
"Peer discovery needs no changes" section below), so the connecting side has to find out by
trying - a bounded µTP connection timeout before falling back to TCP, not a race (see
Alternatives).

## Peer discovery needs no changes

Confirmed by inspection: `PeerAddress` (`tracker/PeerAddress.java`) is `record
PeerAddress(InetAddress address, int port)` - no transport field, nothing to add. Every producer
(`MultiTrackerClient`, DHT's `CompactPeers`/`PeerLookup`, PEX's `PexCodec`) constructs the exact
same shape. This matches real BitTorrent behavior: transport is a per-connection-attempt choice
the *connecting* client makes, never a property of peer discovery - there is no separate "µTP
peer list" in any real client either.

## Wire format (BEP 29)

New `utp` package, mirroring `dht`'s per-concept file layout. 20-byte fixed header, big-endian:

```
byte 0:    type (high nibble) | version (low nibble, always 1)
byte 1:    extension (0 = none, 1 = selective-ack extension follows)
bytes 2-3: connection_id (uint16)
bytes 4-7: timestamp_microseconds (uint32)
bytes 8-11: timestamp_difference_microseconds (uint32)
bytes 12-15: wnd_size (uint32, receive window in bytes)
bytes 16-17: seq_nr (uint16)
bytes 18-19: ack_nr (uint16)
[extension blocks, if extension != 0: next_extension(1) | len(1) | len bytes]
[payload, ST_DATA only]
```

`type` values: `ST_DATA=0`, `ST_FIN=1`, `ST_STATE=2`, `ST_RESET=3`, `ST_SYN=4`.

**Connection IDs are asymmetric, not shared** - the single most easily-missed detail in the spec,
called out explicitly so slice 1's tests catch a swap immediately: the initiator picks one random
`recv_id`; it *sends* every packet (including the `ST_SYN`) with `connection_id = recv_id`, and
*expects* every reply addressed with `connection_id = recv_id + 1`. The acceptor does the mirror
image - it learns `recv_id` from the incoming `ST_SYN`'s `connection_id` field, then sends every
reply with `connection_id = recv_id + 1` and expects further incoming packets addressed with
`recv_id`. Two directions, two adjacent IDs, never the same value in both directions.

**`timestamp_difference_microseconds` is the entire LEDBAT delay-measurement mechanism** - filled
in by whichever side is *sending*, as "how much one-way delay did I observe on the last packet I
received from the other side" (`my_receive_time - their_timestamp_microseconds`), not the elapsed
RTT. This needs no clock synchronization between peers since only the difference is ever used,
never the absolute value - worth documenting clearly since it's easy to conflate with an RTT
sample at a glance.

`ack_nr` is a cumulative ACK (matches TCP: "I have everything up to and including this sequence
number, in order") - the selective-ack extension (type 1, a bitmask of which packets *beyond*
`ack_nr + 2` have also been received out of order) is deferred past slice 1 (see Slices below).

## Slices

Same "land independently, each one testable on its own" shape [[0028-magnet-links-and-dht]]
established, not one large drop.

1. **Wire codec + core reliable-delivery state machine.** `UtpPacket`/`UtpPacketType`/
   `UtpPacketCodec` (encode/decode, mirroring `KrpcCodec`'s role), and a `UtpSocket` class
   implementing the connection state machine - handshake (`ST_SYN`/`ST_STATE`), in-order
   delivery via `seq_nr`/`ack_nr` tracking, a basic RTO-based retransmission timer (Jacobson/
   Karels-style SRTT/RTTVAR estimation, no selective-ack yet - a lost packet stalls delivery
   until its own retransmission fires, same as TCP without SACK), and a simple, deliberately
   unsophisticated flow/congestion window (see Interim congestion control below) - just enough
   to move bytes reliably, not yet LEDBAT. Entirely standalone: two `UtpSocket`s talking over a
   real loopback `DatagramSocket` pair, no `TorrentSession`/`PeerConnection`/`DhtNode` involved.
   Packet-loss/reordering behavior tested via a thin `DatagramSocket`-wrapping test double that
   can drop/reorder/delay packets deterministically - this codebase has no existing precedent
   for that kind of harness, so it's new test infrastructure, not a reuse.
2. **`PeerConnection` transport facade.** `PeerConnection` is `Socket`-typed throughout every
   factory signature, and uses four `Socket`-specific calls beyond the `InputStream`/
   `OutputStream` pair it already treats independently: `setSoTimeout`, `close`,
   `getInetAddress`, `getPort`. A small facade interface exposing just those four operations
   (`PeerTransport` or similar - exact name decided when this slice starts) lets a real `Socket`
   and a new `UtpSocket` both satisfy it, so `PeerConnection`'s constructor/factories change once,
   behind the same "add a sibling overload, touch zero existing call sites" pattern already used
   repeatedly in this codebase.
3. **Inbound wiring.** `DhtNode.receiveLoop`/`handlePacket` today assumes every datagram on the
   shared UDP socket is KRPC (a bencoded dict, first byte `'d'`/`0x64`) - it needs a branch ahead
   of `KrpcCodec.decode` that recognizes a µTP packet by its first byte (the type/version nibble
   pair can never equal `0x64`) and routes it into a µTP connection-acceptance path instead,
   landing accepted connections through the same info-hash-routing `PeerServer.handleConnection`
   already does for inbound TCP. µTP shares DHT's existing socket/port - it does not open a
   second one.
4. **Outbound wiring.** `TorrentSession.attemptConnect()` (today's one and only outbound
   `PeerConnection.connect()` call site) gains the µTP-first/TCP-fallback strategy confirmed
   above, gated on `Settings.utpEnabled`.
5. **Real LEDBAT.** Replaces slice 1's interim window with RFC 6817's actual delay-based control
   law (base-delay tracking over a sliding window, target queuing delay, proportional gain,
   faster-than-growth backoff so it yields to competing TCP flows) - the point at which
   `utpEnabled` becomes reasonable to default on. Selective-ack support is a natural companion
   here (loss recovery gets meaningfully better with it), not a hard requirement to reach parity
   with slices 1-4's own correctness.

### Interim congestion control (slices 1-4)

Not real LEDBAT - a fixed, conservative send-window cap (sized modestly, e.g. a handful of
packets in flight) with no delay-based adjustment at all. Correct and interoperable (any real
peer sees valid `wnd_size`/`ack_nr` semantics regardless of how conservatively the sending side
chooses to use its own window), just not yet a polite, adaptive citizen on a shared connection -
exactly why `utpEnabled` defaults off until slice 5.

## Slice 1 implementation notes (2026-09-13)

Built: `UtpPacketType`/`UtpPacket`/`UtpPacketCodec`, and `UtpSocket` per the shapes above.
`mvn test` caught two real bugs, both fixed same-day - exactly the kind of thing this project's
own "confirm before implementing, verify after" pattern exists to catch:

- **The connection-ID asymmetry was backwards in `connect()`'s handshake completion** - the
  single easiest detail to get wrong, and it was gotten wrong. The initiator's own future
  packets (every DATA/ACK/FIN after the handshake) must keep using the *same* `recvId` the SYN
  itself used, not `sendId` (`recvId + 1`) - `connect()`'s final `new UtpSocket(...)` call passed
  `(sendId, recvId, ...)` where the constructor expects `(sendConnectionId, receiveConnectionId,
  ...)`, i.e. exactly swapped. The effect: every packet the initiator sent post-handshake carried
  the *acceptor's* connection ID, which the acceptor's own `receiveConnectionId` check silently
  rejected (`receiveLoop`'s `if (packet.connectionId() != receiveConnectionId) continue;`) -
  every data transfer test timed out waiting for data that was being sent but never accepted,
  and `connectionIdsAreAsymmetricInBothDirections` caught the off-by-one directly. `accept()`'s
  own assignment was correct throughout - only `connect()`'s had the swap.
- **`UtpPacket`'s generated `equals()`/`hashCode()` used reference identity for the `payload`
  field** - a `byte[]` record component doesn't get `Arrays.equals()` for free from the
  compiler-generated methods, so two packets decoded from identical wire bytes (or a hand-built
  packet compared against a freshly-decoded one, exactly what `UtpPacketCodecTest`'s own
  round-trip assertions do) compared unequal despite having identical content. Fixed with an
  explicit `equals()`/`hashCode()` override using `Arrays.equals()`/`Arrays.hashCode()` for
  `payload` alongside ordinary equality for every other field - a well-known Java records gotcha
  worth watching for in any future record with an array component in this codebase.

## Slice 2 implementation notes (2026-09-13)

Built the `PeerTransport` facade exactly as scoped, plus one piece the original slice-2 text
above didn't spell out (it only names the four `Socket`-specific operations, not how streams get
adapted): `UtpSocket` gained `getInputStream()`/`getOutputStream()` (`UtpInputStream`/
`UtpOutputStream`, mirroring `mse`'s `Rc4InputStream`/`Rc4OutputStream` as separate top-level
classes), `remoteAddress()`, and `setReceiveTimeoutMillis()` - the last one exists because `µTP`
has no equivalent of a TCP socket's own "peer is gone" read failure, so without it a silently-dead
µTP peer would hang `PeerConnection`'s read loop forever instead of tripping its existing
`IDLE_READ_TIMEOUT_MS`/`HANDSHAKE_TIMEOUT_MS` logic the way a real `Socket` timeout already does.
Default `receiveTimeoutMillis = 0` (block forever) keeps every slice 1 test passing unmodified.

`PeerTransport`/`SocketPeerTransport`/`UtpPeerTransport` all live in the `peer` package, not
`utp` - `UtpSocket` never references `PeerTransport` itself, so the dependency runs one way
(`peer` → `utp`), matching how `peer` already depends on `mse`/`peerwire` and never the reverse.

Added package-private `PeerConnection.connectViaUtp()`/`acceptViaUtp()` beyond what the slice
strictly required, specifically so `PeerConnectionUtpTransportTest` could prove the facade
carries a *real* BT handshake and a real wire message over a *real* `UtpSocket` pair - not just
that everything compiles. Not a production entry point - slices 3-4 decide the real public shape
once `TorrentSession`/`DhtNode` actually need one.

Every existing `PeerConnection`/`PeerConnectionTest` signature and behavior is unchanged; the
only observable-to-existing-callers difference is internal (`Socket` field → `PeerTransport`
field). One real bug caught during review, not by a test run: `connectPlaintext()`'s refactor
initially called `socket.getInputStream()`/`getOutputStream()` *outside* the try/catch that
handles `socket.connect()` failures, which would have leaked the socket on the (rare) case those
two calls themselves throw - the original code had them inside the same try block. Fixed by
keeping them there before handing off to the new shared `completeOutboundHandshake()` helper.

`mvn test` caught one real flake in `PeerConnectionUtpTransportTest` itself (not in the
production code) on the very first run: unlike every existing `PeerConnectionTest` case, where
the fake-peer side hand-writes `Handshake.of()` and so never advertises BEP 10 extension-protocol
support, this test's *both* sides are real `PeerConnection`s built via `Handshake.
withExtensionProtocol()` - each automatically fires its own `Extended(0, ...)` handshake message
right after the BT handshake completes (`sendExtendedHandshakeIfSupported()`), racing with the
test's own explicit `Interested` message. The test originally assumed `Interested` would be the
first (and only) message received - fixed by polling for that specific message's presence
instead (mirroring `PeerConnectionTest.awaitRemoteExtensionId()`'s own existing idiom), rather
than assuming anything about order or count.

## Slice 3 implementation notes (2026-09-13)

Built inbound wiring exactly as scoped, plus one real design gap the original slice 3 text above
didn't anticipate: `IncomingConnectionHandler`/`TorrentSession.acceptIncomingConnection` are both
`Socket`-typed (not just stream-typed) - `acceptIncomingConnection` calls `socket.close()`
directly. A µTP connection has no real `Socket` backing it, so rather than force-fitting one, this
slice added a parallel, µTP-shaped path end to end: `UtpIncomingConnectionHandler` (interface,
mirrors `IncomingConnectionHandler`), `UtpPeerAcceptor` (mirrors `PeerServer.handleConnection`'s
handshake-read-then-route logic, `peer` package), and `TorrentSession.acceptIncomingUtpConnection`
(mirrors `acceptIncomingConnection`) - three new siblings, zero changes to the existing TCP path.

The other piece slice 1 explicitly deferred ("that sharing is slice 3's own design work") - many
`UtpSocket` connections sharing `DhtNode`'s single `DatagramSocket`, demuxed by connection id -
turned into `UtpSocket` gaining a second mode (`ownsSocket=false`, entered via the new
`acceptShared()`) alongside its original self-contained one: no receive loop of its own, no
`socket.close()` on close, fed packets externally via the new `deliverIncoming()`. The demuxer
itself (`UtpAcceptor`, new, `utp` package) owns the registry (keyed by remote address + connection
id, since a 16-bit id alone isn't unique across different remote peers) and is the only thing that
ever calls `socket.receive()` - `DhtNode.handlePacket()` routes to it by first-byte shape
(`UtpPacketCodec.looksLikeUtpPacket()`) ahead of its existing `KrpcCodec.decode()` call, so KRPC
and µTP genuinely coexist on the one socket/port, proved end to end by `DhtNodeUtpTest`.

**Confirmed with the user, ahead of the original schedule**: `Settings.utpEnabled` (default
`false`, restart-required) was introduced in this slice, not deferred to slice 4 as this doc's
prose originally implied - the "not yet a polite citizen on a shared connection" congestion-control
risk this doc's Decision section raises applies identically to accepting an inbound connection as
to initiating an outbound one, and real peers that already prefer µTP (µTorrent/libtorrent) would
otherwise complete inbound connections against this codebase the moment slice 3 shipped, with no
way for a user to opt out early. `TorrentEngine` wires `DhtNode`'s µTP-accept callback to either a
real `UtpPeerAcceptor` or `UtpSocket::close` (reject everything) depending on
`acceptIncomingConnections && settings.utpEnabled()` - the same reject-everything closure is also
`DhtNode`'s own two-arg constructor's default, so every pre-slice-3 caller (~30 existing tests)
is unaffected.

**Also a stated simplification, not an oversight**: `UtpPeerAcceptor` skips PeerServer's
plaintext-vs-MSE branch entirely and always reads a plain BT handshake - µTP traffic already
looks like ordinary UDP to DPI (MSE's whole point), so real clients (e.g. libtorrent) do the same.

`mvn test` passed cleanly on the first run for this slice - no bugs found once written, unlike
slices 1-2's respective connection-ID-swap/record-equals and test-race findings.

## Slice 4 implementation notes (2026-09-14)

Built outbound wiring exactly as scoped: `TorrentSession.attemptConnect()` now tries µTP first
(via a new private `connect()`/`connectViaUtp()` pair) and falls back to plain TCP on failure,
gated on the same `Settings.utpEnabled` slice 3 introduced. No MSE over µTP, matching slice 3's
inbound precedent.

**One real addition beyond the original slice 4 text, per the user's own request mid-session**:
the µTP leg's own connect timeout is a new user-configurable `Settings.utpConnectTimeoutSeconds`
(default 2s, genuinely live - read fresh on every attempt), not a hardcoded constant. This
mattered because `UtpSocket`'s existing general-purpose handshake budget
(`HANDSHAKE_MAX_RETRIES`/`HANDSHAKE_RETRY_INTERVAL_MILLIS`) is ~5-6 seconds worst case - fine for
a connection that's expected to eventually succeed, but far too slow for "try µTP, then quickly
give TCP a chance" given most peers today aren't µTP-capable at all. `UtpSocket` gained a new
`connect(socket, address, Duration timeout)` overload (retry *count* derived from the timeout at
the existing fixed retry interval - no second independent timing knob), used only by
`TorrentSession`'s own fallback path; the general-purpose 2-arg `connect()` and the
package-private wraparound-test seam are both unchanged.

`utpEnabled` itself stayed a plain, once-captured-at-construction boolean (not a `Supplier`, unlike
`utpConnectTimeoutSeconds`) - deliberately, to keep this one setting's liveness semantics uniform
between its inbound (slice 3, structurally restart-required since `DhtNode`'s acceptor is wired at
`TorrentEngine` construction) and outbound (this slice, which has no structural restart
requirement of its own) uses, rather than have it behave differently depending on direction.

`mvn test` passed cleanly on the first run for this slice too - no bugs found, matching slice 3's
own experience (slices 1-2's bugs were both found here; the wiring slices 3-4 haven't reproduced
that pattern, plausibly because they mostly compose already-tested slice 1-2 building blocks
rather than introducing new protocol-level state machinery).

## Slice 5 implementation notes (2026-09-14)

Built real LEDBAT exactly as scoped: a new, standalone `LedbatCongestionControl` (RFC 6817
section 3.3's control law verbatim - base-delay tracking over a 2-minute sliding window, 100ms
target queuing delay, `cwnd += gain * off_target * bytes_newly_acked * MSS / cwnd`) replaces the
interim fixed 64KB send-window cap in `UtpSocket`. Loss reaction (RFC 6817 requires LEDBAT flows
react to real loss, not just delay) piggybacks on the one signal this implementation has - an RTO
firing - with a straightforward per-event halving, floored at one MSS. Deliberately deferred, per
this doc's own "not a hard requirement for parity" call on extras: selective-ack-driven loss
detection and any distinguished slow-start ramp phase (the control law alone still grows the
window, just linearly rather than exponentially - a real, intended part of LEDBAT's "less
aggressive than TCP" nature, not a missing feature).

**One small correctness addition beyond the interim window's own behavior**: `UtpSocket` now
also decodes and honors the *peer's* advertised receive window (BEP 29's `wnd_size` field) as an
additional cap alongside the local congestion window - decoded on every inbound packet since
slice 1, but never actually consulted before now. This side's own advertised window (the
`wnd_size` it sends) stays a fixed, generous value (`ADVERTISED_RECEIVE_WINDOW_BYTES`, renamed
from the old `WINDOW_CAP_BYTES` now that it's no longer doing double duty as the send-side cap
too) - this class still has no bounded receive buffer to advertise a real backpressure signal
for.

**Correction to slice 3's own implementation notes above**: that text implied
`Settings.utpEnabled`'s upgrade behavior worked the same way `lsdEnabled`'s boxed-`Boolean`
absent-vs-explicit-false distinction does. It doesn't - `utpEnabled` is a primitive `boolean`
(deliberately, since unlike `lsdEnabled` there was no prior true-default to preserve at the time),
so Jackson has no way to tell "field missing from an old settings.json" apart from "field present
and explicitly false" the way it can for a boxed field. This mattered once this slice needed to
flip `utpEnabled`'s default to `true` (confirmed with the user, now that real LEDBAT makes that
reasonable): the compact constructor has no normalization hook available for a primitive boolean,
so `Settings.defaults()` itself was changed to explicitly construct with `utpEnabled=true` instead
- every other default stays defined in exactly one place (the existing four-arg constructor
chain), reconstructed via record accessors with only that one field overridden. The practical
outcome is still exactly what a "new-installs-only default" should be: an *existing* install's
`settings.json` reads back `false` either way (missing field or explicit `false`), and only a
genuinely fresh file (via `defaults()`, never before persisted) picks up `true` - just via a
different mechanism than originally described.

`mvn test` passed cleanly on the first run for this slice too - no bugs found. This closes out
`design_docs/0074`'s original 5-slice plan in full.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Unbounded growth**: a `UtpSocket`'s retransmission-timer/congestion-window state is bounded
  per connection and cleaned up on close, same order of magnitude as an existing `PeerConnection`
  - no new per-connection growth *kind*, but real *new resource type* (timers) scaling with
  connection count (bounded today by `maxConnections`, [[0072-per-torrent-limits]]).
- **Hostile-peer angle, genuinely new**: a remote peer controls `timestamp_microseconds`/
  `timestamp_difference_microseconds` and `wnd_size` in every packet it sends - slice 1's RTO
  estimator and slice 5's LEDBAT delay estimator both need sane clamping against a peer sending
  wildly inconsistent timestamps (deliberately or via clock issues) to avoid a pathological RTO
  (too long: stalls; too short: retransmission storm) - this is a real, new validation surface
  DHT/MSE didn't have (KRPC has no timing fields; MSE's handshake is fixed-length DH exchange).
- **Concurrency model gap, addressed explicitly**: [[0007-concurrency-model]]'s "blocking I/O on
  a virtual thread, no timers" doesn't cover per-connection retransmission/keepalive timing as-is
  - resolved (decided when slice 1 starts, not yet built) as a virtual thread per `UtpSocket`
  blocking on a timeout-bounded wait (matching the existing "ordinary blocking-style code on a
  cheap virtual thread" spirit rather than introducing a shared `ScheduledExecutorService` this
  codebase has no other precedent for at the per-connection granularity) - worth its own explicit
  note when slice 1's own implementation notes are written, since it's a real deviation from
  every existing per-connection thread's pure read-loop shape.
- **Cleanup on every exit path**: a `UtpSocket`'s close (`ST_FIN` sent/received, `ST_RESET`, or a
  local error) must cancel its own retransmission wait/thread - deferred to slice 1's own
  implementation and testing, called out here so it isn't missed once real code exists to check
  it against.
- **`UtpAcceptor`'s registry, and a SYN-flood angle (slice 3)**: every accepted connection is
  removed from the map via `UtpSocket.setOnClosed()` the moment it closes, so a well-behaved
  connection's lifecycle doesn't leak an entry. An unsolicited flood of SYNs still costs one
  `UtpSocket` (two threads: retransmit loop + the one-shot accept-handoff thread) per distinct
  (address, connection id) pair before `UtpPeerAcceptor`'s own `HANDSHAKE_TIMEOUT_MS` closes
  anything that never follows up with a real BT handshake - no worse an exposure than
  `PeerServer.acceptLoop()` already has for a TCP SYN flood (one thread per accepted socket,
  same un-rate-limited accept path), not a new gap uTP specifically introduces. Gated off by
  default regardless (`Settings.utpEnabled`), so this exposure doesn't exist at all until a user
  opts in.
- **A fresh `DatagramSocket` per outbound attempt (slice 4)**: unlike inbound (shared with
  `DhtNode`'s one socket), each outbound µTP attempt opens its own ephemeral socket - exactly
  mirroring how outbound TCP already works (`Socket.connect()` also uses an OS-assigned ephemeral
  port, never the fixed listen port). Cleanup on every exit path: closed explicitly on a failed
  handshake (`TorrentSession.connectViaUtp()`'s own catch block), and transitively via
  `UtpSocket.close()` (its `ownsSocket=true` path) once the resulting `PeerConnection` closes on
  success - no path leaves the socket open with nothing left referencing it. Bounded by the same
  `connectionSlots`/`maxConnections` cap every other connection attempt already respects, so this
  isn't an unbounded-fan-out concern distinct from what already existed for TCP.

## Alternatives considered

- **Racing µTP and TCP concurrently** (this codebase's existing `invokeAny()` peer-racing
  precedent, [[0028-magnet-links-and-dht]]'s own addendum) - rejected (user decision) in favor of
  µTP-first-with-fallback: doubles the sockets opened per connection attempt for a latency win
  that matters less here than in the magnet-metadata-race case (that case was optimizing
  time-to-first-metadata under real user-visible latency; a peer connection attempt failing over
  after a bounded timeout is a background concern, not a blocking one).
- **Inbound-accept-only, deferring outbound entirely** - considered and rejected (user decision):
  full commitment includes both directions, sliced sequentially (inbound slice 3 before outbound
  slice 4) rather than dropping outbound altogether.
- **A third-party µTP library** - rejected; see the hand-rolled decision above.
- **Treating this as the next [[0009-phased-scope]] phase item** - rejected; that list's three
  phases are all already fully built, so this is framed as new, explicitly-scoped-beyond-0009
  work instead of a phase continuation.
