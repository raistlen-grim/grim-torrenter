# 0052 — Message Stream Encryption (MSE)

**Status:** Accepted

## Decision

The last remaining item from [[0009-phased-scope]]'s Phase 3 list. MSE (also called Protocol
Encryption) obfuscates the BitTorrent handshake and, once negotiated, the entire connection -
defeating naive deep-packet-inspection-based throttling/blocking, and incidentally hiding
traffic content from anyone but the two peers. Chosen as the first remaining Phase 3 item to
pick up (user decision, over per-torrent rate overrides / burst allowance / a multi-rule
schedule).

Verified against the actual spec text (RFC 2409 for the Diffie-Hellman prime, a source-checked
writeup for the handshake byte layout) rather than trusted from memory - see "A real bug
caught mid-implementation" below for exactly why that caution mattered.

### `EncryptionMode`: `DISABLED` / `PREFERRED` / `REQUIRED`

The standard 3-way split real clients use (new enum, `mse` package). Global, not per-torrent -
matches [[0042-rate-limiting]]'s scoping precedent. **Default `PREFERRED`** (user decision) -
attempt encrypted connections by default, falling back to plaintext when a peer doesn't
support it. This is the one place this project's settings defaults deliberately break from
its usual "opt-in, off by default" convention (rate limits, schedule): MSE's whole benefit
(resisting naive ISP traffic-shaping) is one most users would want without having to find the
setting first, and `PREFERRED` never refuses a connection a plaintext-only peer would
otherwise have completed - unlike a rate cap, there's no accidental-slowdown risk to opting
everyone in by default.

**Live, not restart-required** - read fresh from `SettingsStore` on every connection
attempt/accept, the same "genuinely live" pole [[0042-rate-limiting]] established, not the
`dhtEnabled`/`acceptIncomingConnections` restart-required pole ([[0041-live-settings-store]]).
There's no long-lived object whose construction bakes the value in the way `DhtNode`/
`PeerServer`'s own existence does.

### Hand-rolled RC4 and Diffie-Hellman - no new dependency

`grimtorrenter-engine/pom.xml` has zero production dependencies (confirmed before writing any
code - only JUnit, for tests), matching this project's stated identity of implementing the
protocol directly rather than wrapping a library.

- **RC4** (`Rc4Cipher`, `Rc4InputStream`/`Rc4OutputStream`, `mse` package): ~30 lines (KSA +
  PRGA), fully spec-defined, verified against the published Wikipedia RC4 test vectors (not
  just self-round-trip - a self-consistent-but-wrong implementation would still pass a
  round-trip-only test). The "never roll your own crypto" caution is aimed at algorithms
  where a subtle bug silently breaks real confidentiality (AES-GCM, RSA padding, TLS state
  machines); RC4 in MSE isn't that, since MSE was never designed to provide real
  confidentiality in the first place (RC4 is already a broken cipher for that purpose) - its
  only job is obfuscating the byte pattern against naive DPI/traffic-shaping.
- **Diffie-Hellman** (`DiffieHellman`, same package): `BigInteger.modPow` (a JDK primitive)
  against RFC 2409 Oakley Group 1's 768-bit prime/generator - the specific constants MSE's own
  spec requires, not a project choice. No dependency needed here either - `BigInteger` was
  already an accepted dependency elsewhere in this codebase (`NodeId.distanceTo`).
- **Rejected**: Bouncy Castle (a large general-purpose crypto library for one legacy cipher
  nothing else here would use) and the JDK's own `javax.crypto.Cipher.getInstance("RC4")`
  (RC4/ARCFOUR is flagged as a legacy algorithm in some JDK distributions' `java.security`
  policy and can be restricted depending on provider/version - exactly the platform-fragility
  [[0051-stability-as-a-standing-consideration]] cares about avoiding; a dependency-free
  implementation behaves identically on every JVM).

### A real bug caught mid-implementation: the DH prime constant

`DiffieHellman.P`'s first draft accidentally pasted RFC 3526 Group 14's 2048-bit prime past a
shared prefix with RFC 2409 Group 1's actual 768-bit prime - the two primes share a long
common hex prefix by construction (RFC 3526's groups were built by extending RFC 2409's), which
is exactly why the mistake wasn't visible on sight. Combined with a hardcoded 96-byte (768-bit)
wire serialization length, this silently truncated every public key on the wire, so each side
derived the shared secret from a corrupted copy of the other's key - surfaced as a confusing
"both sides computed different secrets" test failure, not an obviously-wrong constant. Fixed by
verifying the correct value against RFC 2409's actual text before correcting it, and adding a
dedicated `DiffieHellmanTest` assertion (`P.bitLength() == 768`) so a similar mistake fails
immediately and legibly instead of two tests downstream.

