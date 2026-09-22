# 0079 — SOCKS5 proxy support

**Status:** Accepted

## Decision

An optional SOCKS5 proxy (host, port, optional username/password) that outbound traffic goes
through, so the real address isn't what peers, trackers and list hosts see. Second of two slices
from the missing-feature review, after [[0078-ip-blocklist]]. Established clients were surveyed
first - libtorrent/qBittorrent proxy peers and trackers and offer a strict "block anything that
can't use the proxy" mode, Transmission proxies trackers only - and the design follows the strict
model with the one default this project chose deliberately (below).

### What goes through the proxy

- **Outbound peer connections** (TCP, including the MSE-then-plaintext retry and a magnet's
  metadata fetch): a SOCKS5 CONNECT tunnel per connection.
- **HTTP(S) trackers**: a tunnel plus a small HTTP client (`MiniHttp`), TLS layered over it with
  hostname verification. The tracker's hostname is handed to the proxy to resolve.
- **UDP trackers**: SOCKS5 UDP ASSOCIATE - each announce opens an association, wraps its two
  BEP 15 datagrams in the SOCKS UDP header, and closes it. Included because most public torrents
  (including the project's own test torrents) announce mainly to UDP trackers; without it a proxied
  client would find almost no peers. A proxy that doesn't support UDP fails those announces with a
  clear message rather than falling back to a direct one.
- **The blocklist download** ([[0078-ip-blocklist]]), including up to 5 redirects.

The SOCKS5 client is hand-written (`Socks5`) rather than the JDK's built-in SOCKS support: a
hostname always goes to the proxy unresolved (no local DNS leak), credentials aren't a JVM-wide
`Authenticator`, and the JDK has no UDP ASSOCIATE. The JDK's own HTTP client can't use a SOCKS
proxy at all, hence `MiniHttp` (HTTP/1.0 GET, `Connection: close`, bounded body, redirects, an
overall deadline).

**A proxy that fails is a failed connection, never a fallback to direct.** No code path retries
around it.

### "Block anything that can't use the proxy" - on by default

`Settings.proxyBlockUnsupported` (default **true**, confirmed with the user - qBittorrent
defaults it off, but a leak you don't know about is worse than fewer peers). While a proxy is
active and this is on, the things a SOCKS5 proxy can't carry here are never started: **DHT, µTP,
LSD** (all UDP) and the **inbound peer server**. Peers then come from trackers and PEX only. The
Services page shows those as disabled with the reason. These are created once at engine
construction, so this is **restart-required**, like their own enable flags: `GET /api/proxy`
reports `restartRequired` when the saved settings would block them but this run didn't (or the
reverse), and the UI says plainly that until then they still use the real address. Turning the
switch off is allowed - it's the operator's call - and the UI warns what that exposes.

### Settings and the password

`proxyEnabled`, `proxyHost`, `proxyPort`, `proxyUsername`, `proxyBlockUnsupported` are ordinary
live settings; host/port/username changes apply to the next connection. The **password is not a
Settings field**: `GET /api/settings` echoes the whole record, and unlike the login password
([[0061-authentication]], compared not replayed, so hashed) a proxy password has to be sent to the
proxy, so it must be recoverable. It's stored in its own file, `.grimtorrenter-proxy-password`
(owner-only permissions where the filesystem supports them), written atomically, and **never
returned by any API** - `GET /api/proxy` only says whether one is set. It is plaintext at rest,
which is the same exposure every client with proxy auth has; anyone who can read the config
directory can read it.

A half-filled configuration (enabled but no host, or a bad port) is a 400 at the settings boundary
and is treated as "no proxy" if it somehow gets past.

### REST

- `GET /api/proxy` - `{active, host, port, hasPassword, blockUnsupported, blockingNow,
  restartRequired}`.
- `PUT /api/proxy/password` `{password}` / `DELETE /api/proxy/password` - write-only.
- `POST /api/proxy/test` - checks the *saved* settings end to end: is the proxy reachable, does it
  accept the credentials, does it relay UDP? Returns `{reachable, udpSupported, message}`.

## Stability

- **Unbounded growth / resources:** every proxied connection is one socket (peers) or one socket
  pair (a UDP association: the TCP control connection plus one UDP socket), both closed in
  `finally`/try-with-resources on every exit path; an association lives only for the two
  datagrams of one announce. A proxied HTTP response is capped (tracker 4 MB, blocklist 32 MB),
  redirects are bounded, header lines and count are bounded, and the whole exchange has a deadline
  enforced by closing the socket - a proxy or server that trickles bytes can't hold a thread open.
- **Hostile proxy or server:** SOCKS replies are length-checked, an unresolved BND.ADDR domain is
  never looked up locally, datagrams not from the relay are dropped, and a malformed reply is an
  `IOException`, never an unchecked crash.
- **Locking:** the proxy config is a volatile password field plus live `Settings` reads - no lock
  on a connection path ([[0007-concurrency-model]]).
- **Privacy failure modes, named:** (1) the block switch is restart-required, so between enabling
  a proxy and restarting, DHT/µTP/LSD/inbound still use the real address (surfaced in the UI);
  (2) existing connections stay direct until they churn; (3) with the block switch *off*, µTP
  attempts and DHT still go direct by the operator's choice.

## Deferred

- SOCKS4 / HTTP-CONNECT proxies, and UDP for DHT/µTP through the relay (their long-lived shared
  sockets would be a much larger change).
- Applying the block switch without a restart.
- Per-torrent proxy choice.
