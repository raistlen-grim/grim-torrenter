# 0078 — IP blocklist

**Status:** Accepted

## Decision

An optional IP blocklist: peers whose address falls in a listed range are never connected to,
never accepted, and never even enter a torrent's candidate pool. First of two slices picked from
the missing-feature review (proxy support is the second, its own doc); confirmed with the user
that the two are split and the blocklist goes first.

### Source: one field, a file path or a URL

`Settings.blocklistSource` takes either a local file path (a relative path resolves against the
config directory) or an `http(s)://` URL. `blocklistEnabled` is a separate toggle so a source can
be kept while turned off, and `blocklistRefreshHours` (default 168 = weekly; 0 = never refresh
automatically) governs how often a URL is re-downloaded. All three are live. A file is re-read
when its modification time changes; a URL is downloaded to `configDirectory/.grimtorrenter-
blocklist-cache` (plus a `.meta` sidecar naming the source it came from) so a restart, or being
offline, keeps enforcing the last good list instead of running unprotected until the first
download finishes. A failed refresh keeps the previous list in force and records the error - it
never silently drops protection.

### Formats

Auto-detected line by line, `#`/`//` comments and blanks ignored, unparseable lines counted and
skipped (a stray line never fails a whole list): PeerGuardian `.p2p` (`Name:1.2.3.4-1.2.3.9`),
eMule `.dat` (`1.2.3.4 - 1.2.3.9 , 100 , Name`), plain `a.b.c.d-a.b.c.d` ranges, CIDR
(`1.2.3.0/24`) and single addresses. A gzip stream (magic bytes `1f 8b`) is decompressed
transparently - most public lists ship as `.gz`. **IPv4 only**: this engine's peer discovery is
IPv4-only (compact tracker peers, PEX `added`), so an IPv6 line is skipped and an IPv6 peer is
never blocked (it can't occur today).

### Data structure

`IpRangeSet`: ranges collected as `long` start/end pairs, sorted, and overlapping/adjacent ranges
merged into two parallel `long[]`; `contains()` is a binary search. A few hundred thousand ranges
is a few MB and a lookup is O(log n) with no allocation. The current set is one volatile
reference swapped atomically on reload, so readers never see a half-built list and the reload
takes no lock a connection path could wait on.

### Enforcement

Every point an address can enter, so a blocked peer is stopped as early as it can be:

- **Discovery:** `TorrentSession.recordKnownPeers()` drops blocked addresses before they reach
  `knownAddresses`, covering tracker, DHT, PEX and LSD in one place; magnet metadata fetching
  filters its candidate rounds the same way.
- **Outbound:** `fillConnections()` also skips them, which matters once a *reload* newly blocks an
  address already in the pool.
- **Inbound:** `PeerServer` rejects a blocked TCP peer before reading a byte (so it never costs an
  MSE Diffie-Hellman exchange), and both `acceptIncomingConnection()` and
  `acceptIncomingUtpConnection()` check again as the universal net (µTP has no earlier point).
- **Live connections:** when a reload changes the list, every session drops known addresses and
  closes established connections that are now blocked.

The filter reaches sessions and `PeerServer` through a setter (`setIpFilter`, defaulting to a
no-op `IpFilter.NONE`), not another widest `create()`/`restoreAsync()` overload: unlike file
priorities ([[0075-file-priorities]]) nothing needs the filter before a peer address is known,
and peers are only known after a network round trip, long after the setter runs.

Not filtered (deliberately, first slice): DHT routing-table nodes, and LSD/tracker *replies* are
filtered as peers but not as servers - a blocklist is about who we exchange torrent data with.

### Visibility

`GET /api/blocklist` returns a status (enabled, source, range count, when it loaded, last error,
whether a load is in flight, and a count of addresses blocked since startup); `POST
/api/blocklist/reload` forces a reload now. A new Settings group holds the three fields and shows
that status with a Reload button. Two new library events, `BLOCKLIST_UPDATED` (with the range
count and source) and `BLOCKLIST_FAILED` (with the reason), are engine-wide like
`SERVER_STARTED` ([[0055-library-events]]).

## Stability

- **Unbounded growth:** a download is capped at 32 MB, a decompressed stream at 64 MB, and a
  list at 1,000,000 entries - past any of these the load *fails* (previous list kept) rather than
  truncating silently. A gzip bomb can't exhaust memory.
- **Locking:** reads are one volatile reference and a binary search - no lock, no allocation, on
  the connection hot path ([[0007-concurrency-model]]). Only one load runs at a time (an
  `AtomicBoolean`), on its own virtual thread, never on the maintenance scheduler's shared thread.
- **Hostile input:** a list is untrusted text/bytes - octets are range-checked, lines are
  independently parsed, and a peer can't influence the list at all. The URL is fetched with
  connect/request timeouts, redirects limited to http(s), and only a `200` is accepted. It is
  fetched by whoever can edit settings (an authenticated admin), so an internal-network URL is
  their own choice, not an injection vector.
- **Failure and cleanup:** every exit path leaves the previous list in force; the download temp
  file is deleted in a `finally`; a corrupt cache is ignored and re-fetched.
- **Cost:** two extra checks per candidate address and one per inbound connection.

## Deferred

- Filtering DHT nodes, IPv6 ranges, per-torrent bypass, hit statistics per range.
- ~~Fetching the list through a configured proxy - waits on the proxy slice.~~ **Done (2026-09-20)** -
  the download goes through the SOCKS5 proxy when one is active, following up to 5 redirects; see
  [[0079-socks5-proxy]].
