# 0023 — UDP tracker client (BEP 15)

**Status:** Accepted

## Decision

Driven by real-world testing: the Ubuntu ISO torrent used to validate
Phase 1 end-to-end (see [[0022-multi-tracker-fallback]]) had ~40 trackers,
only 2 of them HTTP(S) and both dead - the swarm was only reachable via
UDP trackers, which Phase 1 didn't support. Rather than continue testing
against a multi-GB file that takes too long to meaningfully validate,
this brings forward Phase 2's UDP tracker item from [[0009-phased-scope]]
so a smaller torrent (realistically UDP-tracker-dependent, like most
modern torrents) can be used for testing instead.

**`UdpTrackerClient`** implements `TrackerClient` directly (BEP 15's
connect-then-announce handshake over a `DatagramSocket`), so it slots into
the existing `MultiTrackerClient` tier structure with no changes to that
class - a tier can now freely mix HTTP(S) and UDP trackers.
`TorrentEngine.selectTrackerTiers` now accepts `udp://` URLs alongside
`http://`/`https://` (previously UDP entries were filtered out entirely);
`createSingleTrackerClient` picks `UdpTrackerClient` or `HttpTrackerClient`
based on URL scheme. Only DHT-only torrents (no trackers at all) remain
unsupported.

**Two deliberate simplifications versus strict BEP 15:**
- **No connection ID caching/reuse across announces.** BEP 15 allows
  reusing a connection ID for repeated announces within its ~1 minute
  validity window. `UdpTrackerClient` does a fresh connect handshake on
  every single `announce()` call instead. Given `TorrentSession` announces
  infrequently (every 30+ minutes for re-announce), the extra round-trip
  is negligible, and skipping expiry-tracking state keeps the class
  simpler.
- **Retry policy is much tighter than BEP 15's suggestion.** The spec
  suggests up to 8 attempts with a `15 × 2^n` second timeout - over an
  hour of total worst-case latency for one dead tracker. That's
  impractical here for two reasons: `TorrentSession.start()`/`reannounce()`
  call `TrackerClient.announce()` synchronously, and `MultiTrackerClient`
  may need to fall through *several* dead trackers in one tier before
  finding a working one (real torrents, per the motivating example above,
  can have dozens). `UdpTrackerClient` defaults to 2 attempts starting at
  3 seconds (doubling each retry, ~9s worst case per dead tracker) -
  still tolerant of a single lost packet, without compounding into
  minutes of latency across a tier of mostly-dead trackers. The base
  timeout and attempt count are constructor parameters (a package-private
  overload) specifically so tests aren't stuck waiting on the production
  timeout.
- **`key` is generated once per client instance**, not per announce - it
  exists so the tracker can recognize the same client across IP changes,
  which requires it to stay stable across calls from the same client.

**Not implemented**: scrape (BEP 15's peer-count-only query without a
full announce) - not needed for anything Phase 1/2 currently does.

**Real bug found on first real-world use**: the tracker address was
originally resolved once in the constructor via
`new InetSocketAddress(String hostname, int port)`, cached, and reused for
the client's whole lifetime. That constructor resolves the hostname
immediately but does *not* throw on failure - it silently produces an
"unresolved" address, and the failure only surfaces later
(`IllegalArgumentException: unresolved address`) when something tries to
actually use it, e.g. `DatagramPacket`'s constructor. Worse, caching the
resolution at construction time meant a single transient DNS failure (or a
tracker's IP changing via dynamic DNS) would have permanently broken that
tracker for the rest of the `TorrentSession`'s life, since the client is
constructed once and reused for every subsequent announce. Fixed by
resolving fresh on every `announce()` call via `InetAddress.getByName(host)`,
which throws `UnknownHostException` immediately and is wrapped into a
clear `TrackerException` - `connect`/`sendAnnounce`/`sendWithRetry` now
take the resolved address as a parameter instead of reading a cached field.

## Testing

`UdpTrackerClientTest` uses a raw `DatagramSocket` as a fake UDP tracker
speaking BEP 15 directly, matching the project's established pattern of
testing against a real local server rather than mocking (see
[[0013-http-tracker-client]]). Covers: full connect+announce round trip
with compact peer parsing, the tracker returning an `ACTION_ERROR`
response, retries exhausting when the tracker never responds (using the
short-timeout test constructor), and rejecting a non-`udp://` URL.

`TorrentEngineTest`'s tracker-tier-selection tests were updated: UDP URLs
are now kept rather than dropped, and the "no usable tracker" test uses an
actually-unsupported scheme (`ftp://`) instead of `udp://`, since UDP is
no longer the thing being excluded.

## Alternatives considered

- **Full BEP 15 retry/backoff schedule** - rejected as impractical given
  `MultiTrackerClient` may serially exhaust several dead trackers in one
  `announce()` call; see above.
- **Concurrent ("racing") tracker attempts within a tier**, trying all of
  a tier's trackers in parallel and using whichever responds first -
  would reduce worst-case latency further when many trackers in a tier
  are dead, but is real added complexity (executor management,
  cancelling in-flight requests once one succeeds). Not built now; the
  tighter sequential retry policy above is judged sufficient until proven
  otherwise in practice.
