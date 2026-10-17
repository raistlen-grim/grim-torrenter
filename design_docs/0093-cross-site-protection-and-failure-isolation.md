# 0093 - Cross-site request protection, and failures that stay where they happen

Status: Accepted (2026-10-17). Found in the pre-release app review (`code_review/02-app.md`,
items A1-A3). Builds on [[0061-authentication]] (A1), [[0055-library-events]] and
[[0041-live-settings-store]] (A2/A3). Applies [[0051-stability-as-a-standing-consideration]].

## 1. Cross-site requests (A1)

### Problem

Auth is off by default ([[0061-authentication]]), and the Health page tells users that's fine on
a private network. But the browser is *on* that network, and a web page from anywhere can make
it send requests to the app:
- A cross-site POST with a `text/plain`, multipart or empty body is a "simple" request. The
  browser sends it without asking first and only hides the response. That reached adding a
  magnet or torrent (the server downloads whatever the attacker picks), pause, resume, recheck,
  reannounce, blocklist reload and proxy test.
- WebSockets aren't covered by CORS at all, so any page could open `/ws/torrents` and read
  every torrent and library event.

With auth on, the bearer token isn't sent automatically, so REST was already safe; the socket
was not, if it already held a token.

### Decision

**REST - a marker header, not an Origin/Host comparison.** `CrossSiteRequestFilter` (runs
before `AuthenticationFilter`) refuses, with 403, any non-safe method (not GET/HEAD/OPTIONS) on
`/api/*` that carries an `Origin` header but no `X-Requested-With` header.
- Every browser sends `Origin` on cross-origin POST/PUT/DELETE.
- A page on another origin can't add a custom header without a CORS preflight, and this app
  never approves one, so only our own frontend can send the marker. `authInterceptor` adds it to
  every request.
- No `Origin` means a non-browser client (curl, scripts, the test suite), which CSRF can't come
  from, so it's left alone. Existing API scripts keep working.

Comparing `Origin` with `Host` was rejected for REST because reverse proxies commonly rewrite
`Host` to their upstream's name (nginx's default `proxy_pass`). That would have broken every
write behind such a proxy, even though the TLS-proxy setup is exactly what the README recommends.

**WebSocket - an Origin check, with an escape hatch.** A browser can't add headers to a WebSocket
handshake, so `OriginPolicy` checks `Origin` in `TorrentWebSocket.onOpen` before the token check.
A handshake is allowed when:
- it has no `Origin` (not a browser); or
- the `Origin`'s host equals the `Host` header's host (and, if `Host` names a port, the ports
  match too); or
- the `Origin`'s host equals the first `X-Forwarded-Host` entry, which a page can't set on a
  handshake; or
- the `Origin` is listed in `grimtorrenter.allowed-origins` (`GRIMTORRENTER_ALLOWED_ORIGINS`),
  for proxies that rewrite `Host` and send no `X-Forwarded-Host`.

The README tells nginx users to pass `Host` through, or to set the property.

**Not covered: DNS rebinding.** An attacker's hostname re-pointed at the LAN address makes the
page same-origin as far as the browser is concerned, so neither check can tell it apart. The
defence is a `Host` allowlist; it's listed in TECH_DEBT as an optional
`grimtorrenter.allowed-hosts`, because requiring users to configure their host name is a bigger
deployment change than a pre-release fix should make.

## 2. A failing collaborator stays contained (A2)

### Problem

`JsonLinesEventStore.record()` threw when an append failed. A full disk is the likely cause,
and a torrent client filling its disk is a normal event. Its callers run deep inside the engine:
`TorrentSession.setState()` via the listener (which aborted transitions halfway, so `fail()`
never reached its teardown), and the engine's `ScheduledExecutorService` tasks. **An exception in
a `scheduleWithFixedDelay` task cancels every later run, without anything being logged.** The
seeding-limit check, watch folder, DHT upkeep and lifetime-stats flush could each stop for good on
one bad tick. The session's own tasks (reannounce, PEX, choking, keep-alives, refill, DHT
discovery) had the same exposure to their own exceptions.

### Decision

Defence on both sides of the boundary:
- **The event store and listener never throw.** `JsonLinesEventStore.record()` and
  `TorrentEventListener.onStateChanged()` catch everything, log at ERROR, and carry on. The
  event is lost; the operation that produced it isn't.
- **The engine doesn't trust its collaborators.** `TorrentSession` wraps `listener.onStateChanged`
  and `onPieceCompleted` in a catch-all. Every periodic task in `TorrentEngine` and
  `TorrentSession` runs through the new `GuardedTask.of(name, task)`, which logs and returns, so
  the task runs again on its next tick.

## 3. Config files are written atomically (A3)

### Problem

`settings.json` and `auth.json` were written over the live file. A crash or power cut mid-write
left a truncated file, `@PostConstruct` failed to read it, and the container restart-looped until
someone fixed the file by hand.

### Decision

- Both are written through `AtomicJsonFile`: a temp file in the same directory, then
  `ATOMIC_MOVE`. A reader sees the old file or the new one, never a torn one.
- `auth.json` is now owner-only (0600), like the proxy password file. That's set on every
  write, and once at startup for files written by earlier versions.
- **A `settings.json` that is corrupt anyway (hand-edited, say) still fails startup.** It
  deliberately doesn't fall back to defaults, because the defaults have `authEnabled` off:
  silently opening a protected API is worse than refusing to start.

## Stability

- A1 adds two header checks per request and per handshake; no state.
- A2 is entirely about stability. Nothing new is retained, failures are logged once per
  occurrence, and the only behaviour change is that work continues where it used to stop.
- A3: every write deletes its own temp file in `finally`. Only a process killed between
  creating the temp file and renaming it leaves one behind. It's never read, and costs the size
  of one settings file.

## Alternatives considered

- **SameSite cookies / CSRF tokens:** this app has no cookies (the bearer token is in a header or
  subprotocol), and a synchronizer token would be a new concept for every API client to learn
  ([[0071-thin-frontend-as-a-standing-consideration]]). The marker header gets the same browser
  guarantee with nothing to fetch first.
- **Always require the marker header, Origin or not:** would break every existing script and the
  test suite, and adds no protection, because the requests it would additionally refuse can't
  come from a browser.
