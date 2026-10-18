# Code review 02 — grimtorrenter-app

Reviewed 2026-10-17, after the engine A/B fixes. Scope: everything under
`grimtorrenter-app/src/main` (~2.9k lines, ~50 files) plus `application.properties`. Tests were
not reviewed. Paths are relative to `grimtorrenter-app/src/main/java/com/grimtorrenter/app/`.

**Confirmed** means the failure path was traced through the code. **Plausible** means it depends
on framework behaviour that wasn't verified here.

Longer-term structural items are in `../TECH_DEBT.md`.

---

## A. Fix before release

**Status (2026-10-17): A1–A3 fixed, with tests. See design_docs/0093.**
- **A1:** REST now requires a marker header (not an Origin/Host comparison, so it works behind
  any proxy); the WebSocket gets an Origin check. DNS rebinding remains in TECH_DEBT.
- **A2:** also hardened the engine side. Every periodic task runs through `GuardedTask`, and
  listener calls are guarded.
- **A3:** atomic writes, and `auth.json` is now 0600.

### A1. With auth off (the default), any web page the user visits can drive the API (Confirmed, high)
Nothing checks `Origin`, `Host` or a custom header. Browsers send "simple" cross-origin requests
(POST with a `text/plain`, `multipart/form-data` or empty body) without a CORS preflight. The
response is unreadable to the attacker, but the request still runs. Endpoints reachable this
way:
- `POST /api/torrents/magnet` (`text/plain`) and `POST /api/torrents` (multipart). A
  drive-by page can make the user's server download whatever it likes into their downloads
  folder.
- `POST /api/torrents/{hash}/pause`, `resume`, `recheck`, `reannounce`;
  `POST /api/blocklist/reload`; `POST /api/proxy/test`.
- **The WebSocket** (`/ws/torrents`): WebSockets aren't covered by CORS, so any page can open
  it and read the full torrent list and every library event (cross-site WebSocket hijacking).
- DNS rebinding goes further: an attacker's hostname re-pointed at the LAN IP becomes
  "same-origin", so even the JSON `PUT`/`DELETE` endpoints work, because nothing checks `Host`.

Auth-enabled deployments are safe from the first two, since the bearer token isn't sent
automatically. But auth is off by default and the Health page calls that "fine on a private
network", which this undermines: a private network is exactly where the browser sits.

Fix: add a filter for `/api/*` and an `@OnOpen` check for `/ws` that rejects any request whose
`Origin` header is present and doesn't match the request's own scheme/host/port. Optionally
also require a custom header such as `X-Requested-With` on state-changing methods. Same-origin
Angular calls send a matching `Origin` (or none for GET), so the frontend needs no change
beyond the WebSocket. For rebinding, an optional `grimtorrenter.allowed-hosts` setting checked
against `Host`.

### A2. A failing event write is thrown into the engine (Confirmed, high)
`JsonLinesEventStore.record()` (`:80-90`) throws `UncheckedIOException` when the append fails.
Disk full is the obvious cause, and a torrent client filling its disk is a normal event. Its
callers are inside the engine:
- `TorrentSession.setState()` → `TorrentEventListener.onStateChanged()` → `record()`. The
  exception escapes mid-transition. In `fail()`, for example, the state is already ERROR but
  `shutdownNetworking()`/`closeStorage()` never run.
- `TorrentEngine.checkSeedingLimits()`, the blocklist reload and the health storage checks all
  call `record()` from `ScheduledExecutorService` tasks. **An exception from a
  `scheduleWithFixedDelay` task cancels every later run**, so seeding limits stop being
  enforced until the next restart, without anything being logged.
- `TorrentEngine.addTorrent()` records ADDED after the session is registered, so the REST call
  returns 500 for a torrent that was in fact added.

`TorrentEventListener.broadcast()` likewise throws `IllegalStateException` on a serialisation
failure.

Fix, on both sides:
- `record()` and the listener catch everything and log; persisting or pushing an event is
  never worth failing the caller over.
- Engine follow-up (missed in review 01): wrap each `maintenanceScheduler` task and each
  `listener.onStateChanged` call in a catch-all that logs, so no single collaborator can stop
  the engine's periodic work or break a state transition.

### A3. A torn `settings.json` or `auth.json` stops the app starting (Confirmed, high)
`JsonSettingsStore.writeToDisk()` (`:81-87`) and `JsonAuthStore.writeToDisk()` (`:104-110`)
write straight over the live file. A crash or power loss mid-write leaves it truncated. The
`@PostConstruct` `readOrCreateDefault()`/`readOrSeed()` then throws `UncheckedIOException`, so
startup fails and the container restart-loops until someone edits the file by hand.

