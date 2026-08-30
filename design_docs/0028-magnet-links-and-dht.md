# 0028 — Magnet links and Mainline DHT

**Status:** Done for trackerless-magnet support (magnet links are now fully
usable end-to-end, with or without embedded trackers) and DHT's protocol
layer in general. DHT as a peer-discovery backstop for a *regular*
(non-magnet) torrent whose trackers are all dead - deferred out of slice 6
above - is now also done; see [[0036-dht-backstop-for-tracker-bearing-torrents]].

## Decision

Phase 2's last two items ([[0009-phased-scope]]): magnet link support and
Mainline DHT (BEP 5). Confirmed build order with the user, since this is
large enough to span several slices:

1. Magnet URI parsing (this slice) - pure, standalone.
2. BEP 10 extension protocol on `PeerConnection` (the reserved-bit
   handshake flag + extended handshake message every later extension
   message rides on).
3. BEP 9 `ut_metadata` - fetch the info-dict from a peer in pieces, verify
   its SHA-1 against the magnet's info hash, decode into `TorrentMetadata`.
4. Wire into `TorrentEngine` (an `addMagnet` path that fetches metadata
   then hands off into the existing `addTorrent` pipeline unchanged) and
   the frontend (paste-a-magnet-URI control).
5. BEP 5 Mainline DHT - its own larger slice, deferred to last.
   Deliberately *not* first: most real magnet links embed tracker URLs
   (`&tr=`), so magnet support is usable end-to-end without DHT by relying
   on those, same as any other torrent's tracker(s). DHT then adds
   trackerless-magnet support and, as a side benefit, a peer-discovery
   backstop for any torrent whose trackers are dead - a real case already
   hit once, see [[0022-multi-tracker-fallback]]/[[0023-udp-tracker-client]].

**Metadata fetch will be a bounded concern that only produces a
`TorrentMetadata`, not a new `TorrentSession` mode.** Not yet built, but
worth recording now since it shapes the parsing decisions below: rather
than teaching `TorrentSession`/`PieceManager`/`TorrentStorage` to operate
without metadata up front, a separate small fetcher will speak BEP 10/9
to whichever peers it can reach, and only once it has a verified info-dict
does it call the same `TorrentEngine.addTorrent`-style path every other
torrent already uses. Keeps the entire existing download/seed/resume
pipeline untouched.

### Magnet URI parsing (`MagnetLink`, `grimtorrenter-engine/.../magnet/`)

- Only `xt` (must resolve to a v1 info hash via `urn:btih:`), `dn`, and
  `tr` (repeatable) are extracted. Everything else (`xl`, `as`, `xs`,
  `kt`, a v2 `xt=urn:btmh:...`, ...) is accepted but ignored rather than
  making the link unparseable - an unrecognized param doesn't mean the
  link is unusable, it means Phase 2 doesn't act on that particular
  param yet.
- **A magnet link may carry more than one `xt`** (e.g. a v1 `urn:btih:`
  alongside a v2 `urn:btmh:` on a hybrid link). The first usable
  `urn:btih:` wins; an unsupported `xt` namespace doesn't null out one
  already found. v2/hybrid torrents (BEP 52) aren't supported at all yet
  - only relevant here in that a hybrid magnet link should still work off
  its v1 half.
- **Both the 40-character hex and 32-character Base32 info-hash forms are
  supported** - both appear in the wild. Base32 decoding needed a small
  hand-rolled `Base32` (RFC 4648, decode-only, no padding handling needed
  since 20 bytes encodes to exactly 32 characters) since the JDK only
  ships `Base64`. Kept package-private to `magnet` - nothing else in the
  engine needs it.
- **The 40-character hex form is normalized to lowercase.** `InfoHash` is
  a plain string wrapper with `record`-generated (i.e. case-sensitive)
  `equals`/`hashCode`, and every other producer of one (`HexFormat.of()`,
  lowercase by default) is already lowercase - an uppercase magnet-derived
  `InfoHash` would silently fail to match the same torrent's `InfoHash`
  from a tracker/peer/session-map lookup elsewhere.
- **Values are decoded via `URLDecoder` (`application/x-www-form-urlencoded`),
  not a bare percent-decoder** - treats `+` as space, matching how magnet
  links are actually generated/consumed in practice, at the accepted cost
  of mishandling a literal `+` meant literally in a display name (a
  vanishingly rare case for a torrent name).

### BEP 10 extension protocol (`PeerConnection`, `peerwire`)