### The negotiation protocol (`MseHandshake`)

Full detail lives in the class itself; the shape of it:

- **`negotiateOutbound`**: DH exchange, then `HASH('req1', S)` / `HASH('req2', SKEY) xor
  HASH('req3', S)` / the encrypted `crypto_provide` exchange, then locates the receiver's
  reply by **trial-decrypting** candidate offsets (a fresh throwaway cipher per candidate)
  against the known verification constant `VC` - the receiver's reply is never sent in the
  clear, unlike the initiator's own sync marker, so a literal byte search isn't possible for
  this direction.
- **`negotiateInbound`**: the mirror image - locates the initiator's sync marker via a literal
  20-byte sliding-window search (it *is* sent in the clear), then recovers **which torrent**
  the connection is for by computing `HASH('req2', candidate) xor HASH('req3', S)` for every
  currently-active info hash and comparing against what the peer sent, since an incoming
  connection doesn't say its torrent in the clear the way a plaintext handshake does.
- Both search windows are bounded to the spec's own 512-byte max padding length - small and
  fixed, not unbounded.
- **No `IA` (initial payload) embedding on the outbound side** - the spec allows embedding the
  BT handshake inside the negotiation to save a round trip; skipped for simplicity, at the
  cost of one extra round trip on connection setup (negligible for BitTorrent). **Inbound
  handling of a peer's embedded `IA` is still fully correct**, though - real clients (µTorrent,
  libtorrent-based ones) commonly do embed it when connecting to us, and a first pass at
  `negotiateInbound` would have decrypted and silently discarded that content. Caught before
  it shipped: discarding it would leave us hanging on every inbound connection from those
  clients, since they'd believe their handshake was already sent and never send it again
  separately. Fixed by threading the decrypted `IA` bytes through as the genuine first bytes
  of the result stream via `SequenceInputStream`, rather than discarding them.
- Full-stream RC4 once negotiated, not handshake-only obfuscation - every byte after
  negotiation (the plaintext-format BT handshake itself, then every subsequent message) flows
  through RC4 in both directions, matching the real spec and every major client.

### Wiring into `PeerConnection` and `PeerServer`