Fix: write atomically (temp file + `ATOMIC_MOVE`, the same pattern the engine now uses
everywhere). Keep failing on a *corrupt* `settings.json` rather than falling back to defaults:
defaults mean `authEnabled=false`, and silently opening the API is worse than not starting.
Also make `auth.json` owner-only (0600), as `ProxyConfig` already does for the proxy password.

---

## B. Should fix

**Status (2026-10-17): B1–B9 fixed, with tests. See design_docs/0094.**
- **B1:** a password change revokes every session except the caller's.
- **B2:** the backoff moved into its own `LoginThrottle` (also clears that C smell).
- **B6:** also found and fixed `NotAuthorizedException("msg")` treating the message as the
  `WWW-Authenticate` challenge.
- **B8:** the frontend's remove now treats a 404 as already removed.

### B1. Logout, password changes and expiry don't end existing access (Confirmed)
- `AuthResource.changePassword()` (`:75-87`) leaves every issued token valid. A leaked or
  stolen session survives the obvious remedy. Revoke all tokens on a password change; the
  caller logs in again with the new password.
- `TorrentWebSocket` validates the token only in `@OnOpen` (`:60`). After logout, token
  expiry, a password change, or auth being switched on, an already-open socket keeps receiving
  every broadcast indefinitely. Keep the token with the connection and drop connections whose
  token no longer validates, for example on the 2-second snapshot tick.

### B2. The login throttle can be raced (Confirmed)
`AuthResource.login()` checks `isLockedOut()`, *then* verifies, *then* records the failure
(`:50-56`). Concurrent requests fired as the lockout lapses all pass the check, so each gets a
guess, and each costs a full 210k-iteration PBKDF2, which is also a CPU-exhaustion lever.
Allow one verification at a time (`Semaphore(1).tryAcquire()` → 429 when busy), or reserve
the attempt atomically before verifying.

### B3. One torn event line breaks the event log for a month (Confirmed)
`JsonLinesEventStore.readAll()` (`:110-129`) throws on the first unparseable line. A crash
mid-append leaves exactly that. `GET /api/events` then returns 500 until that day-file falls
out of the retention window (30 days by default). Skip and log bad lines. Also, `prune()`
throws on a delete failure, and it runs in `@PostConstruct`, so a single undeletable old
file stops startup.

### B4. A WebSocket whose send failed is dropped from the set but left open (Confirmed)
`TorrentWebSocket.broadcast()` (`:99-111`) removes the connection from `CONNECTIONS` on a
send failure or timeout but never closes it. A browser tab that hiccuped once, for example
after a laptop sleep, keeps an open socket that receives nothing ever again. Its
reconnect logic never runs because the socket never closes, so the UI silently goes stale.
Close the connection on failure.

### B5. TorrentEngineException always maps to 400, including for server failures (Confirmed)
`TorrentEngineExceptionMapper` turns every `TorrentEngineException` into 400 "bad request".
The engine throws it for user input (no usable tracker) **and** for server-side I/O failures:
"Could not persist state…", "Could not delete…", "Could not scan config directory…". A failed
delete-with-data reads to the client as a malformed request. Split the exception (for example
`TorrentEngineException` for bad input and an unchecked I/O failure type for server errors →
500), or carry a kind on it.

### B6. Error bodies come in two shapes (Plausible)
`ErrorResponses.badRequest()` and the exception mappers return `{"error": "..."}`. Most
resources instead `throw new BadRequestException(msg)` or
`new WebApplicationException(msg, status)`, which Quarkus REST sends with **no body**: the
message only reaches the server log. Examples: "At most 20 labels per torrent", "A label named
X already exists", "Unknown file priority", "Set a password before enabling authEnabled". The
frontend reads `err.error.error` only on the two add flows. Under 0071 (thin frontend), every
4xx should carry the server's reason in one shape. A single `ExceptionMapper<WebApplicationException>`
(or `@ServerExceptionMapper`) that writes `{"error": message}` would do it. The "no body"
part is Quarkus default behaviour that wasn't confirmed here; check with one curl.