- **We now always advertise extension-protocol support** in our outgoing
  handshake (`Handshake.withExtensionProtocol`, setting reserved byte
  index 5's `0x10` bit - the "20th bit from the right" BEP 10 specifies).
  `Handshake.of` (all-zero reserved bytes) is kept as-is for tests and any
  future case that genuinely wants to advertise nothing.
- **A new `Extended` `PeerMessage`** (wire id 20) is a generic envelope:
  `(extendedMessageId, payload)`. `extendedMessageId == 0` is the extended
  handshake itself; any other value belongs to whatever specific extension
  negotiated that id. `PeerWireCodec` only encodes/decodes the envelope -
  it never interprets `payload` as bencode, keeping it consistent with
  every other message type there (pure wire format, no protocol
  semantics).
- **`PeerConnection` owns the handshake exchange, nothing more.** After
  the base 68-byte handshake, if (and only if) the *peer's* handshake
  advertised support, we send them our own extended handshake
  (`Extended(0, ...)`); an incoming `Extended(0, ...)` is intercepted
  inside `applyIncoming` (alongside the existing `Choke`/`Unchoke`/etc.
  bookkeeping) and decoded into `peerExtensions: Map<String, Integer>` -
  BEP 10's "m" dictionary, i.e. "send me extension X using this numeric
  id." Exposed as `remoteExtensionId(String)`. A malformed extended
  handshake is swallowed (leaves `peerExtensions` empty) rather than
  killing the connection - same tolerance-for-malformed-but-received-data
  philosophy as the rest of this layer.
- **Our own advertised "m" dictionary is empty for now** (`{"m": {}}`) -
  there's no concrete extension to advertise until BEP 9's `ut_metadata`
  lands in the next slice. Deliberately not parametrizing
  `PeerConnection.connect()` with a configurable extensions map yet:
  there's exactly one call site and exactly zero extensions to register
  today, so that's a change worth making once `ut_metadata` actually needs
  it, not speculatively now.
- **Every other `PeerMessage` case (`TorrentSession.handleMessage`,
  `PeerConnection.applyIncoming`, `PeerWireCodec`) had to add an `Extended`
  case** - `PeerMessage` is a sealed interface, so the compiler enforced
  this everywhere it's exhaustively switched over. `TorrentSession`'s case
  is a no-op for now: BEP 10 itself has no torrent-level meaning, only
  whatever's built on top of it does.

### BEP 9 ut_metadata fetch (`metadata` package)

- **`PeerConnection.connect()` now takes an `extensionsToAdvertise` map**
  (extension name -> the local id we want used when a peer sends *us*
  that extension) instead of the empty "m" dict hardcoded in the previous
  slice. Reopening that signature was deferred there specifically until
  something needed it - this is that something. `TorrentSession`'s one
  call site passes `Map.of()` (still nothing to advertise for a regular
  download); the new `MetadataFetcher` passes `{"ut_metadata": 1}`.
  `PeerConnection` still doesn't know the string `"ut_metadata"` means
  anything - the map is just data handed to it by the caller.
- **`PeerConnection` also now captures the extended handshake's top-level
  `metadata_size`** (previously only "m" was read), bundled with the
  extensions map into one `ExtendedHandshakeInfo` record behind a single
  volatile field - avoids a reader ever observing the new extensions map
  paired with a stale (or not-yet-set) metadata size from a torn update
  across two separate fields.
- **A new `UtMetadataCodec`** encodes/decodes BEP 9's three message types
  (`MetadataRequest`/`MetadataData`/`MetadataReject`, msg_type 0/1/2) -
  the layer above `PeerWireCodec`'s generic `Extended` envelope that
  actually understands what the bytes inside it mean for this one
  extension, mirroring the peerwire/`PeerMessage` split one level up.
  `MetadataData`'s bytes are a bencoded dict immediately followed by raw
  (undelimited) piece bytes, which needed `BencodeDecoder.decodePrefix()`
  - a new addition alongside the existing strict `decode()` - since
  nothing before this needed to decode "one bencoded value, then treat
  whatever's left as something else."
- **`MetadataFetcher.fetch(address, infoHash, ourPeerId)`** is a
  single-peer, single-attempt, blocking building block: connect, wait for
  the peer's extended handshake, confirm they support `ut_metadata` and
  reported a `metadata_size`, request each 16 KiB piece sequentially
  (no pipelining - a metadata exchange is small and one-shot, unlike a
  whole download), verify the assembled bytes' SHA-1 against the
  requested info hash, and return them. Trying multiple peers and
  deciding when to give up belongs to whatever wires this into
  `TorrentEngine` next, not this class - same separation `PeerConnection`
  itself already has from `TorrentSession`'s multi-peer swarm logic.
- **Every "data" message re-validates `total_size` and the block's
  length against what the handshake declared**, not just the final SHA-1
  - a malformed or adversarial peer gets a clear, specific failure
  (wrong-sized piece, inconsistent total size) rather than an
  `ArrayIndexOutOfBoundsException` from a bad offset.
- **A disconnect mid-fetch isn't specially short-circuited for a pending
  piece request** - it resolves as an ordinary timeout once no response
  arrives for the rest of that piece's wait window, rather than adding
  interrupt-based fast-path plumbing for what step 4's multi-peer retry
  logic will route around anyway.

### Engine + UI wiring (`TorrentEngine.addMagnet`, `TorrentResource`, frontend)

- **`addTorrent(byte[] torrentFileBytes)` is reused completely unchanged**
  for magnet-derived torrents, resume-record persistence included.
  Once `MetadataFetcher` returns a verified info-dict, it's wrapped back
  into a synthetic full top-level torrent-file byte array - `{"info":
  <the fetched dict>, "announce-list": <the magnet's own trackers, as one
  flat tier>}` - and handed to the exact same `addTorrent()` every
  `.torrent` upload already goes through. No new persistence format, no
  new session-construction path, no special-casing anywhere else in
  `TorrentEngine`/`TorrentSession`/`design_docs/0026`'s resume machinery
  - a magnet-derived torrent restores after a restart exactly like any
  other, because by the time it's persisted, it *is* any other.
- **A magnet's flat `tr=` list becomes a single announce-list tier**, not
  one tier per tracker - it has no BEP 12 tier structure to begin with,
  and `MultiTrackerClient`'s current fallback logic tries every tracker
  in list order regardless of tier boundaries anyway (tiers only matter
  for future refinements like per-tier shuffling, not implemented yet -
  see [[0022-multi-tracker-fallback]]), so inventing tier boundaries
  would imply structure that isn't there.
- **`addMagnet()` validates trackers synchronously** (throws
  `TorrentEngineException`, already mapped to 400, for a trackerless
  magnet) but does everything else - the tracker announce and the
  metadata fetch itself - on a background virtual thread, since it's
  unbounded real network I/O against an unknown number of peers before
  anything is known about the torrent at all.
- **Up to 8 peers are tried sequentially**, not concurrently, stopping at
  the first success. A concurrent "race all of them" approach would fail
  faster when most candidates are dead, but sequential is simply less
  code, and this can be revisited if real-world latency turns out to be
  a problem - not clear it will be, since most magnet links only need one
  peer that actually has the metadata. **Since superseded** - see this
  doc's own 2026-08-30 addendum: real-world evidence showed this wasn't
  trying enough peers, and the fix is now a concurrent, retried, live-
  tunable version of this same idea, not a different design.
- **A total failure (no tracker reachable, or none of the peers tried had
  the metadata) is only logged server-side, not surfaced to the UI.**
  Raised explicitly rather than silently decided: this is the same
  accepted add-to-visible latency/feedback gap already noted for a
  regular `.torrent` upload (see the `upload_latency_ux` memory note,
  deferred to the future visual design pass) - a magnet add can only ever
  be slower and less certain than a local file upload, so it inherits the
  same deferral rather than getting bespoke "pending" UI treatment now.
  Once metadata succeeds, the resulting torrent appears exactly like any
  other, through the existing snapshot/push mechanism. **Since superseded**
  - see `[[0060-magnet-add-failure-feedback]]`: a total failure now also
  records a `MAGNET_ADD_FAILED` library event, visible in the Events tab
  and used by the frontend to resolve its own optimistic pending row.
- **`POST /api/torrents/magnet`** takes a raw `text/plain` magnet URI
  and returns `204` (matching this resource's other action endpoints,
  e.g. pause/resume) rather than a `TorrentView` - there's nothing to
  return yet. A new `MagnetLinkExceptionMapper` (same one-liner pattern as
  `MetainfoExceptionMapper`) turns a malformed URI into `400`.
- **Frontend**: a `pInputText` + `p-button` pair next to the existing
  file-upload control, using Reactive Forms (`FormControl`) per this
  frontend's own convention over template-driven `ngModel`. Success
  clears the field and shows a toast acknowledging the paste was
  accepted (`"Fetching metadata from peers…"`) - since there's no
  torrent to show immediately, an explicit acknowledgment is what keeps
  the action from feeling like it silently did nothing, without needing
  the new "pending" state deferred above.

### BEP 5 Mainline DHT — KRPC message model and codec (`dht` package)

Confirmed with the user before starting: node id persists to disk across restarts
(BEP 5's own recommendation, and cheap to add via the same marker-file pattern
`TorrentEngine` already uses elsewhere); the DHT UDP socket reuses `ourListenPort`
rather than getting its own config property, matching real clients and what the
existing (so far inert) `Port` peerwire message already implies. Both land in a later
slice once there's a `DhtNode` to apply them to. This first slice is purely the wire
model: BEP 5's KRPC query/response/error envelope, bencoded over UDP, with no
networking yet - unit-testable the same way `UtMetadataCodec` is.

- **`NodeId`** is the same hex-string-backed shape as `InfoHash`/`PeerId` (fixed 20
  bytes, correct `equals`/`hashCode` for free, human-readable in logs) - not shared
  with them, since a DHT node identity is a distinct concept from a torrent's or a
  peer-wire client's.
- **Queries decode to fully typed records** (`Ping`/`FindNode`/`GetPeers`/
  `AnnouncePeer`, behind a sealed `KrpcQuery`) since a query's `"q"` field
  unambiguously says which one it is - no context needed beyond the bytes
  themselves, same reasoning that already types `UtMetadataMessage`'s three
  variants. `KrpcQuery` itself declares `id()` since every query carries the
  querying node's own id regardless of what else it asks for.
- **Responses decode to one generic `KrpcResponse(transactionId, BDictionary
  returnValues)`, not one record per query type.** Unlike queries, a response's
  shape can't always be told apart from its bytes alone - a `find_node` response
  and a peerless `get_peers` response are wire-identical apart from `get_peers`
  always adding a `"token"`. Only whichever query is still pending for that
  transaction id knows what shape to expect back, and that context belongs to
  whatever's tracking outstanding transactions (the `DhtNode` of a later slice),
  not the codec. Compact node-list (`"nodes"`) and compact peer-list (`"values"`)
  parsing is deferred to that same later slice for the same reason - not needed
  until something actually builds or reads a response's `returnValues`.
- **Transaction ids and `announce_peer`'s token are both modeled as `BString`**,
  not `byte[]` or a new hex-string wrapper - both are arbitrary-length opaque
  blobs (we choose our own outgoing transaction id length, but must echo back
  whatever length a peer sent on an incoming query, and a token is never
  interpreted, only ever echoed back verbatim), so neither fits `NodeId`/
  `InfoHash`'s fixed-20-byte assumption. Reusing `BString` sidesteps the
  byte-array-equals pitfall those two work around, for free, with no new type.
- **`KrpcCodec.decode` throws `KrpcException` for every malformed-input case it
  checks itself** (missing/wrong-type field, unknown `"y"`/`"q"` value, malformed
  `"e"` list), consistent with `UtMetadataCodec`. A nested `NodeId`/`InfoHash`
  construction failure (wrong length) is caught and rewrapped from
  `IllegalArgumentException` into `KrpcException` so decode only ever surfaces
  one exception type to callers.

### BEP 5 Mainline DHT — node id distance and the k-bucket routing table (`dht` package)

Still pure in-memory state, no networking - one step closer to a `DhtNode`, but nothing
yet that needs a socket.

- **`NodeId.distanceTo` returns a `BigInteger`** (the XOR of the two ids' bytes,
  interpreted as an unsigned magnitude) rather than a hand-rolled distance type -
  `BigInteger` gets correct ordering for free, and `bitLength()` conveniently *is* "the
  routing-table bucket index this distance falls into" (bucket *i* holds contacts at
  distance `[2^i, 2^(i+1))`, i.e. `bitLength() - 1`), so no separate bit-fiddling was
  needed to compute it.
- **`RoutingTable` is a fixed array of 160 buckets** (one per possible bit position of
  the highest differing bit, 0-159), each capped at BEP 5's k=8 contacts, rather than the
  splitting-tree refinement some Kademlia implementations use to keep only the buckets
  near our own id fine-grained. Rejected in favor of the fixed array: with an 8-per-bucket
  cap, 160 buckets is at most 1280 contacts total - trivial to hold as plain (mostly-empty)
  lists - so the splitting tree's extra bookkeeping (deciding which bucket is "on our own
  id's path" and eligible to split) buys nothing at this project's scale.
- **A full bucket doesn't decide eviction itself.** BEP 5's own policy - ping the bucket's
  least-recently-seen contact, and only evict it if that ping goes unanswered - needs a
  live socket, which this pure data structure doesn't have. `insert()` instead returns
  `Optional<NodeInfo>`: empty when the new contact was added or an existing one refreshed,
  or (bucket full, contact new) the least-recently-seen entry for the caller to ping.
  Whatever owns the network (`DhtNode`, next slice) decides what to do with that: refresh
  it via another `insert()` call if it answers, or `evict()` it and re-`insert()` the new
  contact if it doesn't. "Least-recently-seen" itself needs no explicit timestamp field on
  `NodeInfo` - each bucket is a `LinkedHashMap` with refresh-on-touch (remove then re-put),
  so insertion order alone is always oldest-first.