- **`PeerConnection`**'s constructor now takes an explicit `InputStream`/`OutputStream` pair
  instead of deriving them from the socket - so a caller that already completed MSE
  negotiation can hand over the resulting (possibly RC4-wrapped) streams. `connect()` gained
  an `EncryptionMode`-aware sibling overload: `PREFERRED` attempts MSE first and, on any
  failure (peer doesn't respond as an MSE peer, sync point never found, negotiation error),
  opens a **fresh second connection** attempting a plain handshake instead - MSE negotiation
  state can't be rewound on the same socket once bytes have gone out. `REQUIRED` does not fall
  back. Every existing `connect()`/`accept()` overload is preserved unchanged via delegation
  (`EncryptionMode.DISABLED` default) - zero blast radius on other callers, the same
  sibling-overload pattern [[0042-rate-limiting]]/[[0047-bounded-file-handle-pool]] already
  established.
- **`PeerServer`** peeks the first byte of each accepted connection via a `BufferedInputStream`
  `mark(1)`/`reset()` (no bytes lost either way): `19` (the plaintext handshake's `pstrlen`)
  routes through the existing plaintext path unchanged; anything else is assumed to be the
  start of an MSE negotiation. `REQUIRED` rejects anything that peeks as plaintext; `DISABLED`
  rejects anything that doesn't.
- **`IncomingConnectionHandler`**'s signature changed directly (not via sibling overload) to
  carry the resolved stream pair through - the one exception to the zero-blast-radius pattern
  in this change, justified because it's a tiny, single-purpose internal interface with
  exactly one production implementor (`TorrentSession::acceptIncomingConnection`) and one test
  file, not a wide public API with dozens of call sites the way `connect()`/`accept()` are.
- `TorrentEngine` threads a `Supplier<EncryptionMode>` (reading `settingsStore.current()` live,
  same pattern as `RateLimiters`) down through `TorrentSession` and into `PeerServer`, plus
  `sessions::keySet` as `PeerServer`'s enumerable candidate-info-hash source for inbound SKEY
  matching.

### Frontend

`EncryptionMode` as a TS string-literal union (matching `TorrentState`'s existing convention,
not a TS `enum`), a new row in the existing **Network** settings group with a `p-select`
dropdown - the first select/dropdown control anywhere in this frontend (confirmed before
building it: no `p-select`/`p-dropdown`/native `<select>` precedent existed). Folded into
Network rather than given its own group, unlike rate limiting's own precedent
([[0045-settings-page]]'s "own group because more rate-limiting fields were already
anticipated") - no immediate reason to expect more encryption-specific settings soon.

**The group-level restart-required hint had to be restructured, not just extended.** Network's
existing hint ("Changes here take effect after the app restarts") was true of both its
existing fields but would be actively wrong for `encryptionMode`, which is live. Moved from one
blanket group-level sentence to each row's own description instead - more accurate per field,
not a weaker version of [[0041-live-settings-store]]'s "restart-required settings surfaced
inline, not silently" principle.

`p-select`'s `[styleClass]` needed its width rule in the **global** `styles.scss`, not
`network-settings.scss` - Angular's emulated view encapsulation means a class PrimeNG applies
from its own internal template can't be reached by a scoped selector in the consuming
component's own stylesheet, the identical issue `[inputStyleClass]` on `p-inputnumber` already
hit for the rate-limit fields.

## Testing

- `Rc4CipherTest`: published RC4 test vectors, round-trip, `discard()` behavior, stream
  wrappers round-tripping arbitrary multi-read/write data.
- `DiffieHellmanTest`: both sides agree on the same shared secret, different key pairs produce
  different secrets, wire-format round-trip, and the `P.bitLength() == 768` regression guard
  described above.
- `MseHandshakeTest`: real `negotiateOutbound`/`negotiateInbound` driven against each other
  over loopback sockets (matching `PeerConnectionTest`/`PeerServerTest`'s existing convention)
  - `PREFERRED`-vs-`PREFERRED` and `REQUIRED`-vs-`REQUIRED` round trips with real message
  exchange both directions, correct SKEY matching among several candidate torrents, correct
  rejection of an unrecognized torrent, the `IA`-embedding interop case via a hand-rolled fake
  initiator (since `negotiateOutbound` itself never embeds `IA`), and `REQUIRED` rejecting a
  peer that only offers plaintext.
- `PeerConnectionTest`: a real MSE negotiation completing through `connect()` (via
  `negotiateInbound` standing in for a real remote peer), `PREFERRED` falling back to a fresh
  plaintext connection against a legacy-only fake peer, `REQUIRED` failing outright against one.
- `PeerServerTest`: inbound routing correctly recovers the info hash from a real MSE
  negotiation (via `negotiateOutbound` standing in for a real remote peer), `REQUIRED`
  rejecting a plaintext connection attempt outright.

## Stability ([[0051-stability-as-a-standing-consideration]])

- DH modexp per connection is cheap (roughly a millisecond for a 768-bit prime) and bounded by
  the existing per-torrent `MAX_CONNECTIONS` cap - no new unbounded growth.
- Inbound SKEY matching costs one hash comparison per currently-active torrent per incoming
  connection attempt - bounded by torrent count, the same shape as other already-accepted
  per-torrent-count-scaling costs elsewhere in this codebase.
- The existing `HANDSHAKE_TIMEOUT_MS` socket timeout already bounds a hung or garbage
  negotiation attempt on both the outbound and inbound path, since it's a plain socket-level
  timeout applying regardless of what's being read.
- No new load test - existing connection-level coverage plus the new MSE-specific tests above
  are enough for this feature's actual risk profile (a fixed per-connection negotiation cost,
  not a shared-resource-contention concern the way file handles or piece verification were).
  Noted explicitly, per 0051's own spirit, rather than silently skipped.

## Alternatives considered

- **Skip `PREFERRED`'s outbound fallback, offer only `DISABLED`/`REQUIRED`** - rejected;
  `PREFERRED` (attempt encryption, tolerate a peer that can't) is the mode most real clients
  default to and the one that gives most of MSE's benefit without excluding any peer a
  plaintext-only connection would have reached.
- **Embed `IA` on the outbound side too**, to save a round trip - rejected for this pass;
  correctness (handling a peer's own embedded `IA` on the inbound side) mattered far more than
  shaving one round trip off our own outbound connection setup, and skipping it kept
  `negotiateOutbound` simpler.
- **A dedicated MSE load/stress test**, mirroring [[0049-many-torrents-load-test]] - rejected;
  see the Stability section above for why this feature's risk shape doesn't call for it the
  way the file-handle pool and verification throttling did.