### B7. Settings have no upper bounds (Confirmed, low)
`SettingsResource.update()` checks formats (times, URLs, proxy host/port) but not ranges. The
`Settings` constructor only replaces values ≤ 0. An authenticated client can set
`magnetFetchConcurrencyLimit=1000000` (effectively unbounded concurrent dials),
`lsdAnnounceIntervalSeconds=1` (multicast flood), `dhtRefreshIntervalSeconds=1`, or a huge
`maxConnectionsPerTorrent`. A `null` body is an NPE → 500. It's the admin's own foot-gun, but
these are cheap to clamp at the boundary.

### B8. Inconsistent 404 handling (Confirmed, low)
`DELETE /api/torrents/{hash}`, `pause` and `resume` return 204 for an unknown torrent; every
other per-torrent endpoint returns 404. A client can't tell "done" from "no such torrent".

### B9. Snapshots are built every 2 s with no clients connected (Confirmed, low)
`TorrentSnapshotScheduler.broadcastSnapshot()` serialises every torrent (`TorrentView.from`
calls several O(pieces) engine methods) and the label list every 2 s, even when no socket is
open. That's most of the time for a headless server. Skip the tick when there are no
connections.

---

## C. Code smells and duplication

**Status (2026-10-18): all fixed. See design_docs/0100.** The directories went into an
`AppDirectories` bean instead of a whole-prefix `@ConfigMapping` (only they were duplicated). The
DTO policy is decided and listed there, not rewrapped. The module-wide comment pass stays in
TECH_DEBT.

- **Config properties are duplicated.** `grimtorrenter.config-directory`,
  `download-directory` and `watch-directory` are declared with their default strings
  separately in `TorrentEngineProducer`, `SystemResource`, `HealthService`,
  `JsonSettingsStore`, `JsonAuthStore` and `JsonLinesEventStore`. If one default changes and
  another doesn't, the app silently reads two different directories. One `@ConfigMapping`
  interface would fix it.
- **Four identical exception mappers** (`Bencode`, `MagnetLink`, `Metainfo`,
  `TorrentEngine` → 400). One mapper over a shared base exception, or one
  `@ServerExceptionMapper` method per type in a single class.
- **Static global state.** `TorrentWebSocket.CONNECTIONS` and the static `broadcast()` are
  called from three other beans (`TorrentEventListener`, `TorrentSnapshotScheduler`,
  `JsonLinesEventStore`). It's hidden coupling and hard to test. Inject a
  `WebSocketBroadcaster` bean instead.
- **Duplicated store code.** `JsonSettingsStore` and `JsonAuthStore` are the same
  read-or-seed / write-then-swap code twice; extract one `JsonFileStore<T>` (it's also
  where A3's atomic write goes).
- **`SessionTokenStore` does two jobs**: session tokens and login throttling. B2 is easier to
  fix with the throttle in its own class.
- **Inconsistent DTO policy.** Some endpoints wrap engine records in views (`TorrentView`,
  `DhtStatusView`, `ServiceStatusView`). Others return engine types directly: `Settings`,
  `SeedingLimitOverride`, `TorrentLimitOverride`, `Blocklist.Status`,
  `TorrentEngine.ProxyStatus`, `Socks5.TestResult`, `Label`, `LibraryEvent`. A rename inside
  the engine silently changes the public API. Each case was a deliberate call
  (design_docs/0045), but together they make the REST contract hard to see in one place.
- **Duplicate free-space code.** `SystemResource.diskUsage()` and
  `HealthService.freeSpaceCheck()` both compute free space, each creating the directory
  first.
- **Stale or historical comments** (same theme as review 01):
  - `application.properties`: "Placeholder - populated as REST/WebSocket resources are
    added."
  - `TorrentWebSocket`: "Broadcast-only in Phase 1", and "not yet confirmed by actually
    compiling it" in two places. It has been compiled and shipped for months.
  - `TorrentEngineProducer`: config dir holds "currently just the two DHT marker files". It
    now holds every per-torrent marker.
  - `SessionTokenStore` cites a PROGRESS.md flake.

## What looked good
- Tokens are opaque `SecureRandom` values; PBKDF2 uses OWASP iterations and a constant-time
  compare.
- The password lives apart from `settings.json`, and the proxy password is write-only.
- The bearer token goes in the WebSocket subprotocol, not the URL.
- Settings writes are disk-first, then in memory.
- `TorrentResource` returns real resources from actions (recheck, priorities, labels), in line
  with 0071.
- `HealthService` is careful: cached background proxy probe, one event per new storage
  failure, and evidence-based connectivity messages.
- The SPA fallback is correctly kept outside the auth filter and excludes `/api` and `/ws`.