- **`NodeInfo`** (id + address + port) is introduced here, not in slice 1's KRPC codec,
  since a routing table's contacts are the first thing that actually needs it - slice 1
  deliberately deferred it (and BEP 5's compact node-info wire encoding) until something
  consumed it, per its own "responses stay generic until a caller needs to interpret them"
  reasoning.

### BEP 5 Mainline DHT — `DhtNode`, part 1: socket, transaction matching, ping/find_node (`dht` package)

Split slice 3 ("DhtNode: owns the UDP socket... and answers incoming queries... the server
half") into two parts once it became clear how much distinct new state the full server
side needs (a peer store, a token scheme) - this part covers everything except that:
socket ownership, matching an outgoing query to its response by transaction id (with
timeout), and answering `ping`/`find_node` queries from other nodes. `get_peers`/
`announce_peer` server handling is the next part.

- **One `DhtNode` per `TorrentEngine`, not per torrent** - unlike `PeerConnection`
  (one per peer connection, per torrent), the DHT routing table and its UDP socket are
  shared across every torrent. Still one virtual thread per unit of concurrent work
  though, per [[0007-concurrency-model]]: one dedicated receive-loop thread, written as
  ordinary blocking-style I/O, plus whichever caller threads are blocked in `ping`/
  `findNode` waiting on a `CompletableFuture`.
- **`ping`/`findNode` are typed convenience methods on `DhtNode` itself, not a generic
  `query(KrpcQuery)` that returns a raw `KrpcMessage`.** Revisits slice 1's own framing
  ("that context lives with the caller (the future DhtNode)") now that `DhtNode` exists:
  since it already knows exactly which query it's sending, it can just as well interpret
  the matching response's shape itself (pulling `"nodes"` from a find_node response, say),
  handing typed results (`List<NodeInfo>`) straight back rather than making every caller
  redo that work. This also sidesteps an awkward chicken-and-egg - a caller can't build a
  `Ping`/`FindNode` record itself since only `DhtNode` knows the transaction id it's about
  to reserve.
- **A single flat `DhtException`** covers timeout, a KRPC error reply, and a
  malformed/unexpected response shape - callers doing a lookup or a bucket refresh (later
  slices) treat all three identically ("this node didn't work, try another"), so a cause
  taxonomy would add ceremony nothing reads.
- **Compact node info encode/decode (`CompactNodes`, package-private) lands here**, not
  in slice 1 or 2 - this is the first place anything actually builds (`find_node`
  responses to incoming queries) or reads (`findNode`'s own return value) that wire
  format, matching slice 1's "deferred until a caller needs it" reasoning for exactly
  this. IPv4-only, matching every other compact-address format already in this codebase.
- **`insert()`'s "please ping this oldest contact before evicting it" signal (see slice
  2) is deliberately not acted on yet** - a full bucket simply stops accepting new
  contacts for now. Wiring up the real ping-and-replace policy needs nothing new
  structurally (the pieces - `ping()`, `RoutingTable#evict`- already exist), it's just
  not turned on until there's a concrete reason to (e.g. once bootstrapping/refresh in a
  later slice starts exercising the table enough for it to matter).
- **`get_peers`/`announce_peer` queries received from other nodes are silently ignored**
  (`buildResponse` returns `Optional.empty()`) - same tolerant handling as any query type
  this node doesn't recognize, until the next part adds real handling.
- **Every exception while handling one received packet is caught in one place**
  (`handlePacket`) and logged at DEBUG rather than allowed to propagate - a single
  malformed, adversarial, or (until the next part) unsupported-query packet must never
  kill the receive loop that every torrent using this shared node depends on.

### BEP 5 Mainline DHT — `DhtNode`, part 2: get_peers/announce_peer server handling (`dht` package)

Completes slice 3. Still server-side only - `DhtNode` sending its *own* get_peers/
announce_peer queries (what an actual peer lookup needs) is slice 5.

- **A rotating-secret token, not stored per-issued-token state.** `get_peers` hands back
  `SHA1(secret + requester's IP)`; `announce_peer` is only accepted if its token matches
  that same computation against either the current secret or the previous one (checked
  lazily - rotated in place the next time a token is issued or checked once
  `SECRET_ROTATION_INTERVAL` has passed, no background timer thread). Accepting the
  previous secret too avoids a token issued moments before a rotation going bad before a
  well-behaved client can even use it. This is BEP 5's own recommended scheme, and needs
  no storage or expiry of individual tokens - only two 20-byte secrets ever exist at once.
- **A bad or missing token gets a real KRPC error (203, "Bad token") back**, not a
  silent drop - the first case this node needs to *send* an error itself. A well-behaved
  remote client gets a chance to understand why and retry with a fresh `get_peers`,
  rather than an announce that silently never took effect.
- **The peer store (`Map<InfoHash, Map<PeerAddress, Instant>>`) expires entries lazily
  on read** (`PEER_EXPIRY`, 30 minutes - the widely-used mainline DHT convention), pruned
  as part of whatever `get_peers` query happens to read that info hash next, rather than
  a periodic sweep - no separate thread needed, and an info hash nobody's asking about
  costs nothing extra to leave stale in the map.
- **`get_peers`'s "no peers known" fallback treats the queried info hash as a point in
  the same 160-bit id space node ids occupy** (`NodeId.of(infoHash.bytes())`) and reuses
  `closestNodes` - standard Kademlia/BEP 5 convention (both are 20-byte SHA-1-shaped
  identifiers), and exactly the same "nodes" field `find_node` already returns.
- **`announce_peer`'s `impliedPort` uses the UDP packet's actual source port**, not
  its declared `port` field, when set - the whole point of that flag is a peer behind
  NAT that can't reliably self-report an externally reachable port.
- **The token secrets need no synchronization**, despite looking like shared mutable
  state - `issueToken`/`isValidToken` are only ever reached via `handleQuery`, which only
  ever runs on the single receive-loop thread. Called out explicitly in a comment since
  the lack of `synchronized`/`volatile` here would otherwise look like an oversight.
- **`buildResponse` now returns `KrpcMessage` directly instead of `Optional<KrpcMessage>`**
  - with all four `KrpcQuery` variants now genuinely handled (including returning a
  `KrpcError` for a bad token), there's no remaining case that means "silently drop this,"
  so the `Optional` from part 1 was no longer earning its complexity.

### BEP 5 Mainline DHT — iterative find_node lookup and bootstrap (`dht` package)

- **`NodeLookup` queries ALPHA (3) closest not-yet-queried candidates concurrently per
  round**, using `Executors.newVirtualThreadPerTaskExecutor()` - per [[0007-concurrency-model]],
  virtual threads make this no more code than a sequential loop would be, so there's no
  reason to accept the extra wall-clock latency sequential querying would add (unlike the
  deliberate sequential choice for magnet peer-fetch in an earlier slice above, which was
  a "stop at the first success" search, not an exhaustive one - a very different shape).
  Each round's futures are awaited before the next round starts, keeping "round" a real
  synchronous concept.
- **Exploration is bounded to roughly the current best-K window, not "every node ever
  mentioned."** `closestUnqueried(window, limit)` only ever considers the closest `window`
  (`RoutingTable.BUCKET_SIZE`, 8) entries of the shortlist when picking unqueried
  candidates - once every node in that window has been queried, the lookup stops,
  regardless of how many farther candidates the shortlist has accumulated along the way.
  This is a simplified stand-in for the Kademlia paper's more nuanced termination
  condition, plus a hard `MAX_ROUNDS` (20) safety cap against a pathological/adversarial
  network that keeps surfacing "new" candidates forever - both accepted as "correct
  enough, not textbook-perfect" for this project's scale.
- **A response's own sender is the only thing that reaches the routing table** (via
  `DhtNode`'s existing `seen()`, triggered by the query `NodeLookup` sends) - nodes merely
  *mentioned* in a response only become further lookup candidates in `NodeLookup`'s own
  shortlist, never routing-table entries on their own. Same hygiene rule slice 3 already
  established, just exercised for the first time by something other than a direct
  ping/find_node from outside.
- **`Bootstrap` pings each well-known host just to get it recorded as a direct contact**,
  then hands off entirely to one `NodeLookup.run(dhtNode, dhtNode.ourId(), ...)` call - a
  self-lookup is the standard way a Kademlia node fills in its whole table on startup,
  since the lookup naturally surfaces nodes across every distance band away from us, not
  just near the bootstrap hosts themselves. A `find_node` straight to each bootstrap host
  was considered and rejected: `NodeLookup`'s own first round re-queries them anyway
  (seeded from the routing table `seedFrom` just populated), so a separate find_node call
  would only add one redundant round-trip per host for no benefit.
- **A bootstrap host that fails to resolve or doesn't respond is logged and skipped, not
  fatal** - if every one fails (no network at all), the routing table simply stays empty
  and the lookup that follows immediately finds nothing to do, same as any other
  empty-table lookup.
- **`DhtNode.bootstrap()` is a one-line public wrapper around the package-private
  `Bootstrap`/`NodeLookup`** - both stay internal to `dht` (`NodeLookup` is expected to be
  reused by a future routing-table refresh and by slice 5's get_peers lookup, entirely
  within this package), so nothing outside it needs to know either exists.

### BEP 5 Mainline DHT — get_peers/announce_peer client side (`dht` package)

Completes the DHT protocol layer itself - `DhtNode` now both answers (slice 3) and makes
(this slice) all four KRPC query types. Only wiring this into `TorrentEngine` (slice 6)
remains.

- **`DhtNode.getPeers`/`announcePeer` are single-address client calls**, same shape as
  `ping`/`findNode` - the iterative, many-node search lives in a new `PeerLookup`, exactly
  the same split as `findNode` (single-hop, on `DhtNode`) vs `NodeLookup` (iterative).
- **`PeerLookup` isn't built on a shared base with `NodeLookup`**, despite both being
  "closest-unqueried, ALPHA at a time, bounded to the best-K window" - deliberately
  duplicated rather than factored out. The two collect meaningfully different things per
  response (one node id list vs. peers + tokens + a node id list), and with exactly two
  call sites, a shared generic skeleton would cost more to read than the ~15 lines of
  overlap it would save.
- **`PeerLookup.findPeers` also announces us to the closest nodes that responded, as part
  of the same call** - not a separate step a caller has to remember to invoke. Every one
  of those nodes' tokens only exist in memory as long as this lookup already has them in
  hand, and a real DHT client always does both together anyway (being findable via DHT
  requires having announced, and there's no reason to look up peers for a torrent without
  also making ourselves visible to future lookups for it).
- **Only nodes we actually queried ourselves are announce targets** - `PeerLookup`
  tracks a token per queried node id, and `announceToClosest` filters to exactly that set,
  never a node merely mentioned in someone else's response (the same hygiene rule
  `DhtNode`'s routing-table insertion already follows, applied here to "who can we
  legitimately announce to" instead).
- **A single failed announce_peer to one node doesn't affect the others or the peers
  already found** - each is independently best-effort, consistent with this whole layer's
  general tolerance for one bad/unreachable node not derailing everything else.
- **`CompactPeers.decode` lands here** (encode already existed, from slice 3b's
  get_peers/announce_peer server handling) - this is the first place anything needs to
  read a `"values"` field back out of a response, matching the same "add it when a caller
  needs it" pattern the rest of this DHT work has followed throughout.

### BEP 5 Mainline DHT — wiring into TorrentEngine (`engine`, `torrent`, `tracker`, `peer`, `peerwire` packages)

Completes DHT (slices 1-6). Scoped down from the original plan during this slice, confirmed
with the user: DHT as a peer-discovery *backstop for regular torrents whose trackers are
all dead* is deferred to a future slice - this one covers trackerless-magnet support (DHT's
originally-stated primary goal) and everything needed to get there.

- **DHT is opt-in via a new `boolean enableDht` constructor parameter on `TorrentEngine`**,
  not unconditional. The original 3-arg constructor is kept, unchanged, calling through
  with `enableDht=false` - every existing caller (production code and the whole existing
  test suite) is completely unaffected. This turned out to be load-bearing, not just
  tidiness: the naive version (DHT always on) broke the existing `TorrentEngineTest`/
  `TorrentEngineMagnetTest` suite two ways at once - many of those tests hardcode
  `ourListenPort=6881`, so multiple `TorrentEngine`s constructed across test methods would
  race to bind the same UDP port; and `DhtNode.bootstrap()` reaches out to the real
  internet, which a unit test suite should never do just by constructing an object.
  `grimtorrenter-app`'s `TorrentEngineProducer` now reads a new `grimtorrenter.dht-enabled`
  config property (default `true`) to decide; the test module's `application.properties`
  sets it to `false` for the same reason. **Superseded**: `dht-enabled` moved from a plain
  `@ConfigProperty` to the live `SettingsStore` - see [[0041-live-settings-store]]. The
  underlying "operator/user-toggleable rather than unconditional" reasoning is unchanged,
  only where the value now lives.
- **Node id persistence follows the exact same marker-file pattern `TorrentEngine`
  already uses** for everything else it persists (info hash, torrent bytes, run state) -
  a new `.grimtorrenter-dht-node-id` file, read on startup, generated and written once if
  absent or unreadable. `DhtNode` construction and bootstrap failure are both tolerated
  the same way (logged, left `null`/skipped) - DHT is confirmed-with-the-user to be a
  peer-discovery enhancement, never something a download's ability to start should depend
  on.
- **Trackerless-magnet support required teaching `TorrentSession` to run without any
  tracker at all**, not just wiring a DHT lookup into `addMagnet` - `selectTrackerTiers`
  previously treated "zero trackers declared" and "trackers declared but all unsupported"
  identically (both threw). Only the first case changes: it now returns an empty list,
  and a new `NoOpTrackerClient` (`tracker` package - announces always succeed with zero
  peers, interval a safely-bounded "practically never" rather than `Long.MAX_VALUE`, which
  overflows when the scheduler converts it to nanoseconds) stands in wherever a
  `TrackerClient` is otherwise required. "Trackers declared but all unsupported" still
  throws, unchanged - a torrent that came with tracker URLs none of which this client can
  talk to is a different, still-fatal situation.
- **`TorrentEngine.addTorrent` automatically seeds peers from DHT for *any* trackerless
  torrent** (`seedFromDhtIfTrackerless`, keyed off `trackerClient instanceof
  NoOpTrackerClient`) - not a special case bolted onto the magnet path only. This covers a
  gap that only became visible while implementing it: a DHT-magnet's synthesized torrent
  file has zero trackers, so *every* restore of it after a restart would otherwise start
  from zero peers forever, with no path back to DHT. Applying the seeding generically in
  `addTorrent`/`restoreOne` fixes that for free, and also means the magnet-add path itself
  needed no special-casing - `fetchMagnetMetadataViaDhtThenAdd` just calls the ordinary
  `addTorrent`, same as the tracker path already did.
- **`TorrentSession` gained one small public method, `addKnownPeers`**, rather than
  changing `create`'s signature to accept an initial peer list - keeps `addTorrent(byte[])`
  itself completely unchanged for every caller (preserving the "magnet-derived torrents go
  through the exact same pipeline" property from earlier in this doc), at the cost of a
  two-step create-then-seed call from `TorrentEngine` instead of a one-step constructor.
  Safe to call in any state; only actually attempts connections if the session is running.
- **The peerwire `Port` message is finally acted on** (previously modeled but always a
  no-op, since Phase 1): `TorrentSession` sends ours (`ourListenPort`, doubling as the DHT
  port) right after every handshake via a new `PeerConnection.sendPort`, and an incoming
  one is verified with a background, best-effort `dhtNode.ping()` before it can ever reach
  the routing table - the same "only trust nodes directly heard from" hygiene `DhtNode`'s
  own query handling already follows, now extended to a source outside the DHT wire
  protocol itself.
- **`PeerLookup`/`Bootstrap`/`NodeLookup` stay package-private; `DhtNode` gained one more
  thin public wrapper, `findPeers`**, exactly the same pattern `bootstrap()` already
  established - `TorrentEngine` lives in a different package and can only ever see
  `dht`'s public surface, which stays deliberately just `DhtNode` (plus the small value
  types its methods return/accept: `NodeId`, `NodeInfo`, `DhtException`).

## Addendum: concurrent, retried, live-tunable peer sampling (2026-08-30)

The "Up to 8 peers are tried sequentially, not concurrently" decision above was deliberately
flagged as revisitable: *"this can be revisited if real-world latency turns out to be a
problem - not clear it will be."* This is that revisit, triggered by a real debugging session
rather than speculation.

**The trigger**: a user's magnet add kept failing. Chased what looked like a network-level
block on port 6881 across dev mode, a Docker rebuild, and raw `nc` outside the app entirely -
along the way, a real bug surfaced and got fixed (`MetadataFetcher` never actually respected
the configured `EncryptionMode`, see `design_docs/0052`'s own addendum). The theory finally
fell apart against one piece of decisive evidence: the user pulled up qBittorrent's live peer
list for the exact same torrent, on the same machine and network - roughly 76 real peers,
several on port 6881 itself, all reachable. That ruled out a network block outright.
GrimTorrenter's own magnet-add path was simply trying far too few peers, sequentially, to
reliably beat ordinary BitTorrent swarm churn - most candidates in any real swarm are
routinely unreachable at any given moment (NAT, firewalls, offline), which a client trying
dozens-to-hundreds of candidates over real time shrugs off and one trying 8 once does not.

**The fix, discussed and revised with the user before landing on this shape - three pieces:**

1. **Race a round's candidates concurrently, not sequentially.** `TorrentEngine.raceOneRound()`
   builds one `Callable<byte[]>` per candidate and hands them to
   `ExecutorService.invokeAny()` (`Executors.newVirtualThreadPerTaskExecutor()`, the same
   virtual-thread-per-connection convention `[[0007-concurrency-model]]` already established
   elsewhere) - the first candidate to answer wins, and `invokeAny()` itself cancels every
   other still-in-flight or not-yet-started task. This alone turns a round's worst-case
   latency from *N × up to ~20s* (a `PREFERRED`-mode candidate may attempt both an encrypted
   and a plaintext connection) into roughly one candidate's worth of wall-clock time.
2. **Keep trying over a bounded time window, not just one batch.** Raised directly by the
   user reviewing an earlier draft of this fix that only widened a single batch: qBittorrent's
   peer list wasn't a static 76-peer snapshot, it grew to that over real time via repeated
   tracker re-announces/DHT lookups - a meaningful part of *why* it succeeds where a
   single one-shot batch might not. `fetchMagnetMetadataViaTrackerThenAdd()`/
   `fetchMagnetMetadataViaDhtThenAdd()` are now `do`/`while` loops: each iteration announces
   (or re-queries DHT), filters the response down to addresses not already tried this attempt
   (a `Set<PeerAddress> alreadyTried` accumulated across rounds, so a known-dead peer is never
   raced twice), races the fresh ones via `raceOneRound()`, and either returns immediately on
   success or loops back until a deadline computed once at the start passes. A round that
   found genuinely nothing *new* to try (every candidate in the response was already tried)
   sleeps a short fixed `EMPTY_ROUND_RETRY_DELAY` (5s, clamped to whatever's left of the
   budget) before looping - a round that actually raced fresh candidates needs no such delay,
   since racing already costs roughly one candidate's connect-timeout worth of time on its
   own, a reasonable re-announce cadence for free. Without that delay, a tracker/DHT that
   keeps handing back the same already-tried addresses would spin-loop re-announcing as fast
   as it can answer - a real edge case (common once a smaller swarm's candidates are mostly
   exhausted early), not a hypothetical one, so it's guarded explicitly rather than left as a
   surprise. A tracker announce or DHT lookup that itself throws still fails the whole attempt
   immediately, as before - retrying the identical broken tracker for the full budget isn't
   the problem this loop solves.
3. **Made the tuning numbers themselves live-configurable**, also raised by the user in the
   same review: rather than fixed constants or restart-only deploy config, `Settings` gained
   three new fields - `magnetFetchTimeBudgetSeconds` (default 90), `magnetFetchCandidatesPerRound`
   (default 50, also now drives the tracker announce's own `num_want` - replacing the old fixed
   `MAGNET_NUM_WANT` constant), and `magnetFetchConcurrencyLimit` (default 64) - the same
   live, user-editable, no-restart mechanism (`[[0041-live-settings-store]]`) already backing
   rate limits, MSE mode, and seeding limits, added the same "sibling constructor overload,
   touch zero existing call sites" way every prior field addition to that record has been. Same
   never-unlimited, silently-normalized-if-`<= 0` treatment `eventLogRetentionDays`/
   `watchFolderRetentionDays` already established - an advanced user (or someone actively
   diagnosing a connectivity issue, the user's own stated motivation for wanting this live) can
   set any of them generously high, just not to a degenerate value meaning "never give up" or
   "no concurrency bound at all." A new `magnet-fetch-settings` frontend group
   (`[[0045-settings-page]]`'s established per-topic component/form-builder-pair shape) exposes
   all three; `Settings` already round-trips as a flat, DTO-less record, so no new REST surface
   was needed. Each of `fetchMagnetMetadataViaTrackerThenAdd()`/`ViaDhtThenAdd()` reads
   `settingsStore.current()` once at the start of its own attempt - so a live change takes
   effect on the *next* magnet add, matching how `encryptionMode`/rate limits are already
   described as applying "on the very next connection attempt," not retroactively mid-flight.

**`LiveResizableSemaphore`** (new, small, `TorrentEngine`'s own package): the concurrency
limit backs a `Semaphore` bounding simultaneous in-flight metadata-fetch connection attempts
*engine-wide*, not per-magnet-per-round - still needed even at a generous default, since the
retry loop can hold attempts open across a whole ~90s window per magnet, and the pre-existing
multi-magnet-paste feature (`TorrentList.submitMultipleMagnets`, frontend,
`design_docs/0029`) means several magnets can be in flight at once; without an engine-wide
cap, pasting several magnets at once could fan out into hundreds of simultaneous sockets. A
plain `java.util.concurrent.Semaphore` has no public "resize beyond initial" API, so this
small subclass adds one: `release(n)` safely grows the total with no prior `acquire()`
needed, and the JDK's own protected `reducePermits(n)` safely shrinks it without disturbing
permits already issued to an in-flight `acquire()` (its own Javadoc: "useful in subclasses
that use semaphores to track resources that become unavailable" - exactly this case).
`resizeTo()` tracks its own last-applied total (`availablePermits()` reflects currently-*free*
permits, not the configured total) and is `synchronized` - fine per
`[[0007-concurrency-model]]`'s "no synchronized in the hot path" rule, since a resize happens
at most once per magnet-add attempt, not per-candidate or per-byte.

**Stability** (`[[0051-stability-as-a-standing-consideration]]`): the semaphore above is
exactly what keeps the per-round candidate pool (up to 50, live-configurable higher), the
~90s retry window, and the unbounded number of magnets a user can paste at once from
compounding into unbounded socket fan-out - total concurrent connection attempts for this
purpose stays capped engine-wide regardless of how many magnets, rounds, or candidates are
in flight, and that cap can't be configured away to "unlimited" (same `<= 0`-normalized
treatment as the other two new fields). No change to per-torrent-count scaling - this only
fires during the one-shot magnet-add window, not for an active torrent's lifetime. Cleanup on
every exit path: `invokeAny()` itself cancels/interrupts every not-yet-completed task once a
winner is found or all fail, `MetadataFetcher.fetch()`'s own try-with-resources still closes
each `PeerConnection`, and the semaphore's `acquire()`/`release()` pairing is a standard
try/finally safe against mid-attempt interruption. Not reachable/triggerable by a hostile
remote peer or tracker beyond what could already happen today - a malicious tracker could
already return up to `magnetFetchCandidatesPerRound` bogus addresses; the semaphore is exactly
what prevents that from turning into unbounded fan-out either way.

**Tests**: `TorrentEngineMagnetTest` gained cases for concurrent racing within one round
(an early candidate unreachable, a later one real), the retry loop itself (a fake tracker
whose first response only offers an unreachable peer, whose second offers a real one), and
the empty-candidates guard (`raceOneRound()` returning immediately rather than calling
`invokeAny()` on an empty task list, which throws `IllegalArgumentException`) - all
constructed with a short (`magnetFetchTimeBudgetSeconds` of a few seconds, not production's
90) test `Settings` so they stay fast regardless of how many retry rounds they need.

## Testing

`NoOpTrackerClientTest` covers a bare announce (empty peers, positive/bounded interval).
`TorrentSessionTest` gained a case constructing a session with `NoOpTrackerClient`,
confirming `addKnownPeers` alone (no tracker involved at all) is enough to reach and
handshake a fake peer. `TorrentEngineTest` gained a case for `selectTrackerTiers`'s new
empty-list-not-throw behavior alongside its existing (unchanged) throws-when-declared-
but-unsupported case. `TorrentEngineMagnetTest`'s existing synchronous-throw test was
renamed to make explicit it only holds with DHT disabled, plus a new case confirming
`addMagnet` does *not* throw synchronously for the same trackerless magnet once DHT is
enabled (an ephemeral port avoids clashing with other tests; the full async DHT-discovery
path itself is already thoroughly covered at a lower level by `DhtNodeTest`/
`PeerLookupTest` against real local nodes, so this only proves the dispatch decision).

`CompactPeersTest` covers a single peer against hand-built exact bytes, multiple peers, an
empty list, rejecting a non-IPv4 address on encode, and rejecting both a wrong-length entry
and a non-string entry on decode - the same shape as `CompactNodesTest`, now that decode
exists too. `DhtNodeTest` gained direct `getPeers`/`announcePeer` round-trip coverage
(peerless -> announce -> found) and a bad-token-throws case, using the real client methods
this time rather than a raw socket. `PeerLookupTest` builds a 4-node chain (querier only
knows middle, middle only knows far) where a fifth node announces a peer to far via the
real get_peers-then-announce_peer sequence, and confirms `PeerLookup.findPeers` from
querier still finds it two hops away; separately confirms an empty routing table returns
an empty result immediately, and that `findPeers` itself announces the caller to whichever
node it successfully queried (confirmed by a fresh, independent `getPeers` from a third
node afterward).

`NodeLookupTest` builds a real 5-node chain (loopback, ephemeral ports) where each node
only directly knows the next one, and confirms an iterative lookup from the first node
discovers all four others purely through iteration - not something a single find_node
could do. Also covers an empty routing table producing an empty result immediately (no
hang), and that a lookup's result is both capped at `RoutingTable.BUCKET_SIZE` and sorted
by distance when more candidates than that are available. `BootstrapTest` points `Bootstrap`
at a real local `DhtNode` standing in for a bootstrap host (with one further contact of its
own, to confirm the follow-up lookup finds something beyond the bootstrap host itself), and
separately confirms an unreachable bootstrap host leaves the routing table empty rather
than throwing.

`DhtNodeTest` (extended with more cases, same fixture) drives get_peers/announce_peer
against `DhtNode`'s server side via a raw socket sending hand-built queries directly -
deliberately bypassing `DhtNode`'s own client API, since this slice doesn't add a
client-side get_peers/announce_peer (that's slice 5): a peerless `get_peers` falls back to
"nodes" and issues a token; a valid `announce_peer` is both accepted and then surfaced by a
follow-up `get_peers`'s "values"; `impliedPort` is confirmed to use the packet's actual
source port over a deliberately-wrong declared one; and a bad token gets error code 203
back rather than silent acceptance or a dropped packet.

`DhtNodeTest` runs two real `DhtNode`s against each other over loopback (ephemeral ports):
a successful `ping` populates both sides' routing tables (confirms both the query-answering
and response-handling insertion paths); `findNode` returns contacts seeded directly into
the responder's routing table; a query to an unreachable address times out; and a garbage
UDP packet sent mid-test is silently dropped without killing the receive loop (proven by a
following `ping` still working). `CompactNodesTest` covers a single node against
hand-built exact bytes, multiple nodes, an empty list, rejecting a non-IPv4 address on
encode, and rejecting a decode length that isn't a multiple of 26.

`NodeIdTest` covers the `of`/`bytes` round trip, both length-validation paths (hex
constructor and `of(byte[])`), and `distanceTo`: zero distance to self, symmetry, and two
hand-computed cases (XOR differing only in the least-significant byte, and only in the
most-significant bit) confirming the byte-order assumption `RoutingTable`'s bucket index
depends on. `RoutingTableTest` covers an empty table, a plain insert, refresh-not-duplicate
on re-inserting a known id, a full bucket returning its least-recently-seen contact instead
of adding a 9th (contacts chosen so all land in the same bucket), the evict-then-replace
follow-up, and `closestNodes` ordering/limiting.

`KrpcCodecTest` covers all four query types plus response and error, each checked
in both directions against hand-built exact bencode bytes (not just a round-trip
through the codec's own encoder, for the same reason `UtMetadataCodecTest`
does this - see that test's existing rationale below), plus `announce_peer`'s
optional `implied_port` defaulting to false when absent. Malformed-input cases
(missing/wrong-type fields, unknown message type/query method, a wrong-length
node id, a malformed error list) are built via `BDictionary`/`BencodeEncoder`
directly rather than hand-typed bencode literals, since getting length prefixes
right by eye for negative-path bytes nobody double-checks is exactly the kind of
transcription mistake that class of test exists to catch.

`TorrentEngineMagnetTest` drives a full magnet -> tracker announce -> real
peer connection -> BEP 9 fetch -> `addTorrent` chain against a fake HTTP
tracker and a fake peer, plus the synchronous no-usable-tracker rejection.
`TorrentResourceTest` covers the REST endpoint's accept/malformed/
no-tracker cases (not the full async chain again - that's what the engine
test above is for).

`BencodeDecoderTest` covers `decodePrefix` (stopping after one value,
reporting bytes consumed, the dict-followed-by-raw-bytes shape ut_metadata
needs). `UtMetadataCodecTest` covers all three message types against
hand-computed exact bencode bytes (both directions, not just round-trips,
to catch a mistake in one side canceling out the same mistake in the
other) and the malformed-input rejection cases. `PeerConnectionTest`
gained cases for advertising a non-empty extensions map and capturing
`metadata_size`. `MetadataFetcherTest` drives a fake peer through the
full handshake + ut_metadata exchange for both a single-piece and a
forced multi-piece info-dict, plus the failure paths (unsupported
extension, missing metadata size, a rejected piece, and a peer serving
metadata that doesn't hash to the requested info hash).

`Base32Test` covers the decode algorithm directly (all-zero, all-0xFF, a
known mixed value worked out by hand, case-insensitivity, an invalid
character). `MagnetLinkTest` covers hex and Base32 info hashes, hex
case-normalization, optional `dn`/`tr`, ignoring unknown params, the
multi-`xt` first-usable-wins rule, and the rejection cases (not a magnet
URI at all, no usable `xt`, wrong-length hash).

`PeerWireCodecTest` covers `Extended`'s wire round-trip (both the
handshake id and an arbitrary other id) and rejects a payload too short
to even contain the extended-message-id byte. `PeerConnectionTest` covers
the real end-to-end case with a fake peer: both sides advertising support
results in us sending `{"m": {}}` and correctly parsing the peer's own
`{"m": {"ut_metadata": 3}}` into `remoteExtensionId`; a peer that doesn't
advertise support never receives an extended handshake at all (made
explicit rather than only implied by other tests' message-count
assumptions).

### DHT status REST endpoint (`grimtorrenter-app`)

The first REST-layer visibility into DHT: `GET /api/dht/status` -> `{enabled, nodeCount}`
(`TorrentEngine.DhtStatus`, a nested record alongside the existing `AddTorrentResult`, and
its `DhtResource`/`DhtStatusView` app-layer counterparts, mirroring `TorrentView`'s own
`from(...)` pattern).

- **A separate resource/endpoint, not a field added to `TorrentView`.** Confirmed with
  the user while discussing the frontend's overall data-fetching shape: `TorrentView` is
  the always-broadcast summary (pushed to every connected client every 2 seconds via
  `TorrentSnapshotScheduler`, see [[0019-rest-and-websocket-layer]]/[[0027-table-row-identity-for-live-updates]]),
  so it should stay bounded to what every list row actually needs to render. DHT status is
  global (not scoped to any one torrent) and detail-level - the general shape settled on
  is that this kind of data gets its own small, self-contained, on-demand endpoint, polled
  only while something is actually displaying it, rather than riding the snapshot. This
  endpoint is the first instance of the pattern, not a decision that needed the whole
  inventory settled first (it isn't even in the same resource family: global engine state
  vs. per-torrent data) - the fuller per-torrent inventory (peers, trackers, files, piece
  availability, ...) that follows the same shape is [[0031-torrent-detail-endpoints]].
- **`nodeCount` is `0`, not null/omitted, whenever `enabled` is `false`** - lets a
  consumer render it directly without a null check; "DHT is off" and "DHT is on with zero
  known nodes yet" are already distinguishable via `enabled` alone.

## Addendum: routing-table health - periodic bucket refresh and a real replacement policy (2026-08-30)

**The trigger**: after the concurrent/retried peer-sampling fix above shipped and was
confirmed working live, the user noticed GrimTorrenter's own DHT node count stayed at ~16-21
known nodes indefinitely, while qBittorrent (libtorrent) reached several hundred on the same
network. Root-caused directly against this doc's own k-bucket/`NodeLookup` sections below -
both gaps were flagged as deliberately deferred when they first shipped - plus a reference
check against libtorrent-rasterbar's real `routing_table.cpp` (`arvidn/libtorrent`, `RC_2_0`
branch), not guesswork from the BEP 5 spec alone:

1. **`Bootstrap.run()` fires exactly once, at startup**, and its one `NodeLookup` self-lookup
   only explores the neighborhood near our own node id (bounded to the closest-8 shortlist -
   see "iterative find_node lookup and bootstrap" above). Nothing ever queries into the other
   ~150 buckets covering the rest of the 160-bit id space. libtorrent, by contrast, runs
   continuous background bucket refresh (`routing_table::next_refresh()`, polled on a timer)
   plus a self-refresh every ~15 minutes.
2. **A full bucket never evicted a stale contact for a better one** - the "k-bucket routing
   table" section above already built `RoutingTable.insert()`'s ping-then-evict contract and
   even proved it works (`RoutingTableTest.evictingThenInsertingReplacesTheOldContact`), but
   explicitly noted it "is deliberately not acted on yet... not turned on until there's a
   concrete reason to (e.g. once bootstrapping/refresh in a later slice starts exercising the
   table enough for it to matter)." This is that later slice.

Both are structural, not a bug: the table's own theoretical capacity is 1280 contacts (160
buckets x 8), but almost none of it was ever touched, and once the handful of buckets near our
own id filled up during the one startup lookup, growth stopped entirely from that path.

**Framing from the user, worth recording**: the project's stability goal
([[0051-stability-as-a-standing-consideration]]) was already mostly achieved by this point -
this was never a stability problem, the client behaved correctly with a sparse table. It was a
**usability** gap: a genuinely underused resource made peer discovery slower than it should be.

**The fix - turn on the replacement policy, and add bucket refresh reusing `NodeLookup`:**

1. **Replacement policy.** `DhtNode.seen(id, from)` - called from both `handleQuery` and
   `handleResponse`, i.e. on the single receive-loop thread - now acts on
   `RoutingTable.insert()`'s returned stale contact instead of discarding it: spawns a virtual
   thread (never inline - the receive loop must keep processing other packets, same "don't
   block on real network I/O" reasoning `reannounceViaDht()` already established in
   [[0036-dht-backstop-for-tracker-bearing-torrents]]) that pings the stale contact
   (`REPLACEMENT_PING_TIMEOUT`, the same 5s convention `Bootstrap`/`PeerLookup`/
   `TorrentSession`'s own DHT timeouts already use). If it answers, `insert()` it again to
   refresh it (the new candidate is simply not added this round - matches BEP 5); if it times
   out, `evict()` it and `insert()` the new candidate in its place - exactly the sequence
   `RoutingTableTest.evictingThenInsertingReplacesTheOldContact` already proved works, now
   actually driven automatically. No new synchronization needed - `RoutingTable`'s `insert`/
   `evict` were already `synchronized`, so a burst of concurrent replacement attempts against
   the same bucket is safe (self-correcting if two candidates race for the same stale slot;
   occasional extra churn, never a leak or a bucket exceeding `BUCKET_SIZE`).
2. **Periodic bucket refresh.** `RoutingTable` gained a `lastRefreshed` timestamp per bucket -
   touched by every successful `insert()`, or explicitly via a new `markRefreshed(bucketIndex)`
   after a refresh that found nothing new - plus `mostOverdueBucket()` (the bucket that's gone
   longest without either, a never-touched bucket always sorting first) and
   `randomIdInBucket(bucketIndex)`, the standard Kademlia "pick a lookup target inside this
   bucket" technique: copy our own id, flip the one bit that determines `bucketIndex`
   (`distanceTo(id).bitLength() - 1`, counting from the least significant bit), randomize
   every bit below that position, leave every bit above it unchanged (or the distance would
   land in a different bucket entirely). `DhtNode.refreshRoutingTable()` ties these together -
   picks the most-overdue bucket, generates a target inside it, and runs
   `NodeLookup.run(this, target, timeout)` - reusing `NodeLookup` exactly the way the
   bootstrap/`NodeLookup` section above already anticipated ("expected to be reused by a
   future routing-table refresh... entirely within this package"), no new lookup surface
   needed. Every node touched along the way already reaches the table normally via the
   existing `seen()` - unchanged.
3. **`TorrentEngine` wiring.** A third task on the existing `maintenanceScheduler` (already
   home to `checkSeedingLimits()`/`scanWatchFolder()`, [[0056]] - "two cheap, independent
   periodic engine-maintenance concerns sharing one thread rather than each getting its own"),
   gated on `dhtNode != null`. Unlike the other two, its own runnable
   (`refreshDhtRoutingTable()`) just spawns a virtual thread and returns immediately, rather
   than running the lookup inline - a `NodeLookup` can take several real seconds (bounded by
   its own `MAX_ROUNDS`/per-query timeout), and blocking the shared scheduler thread that long
   would delay `checkSeedingLimits()`/`scanWatchFolder()`'s own ticks.
4. **New live `Settings` field: `dhtRefreshIntervalSeconds`** (default 300s / 5 minutes), same
   sibling-constructor-overload and never-degenerate-value-normalization pattern as every
   prior field addition. Unlike `trackerlessDhtReannounceIntervalSeconds`
   ([[0036]]'s own addendum), which drives a *per-torrent* scheduled task re-read on each
   `start()`, this drives an *engine-wide* one read once at construction - so a live change
   here takes effect on the engine's next construction/restart, not retroactively (same
   underlying "a `ScheduledExecutorService`'s period can't change mid-flight" limitation, just
   at engine scope). Deliberately shorter than libtorrent's own observed ~15-minute cadence -
   each tick is one lightweight `find_node` lookup against a single bucket, much cheaper than
   a full `get_peers` reannounce, so a shorter default is reasonable DHT etiquette while still
   visibly filling in the table faster after a fresh start. Exposed as a new row in the
   existing Network settings group, alongside `trackerlessDhtReannounceIntervalSeconds`.

**Deliberately out of scope**: persisting the routing table across restarts (so the app starts
"warm" instead of cold-bootstrapping every time) - a related but distinct concern (cold-start
speed after a restart, not "stays sparse while running," which the fix above directly
addresses). Logged to `TODO.md` as a separate follow-on rather than folded in here.

### Testing

`RoutingTableTest` gained cases for `randomIdInBucket()` (several bucket indices including
both ends, 0 and 159, each checked against the same `distanceTo`/`bitLength` computation
`bucketIndex()` uses internally), `mostOverdueBucket()` preferring a never-touched bucket over
a recently-touched one, and `insert()` touching its own bucket's refresh timestamp as a side
effect. `DhtNodeTest` gained real-loopback-socket cases (same style as this doc's original
DHT tests) for both replacement outcomes - an unreachable stale contact gets evicted and
replaced, a reachable one is kept and the new candidate dropped - and a
`refreshRoutingTableDiscoversARealNodeThroughAKnownBridge` case proving the mechanism reaches
a node known only indirectly (through a bridge node), the same multi-hop shape a real refresh
needs; deterministic rather than relying on mocked randomness, since bucket 0 (a fresh table's
own `mostOverdueBucket()`) has a fully deterministic `randomIdInBucket()` result (bucket 0
covers XOR distance exactly 1 - the least significant bit differs and nothing else, so there
are no lower bits left to randomize). `TorrentEngineTest` gained a smoke test confirming the
`maintenanceScheduler` wiring itself runs without throwing - `DhtNode.refreshRoutingTable()`'s
own behavior is already thoroughly covered at that lower level, so this only proves the
engine-level plumbing.

### Follow-up fix: fall back to a full bootstrap retry when the table is still too sparse

**The trigger**: deployed the fix above and, on the very next real run, the DHT node count
regressed - to just 1, worse than the ~16-21 baseline from before this addendum, with the
existing trackerless torrent stalled and a re-add finding "0 peer(s) tried." First suspected
was the new replacement policy causing a burst of simultaneous pings that got outbound DHT UDP
throttled, wrongly evicting genuinely good contacts in a runaway collapse - a real risk in
principle, but the DEBUG log the user captured didn't support it: no sign of replacement
activity at all (which never fires unless a bucket is genuinely full - impossible with a
near-empty table), and only 2 of the 3 hardcoded bootstrap hosts (`router.bittorrent.com`,
`router.utorrent.com`) even logged a failure. The third, `dht.transmissionbt.com`, logged
nothing - almost certainly meaning it succeeded, becoming the *only* contact bootstrap's
self-lookup had to expand from. If that lone contact's own `find_node` response came back
empty (or the query itself failed), the self-lookup terminates having found exactly what
bootstrap itself supplied - one node. This lines up with a live, independent finding from
earlier the same session: a raw KRPC ping sent directly to `router.bittorrent.com`/
`router.utorrent.com` on port 6881 from this exact network timed out on both, outside the app
entirely - this network's reachability to those two specific hosts is impaired, unrelated to
anything built today. (Initially assumed "flaky" - a second real run's DEBUG log, captured
after the fix below, showed the *identical* two hosts failing again, and a third manual ping
test confirmed it a third time. Not flaky: a deterministic, persistent failure to reach those
two specific hosts from this network - see the host-list expansion below, which is the fix
that actually addresses this properly, once "just retry" turned out not to be enough on its
own.)

**The actual gap this exposed**: `refreshRoutingTable()`'s bucket-refresh path is powerless to
recover from a bootstrap that barely got off the ground. `NodeLookup` always seeds itself from
`routingTable.closestNodes(...)` - whatever's *already* known. With only one or two contacts in
the whole table, every subsequent refresh tick just re-queries that same handful of contacts
for a different random target, never getting a genuine second chance at reaching the wider
network the way a fresh attempt at the well-known hosts would. A flaky first bootstrap could
therefore leave the table starved for the entire process lifetime, and this addendum's own new
mechanism did nothing to fix that specific failure mode.

**The fix**: `refreshRoutingTable()` now checks `routingTable.size()` first - below
`MIN_HEALTHY_NODE_COUNT` (`RoutingTable.BUCKET_SIZE`, 8 - a natural "at least one healthy
bucket's worth" cutoff, not chosen for any more specific meaning), it re-runs full
`bootstrap()` (re-pinging the three well-known hosts, then a fresh self-lookup) instead of a
narrow single-bucket refresh. Once the table clears that threshold, it falls back to the
per-bucket refresh already built above. This gives a flaky initial bootstrap a real, repeated
second (and third, and fourth...) chance every `dhtRefreshIntervalSeconds`, rather than only
ever getting one shot at process startup.

**`DhtNodeTest` updates**: `refreshRoutingTableDiscoversARealNodeThroughAKnownBridge` (the
bucket-refresh test above) needed padding - its 2-contact setup (bridgeNode + implicitly
target once discovered) now falls below `MIN_HEALTHY_NODE_COUNT` and would take the new
bootstrap-retry branch instead of the path it means to test. Padded with 7 more directly-
inserted contacts in an unrelated bucket (never pinged - nowhere near full) purely to clear
the threshold. A new `refreshRoutingTableReRunsBootstrapWhenTheTableIsStillSparse` smoke-tests
the new branch is reachable and doesn't throw - real bootstrap success/failure is inherently
network-dependent (as this whole investigation just demonstrated firsthand), so, like
`TorrentEngineMagnetTest`'s own DHT-enabled tests, this only asserts the call completes, not a
specific outcome.

### Second follow-up fix: two more independently-confirmed bootstrap hosts

**The trigger**: the retry fix above shipped and genuinely helped (node count climbed from 1 to
2 on the next real run - real, if slow, progress), but a second DEBUG log capture, from a fresh
restart, showed `router.bittorrent.com` and `router.utorrent.com` failing *again*, the exact
same two hosts as the first capture. That ruled out "flaky" - two identical failures across two
independent runs is a deterministic, persistent gap for this network, not transient bad luck.
A direct manual KRPC ping test (bypassing the app entirely, same technique used earlier this
session) confirmed it a third time: those two hosts reliably don't respond, while
`dht.transmissionbt.com` reliably does. Retrying the same three hosts on a schedule (the fix
above) can only ever get one real vote out of three on this network - not nothing, but not much
either, and it explains why growth was real but slow.

**The fix**: rather than accept "1 of 3 works here," added genuine redundancy - tested other
well-known public DHT bootstrap hosts directly (same manual KRPC ping technique) before adding
anything speculative. `dht.libtorrent.org` (a different port, 25401 - real bootstrap hosts
don't actually agree on one) responded immediately - a real, actively-used bootstrap host
(libtorrent's own, which qBittorrent - the very client whose much higher node count motivated
this whole investigation - is built on) confirmed reachable specifically from the network that
was struggling. The user then supplied the actual list libtorrent/qBittorrent configures via
its own `session.add_dht_router(...)` calls, an authoritative source rather than a guess:
`router.utorrent.com`, `router.bittorrent.com`, `dht.transmissionbt.com` (all three already
present), `router.bitcomet.com`, and `dht.aelitis.com`. Checked both of the two new ones
directly: `router.bitcomet.com` doesn't resolve at all anymore (confirmed independently by the
user too - genuinely retired, not worth adding as permanently-dead weight); `dht.aelitis.com`
(Vuze/Azureus) resolves and is a real host, but didn't respond from this particular network -
added anyway, on the same reasoning that kept `router.bittorrent.com`/`router.utorrent.com`
despite them failing here too: BEP 5 already treats an unreachable bootstrap host as
expected/tolerated, so an extra independent host only ever helps on whichever network it does
work from, never hurts. Net result: five hosts, not the original three, with `dht.transmissionbt.com`
and `dht.libtorrent.org` the two confirmed-working ones on the network that surfaced this whole
investigation.

**`DEFAULT_HOSTS`'s shape changed** from `List<String>` plus one shared port constant
(`DEFAULT_PORT`, 6881) to `List<BootstrapHost>` (a new small record, `host` + `port`) - real
bootstrap hosts don't actually agree on a port: `dht.libtorrent.org` uses 25401, not 6881.
`Bootstrap.run(DhtNode, List<BootstrapHost>, Duration)` replaces the old four-arg overload
(dropped the now-redundant separate `port` parameter); `BootstrapTest`'s two existing cases
updated to construct a `BootstrapHost` directly instead of passing a bare hostname string plus
port. A new `defaultHostsIncludesFiveRedundantBootstrapHostsWithTheirOwnPorts` test locks in
the five-host list and each one's specific port, so a future accidental host/port edit would
be caught rather than silently drifting.

**Not addressed here, worth noting**: `Bootstrap.seedFrom()` still pings hosts sequentially,
each up to `DEFAULT_QUERY_TIMEOUT` (5s) - with potentially three of five now failing on a given
network, one bootstrap/retry pass can take up to ~15s of wall-clock time before the self-lookup
even starts. Not a correctness problem (bootstrap and every retry already run on their own
virtual thread, never blocking the maintenance scheduler or anything else), just a minor
latency cost noted for anyone revisiting this later - racing the seed pings concurrently
(the same `ExecutorService.invokeAny()`-or-similar pattern already used elsewhere in this
codebase) would be a natural, low-risk follow-on if it ever proves to matter in practice.

### Third follow-up fix: persist the routing table across restarts

**The trigger**: the two fixes above (bootstrap retry, more redundant hosts) both still depend
entirely on whichever of the five hardcoded hosts happen to be reachable *right now*, every
single restart - there's no way to benefit from nodes this process already proved reachable in
a *previous* run. Raised directly by the user mid-investigation, already logged as a
deliberately-deferred follow-on when the periodic-refresh feature was first planned earlier the
same day - picked up now while the DHT-reliability context (and its evidence) was fresh.

**The design**: save the routing table's contacts to disk periodically and on shutdown; on the
next start, ping them - concurrently, not sequentially - before/alongside the existing
hardcoded-host bootstrap.

- **`RoutingTable.allNodes()`** - a plain, unsorted enumerator across every bucket (mirrors
  `size()`'s own shape; unlike `closestNodes()`, nothing here needs distance ordering).
- **`DhtNode.knownContacts()`** - `allNodes()` mapped to `(address, port)` pairs only, no id.
  The id isn't needed to re-ping a persisted contact, and dropping it sidesteps any concern
  about trusting a stale/wrong one - the real, current id always comes back fresh in that
  ping's own response, the same as any other newly-heard-from contact.
- **`DhtNode.bootstrap(List<InetSocketAddress>)`** - a new overload, not a parameter added to
  the existing method (the plain `bootstrap()` stays as the zero-persisted-contacts case, every
  existing caller/test untouched). Pings every contact **concurrently**
  (`Executors.newVirtualThreadPerTaskExecutor()`, one virtual thread per contact) - deliberately
  *not* `Bootstrap.seedFrom()`'s own sequential loop, which is fine for 5 hardcoded hosts but
  would scale wall-clock time linearly with a persisted list that could be far larger; this way
  a warm start costs roughly one timeout's worth of time regardless of how many contacts were
  saved. Same round-of-concurrent-queries shape `NodeLookup`/`TorrentEngine.raceOneRound()`
  already establish elsewhere. **Staleness/re-validation, resolved for free**: each contact is
  verified exactly the way any hardcoded bootstrap host already is - only trusted (reaches the
  routing table via `seen()`) if it actually answers a real ping. No separate "verify before
  trusting" pass was needed; a saved contact that's gone dead since the last run costs one
  timeout and is silently skipped, same as an unreachable `router.bittorrent.com` already is
  today. Falls through to the existing no-arg `bootstrap()` afterward - persisted contacts are
  a head start, never a replacement for the hardcoded-host mechanism.
- **`TorrentEngine`** ties it together: a new plain-text marker (`.grimtorrenter-dht-nodes`,
  same "no JSON library at this layer" convention as every other marker file here, one
  `ip,port` line per contact) is loaded in `createDhtNode()` and handed to the new
  `bootstrap(List)` overload instead of the old bare `node::bootstrap`; a new package-private
  `saveDhtRoutingTable()` (same test-visibility convention as `checkSeedingLimits()`/
  `scanWatchFolder()`) writes `dhtNode.knownContacts()` back out, via write-to-temp-then-
  `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` so a save racing a process exit can never
  leave a torn file behind. Wired in twice: `refreshDhtRoutingTable()`'s existing virtual
  thread calls it right after `dhtNode.refreshRoutingTable()` (piggybacks on the already-
  scheduled `dhtRefreshIntervalSeconds` cadence rather than adding a second timer), and
  `shutdown()` calls it once more, synchronously, right before `dhtNode.close()` - between the
  two, an unclean exit (kill, crash) loses at most one refresh interval's worth of changes, not
  everything.
- **`TorrentEngine.dhtNode()`** - a new package-private accessor, purely for test access (the
  same "package-private purely for testability" convention used throughout this class already)
  - lets a test seed a real contact directly into the routing table without waiting on real
  bootstrap, the same technique every other `dht`-package test already uses.

**Stability**: no new unbounded growth (`RoutingTable`'s existing 1280-contact hard cap already
bounds the persisted file's size); no new concurrency pattern (the warm-start ping round reuses
an established shape, the atomic-rename save avoids introducing a file-corruption class of bug
instead of adding one); every failure path (missing/corrupt file on load, an unreachable
persisted contact, a failed save) is non-fatal and independently logged, matching the tolerance
every other marker file in this codebase already has; the shutdown-time save sits inside the
existing `shutdown()` method, on every graceful exit path that already exists.

**Testing**: `RoutingTableTest` covers `allNodes()`. `DhtNodeTest` covers
`bootstrap(List<InetSocketAddress>)` both ways - a reachable contact gets pinged and added,
an unreachable one is tolerated and never reaches the table (checked precisely by address+port,
not by asserting the whole table stays empty - this overload always falls through to the real
`bootstrap()` afterward, which could legitimately add unrelated real contacts of its own, same
"tolerated real internet access" acceptance `TorrentEngineMagnetTest`'s own DHT-enabled tests
already document). `TorrentEngineTest` gained a full save-then-reload round trip: seed a real
loopback contact into one engine's table, save, shut that engine down, construct a *second*
`TorrentEngine` against the same `baseDownloadDirectory`, and poll for its `dhtStatus()` to
reach at least 1 node - proving the warm start actually reaches the routing table on a fresh
"start," not just that the file gets written.

### Fourth follow-up fix: move the two DHT marker files into the config directory

**The trigger**: raised directly by the user - the two DHT marker files
(`.grimtorrenter-dht-node-id`, and now `.grimtorrenter-dht-nodes` from the persistence work
above) both resolve against `baseDownloadDirectory`, the top-level directory a user actually
browses their torrents in. Unlike every *per-torrent* marker (info hash, state, seeding-limit
override), which correctly lives inside that torrent's own subdirectory, these two are
engine-wide and sat directly at the download directory's root - visible clutter (as dotfiles,
hidden by default, but still there with "show hidden files" or `ls -a`) next to the user's
actual torrent folders.

**The fix**: the app already has exactly the right home for this. `grimtorrenter.config-
directory` (default `config`) is already used by `JsonSettingsStore` (`settings.json`) and
`JsonLinesEventStore` (`events/`, both `grimtorrenter-app`) for engine/app-level bookkeeping,
separate from `download-directory` where actual torrent data lives - the two DHT marker files
are the same kind of thing and now live there too.

`TorrentEngine` itself didn't previously accept a config directory at all - only
`baseDownloadDirectory`. Added the exact same way `watchDirectory` was
(`design_docs/0056`'s own precedent): a new widest constructor (twelve-arg, appending `Path
configDirectory`) does the real work; the previous widest (eleven-arg, ending
`Path watchDirectory`) becomes a delegating overload defaulting `configDirectory` to
`baseDownloadDirectory` - **preserves every pre-existing caller/test's exact current behavior**
(confirmed via a full call-site sweep - only `WatchFolderTest.newEngine()` calls that eleven-arg
form directly, and it stays valid, unchanged). `createDhtNode()`/`loadOrGenerateDhtNodeId()`/
`loadPersistedDhtContacts()`'s parameters, previously named `baseDownloadDirectory` (a stale
name even before this - they always only ever touched the DHT markers, never torrent data),
renamed to `configDirectory` to match what they're actually for now; `saveDhtRoutingTable()`
resolves against the new `configDirectory` field instead. `TorrentEngineProducer` reuses the
exact `grimtorrenter.config-directory` property `JsonSettingsStore`/`JsonLinesEventStore`
already read, rather than introducing a second property for the same directory.

No stability implications beyond what was already true - this only changes *which* directory
two already-tolerant, already-non-fatal marker files resolve against; no new resource or
failure-handling surface. `TorrentEngineTest` gained a case constructing an engine with two
genuinely different directories via the new widest constructor, confirming the DHT-nodes
marker lands under the config directory and specifically not under the download directory.

## Alternatives considered

- **Reject unknown magnet params outright** - rejected; BEP 9's own magnet
  URI convention explicitly allows extra params clients don't understand,
  and rejecting them would make otherwise-perfectly-usable real-world
  magnet links (most of which include params like `xl`) fail to parse.
- **Parametrize `PeerConnection`'s advertised extensions map now** -
  deferred to the `ut_metadata` slice, which will actually need it; see
  above.
- **A UI-visible "fetching metadata" pending state for in-progress
  magnets** (a new `TorrentState`-like value, a tracking structure in
  `TorrentEngine` alongside `sessions`, a union DTO) - explicitly
  considered and rejected for now, given how much heavier it is than
  everything else in this slice combined: it would need a torrent to be
  representable in the UI *before* a `TorrentSession` (which requires
  `TorrentMetadata`) can even be constructed. Deferred to the same
  add-to-visible latency gap already accepted for regular uploads.
  Revisit if a magnet add's silence proves confusing in practice.
