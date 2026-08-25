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
  peer that actually has the metadata.
- **A total failure (no tracker reachable, or none of the peers tried had
  the metadata) is only logged server-side, not surfaced to the UI.**
  Raised explicitly rather than silently decided: this is the same
  accepted add-to-visible latency/feedback gap already noted for a
  regular `.torrent` upload (see the `upload_latency_ux` memory note,
  deferred to the future visual design pass) - a magnet add can only ever
  be slower and less certain than a local file upload, so it inherits the
  same deferral rather than getting bespoke "pending" UI treatment now.
  Once metadata succeeds, the resulting torrent appears exactly like any
  other, through the existing snapshot/push mechanism.
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
