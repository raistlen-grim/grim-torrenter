# 0061 — Authentication for the REST/WebSocket layer

**Status:** Implemented and build/test-verified (2026-09-07) - the full
`mvn test` reactor (364 tests, both modules) passes. Getting here found
and fixed four real bugs across several runs (a test-only StackOverflow, a
Quarkus/SmallRye config-defaults gotcha, a genuine login-lockout UX bug,
and a stale test assertion) - see "Implementation notes" below for each.
An unrelated, pre-existing flake in `TorrentSessionTest` (a real-socket/
real-timing concurrency test, part of the 2026-08-30/09-06 peer-connection
work, not touched by this doc's changes) surfaced once and did not
reproduce on a re-run - not counted as one of this feature's own bugs.
The WebSocket handshake-header check itself (`Sec-WebSocket-Protocol`) is
build-verified (it compiles) but still has no automated test exercising a
real connection either way (same gap [[0019-rest-and-websocket-layer]]
already notes for this class generally) - manual verification against a
real browser is the remaining open item before relying on it in
production.

## Context

Every endpoint (`TorrentResource`, `SettingsResource`, `EventsResource`,
`SystemResource`, `DhtResource`, `/ws/torrents`) is currently completely
unauthenticated. Raised by the user (2026-09-07): the REST API is one of this
implementation's real strengths (unlike wrapping an existing client, it's a
first-class, documented surface), but that strength is moot if exposing it
beyond localhost/LAN means exposing it to anyone. This doc covers what's
needed to let a user safely put GrimTorrenter on the internet.

## Decision

**No username, single shared password, bearer-token auth** — not
multi-user, not a cookie session. Confirmed with the user over HTTP Basic
and cookie-session alternatives (see below), and revised (2026-09-07) to
drop the username entirely: this app has exactly one torrent list per
deployment, not one per account, and a username field would misleadingly
imply otherwise. A single shared password is the whole credential.

- **The password is persisted in a new, dedicated store** —
  `AuthStore`/`auth.json` under `grimtorrenter.config-directory` (same
  directory as `settings.json`/`events/`, but a **separate file on
  purpose**: it holds a salted PBKDF2WithHmacSHA256 hash + iteration count
  (JDK built-in — no new crypto dependency, same reasoning
  [[0052-message-stream-encryption]] used for hand-rolling MSE rather than
  pulling in a library) and must never be reachable through
  `GET /api/settings`, which echoes the whole `Settings` record verbatim
  today. Loaded at startup and **genuinely live-updatable** via a new
  `PUT /api/auth/password` endpoint, no restart needed — unlike
  `dhtEnabled`/`acceptIncomingConnections`'s existing restart-required
  precedent ([[0041-live-settings-store]]).
- **An optional deploy-time `grimtorrenter.bootstrap-password`** (env var
  in the Docker image) seeds `auth.json` only if it doesn't exist yet at
  startup — a convenience for a first `docker run` so there's no window
  where the app is reachable with nothing set, without *requiring* an env
  var. If `auth.json` already exists, the env var is ignored on every
  later startup (same first-run-only behavior Grafana's own
  `GF_SECURITY_ADMIN_PASSWORD` uses) — a stale/leftover env var can't
  silently reset a password the user has since changed through the app.
- **`PUT /api/auth/password`** has two modes based on whether a password
  is currently stored: if `auth.json` has none yet, this call sets the
  initial one unconditionally (no more "open" than the rest of the API
  already is before any password exists); if one is already stored, the
  request must carry either a valid bearer token or the correct current
  password, or it's rejected. This is also how a user changes their
  password while the app keeps running, addressing the point that a
  deploy-time-only value can't do that.
- **`authEnabled`** stays a new, ordinary field on the live `Settings`
  record ([[0041-live-settings-store]]) — default `false`, live-toggleable.
  `SettingsResource.update()` rejects `authEnabled=true` with 400 if
  `auth.json` has no password stored yet — otherwise a user could flip the
  toggle with nothing to log in with and lock themselves out.
- **`POST /api/auth/login`** (just `{password}`, no username) → on
  success, issues an opaque random token (`SecureRandom`, not a JWT — no
  signing-key management, trivially revocable), held server-side in an
  in-memory `ConcurrentHashMap<token, expiry>` (`SessionTokenStore`).
  **`POST /api/auth/logout`** removes it. Tokens themselves don't survive a
  restart — an accepted tradeoff for a personal app, same category as the
  existing non-persisted upload/download byte counters
  ([[0054-seeding-limits]]) — but the password itself does, via `auth.json`.
- **REST enforcement**: a `@PreMatching ContainerRequestFilter` checks
  `Authorization: Bearer <token>` against `SessionTokenStore` on every
  `/api/*` request except `/api/auth/login`, only when `authEnabled` is
  true. Static frontend assets aren't behind this filter at all (they're
  served by Quarkus's static-resource handling, not JAX-RS) — necessary so
  a browser can load the login screen in the first place; this leaks no
  data since the Angular bundle contains no user data, only code.
- **WebSocket enforcement**: browsers cannot set custom headers on a
  `WebSocket` handshake, but the constructor's second argument
  (subprotocols) **is** carried as a real `Sec-WebSocket-Protocol` request
  header — so the frontend connects with
  `new WebSocket(url, ['bearer', token])` and `TorrentWebSocket`'s
  `@OnOpen` reads that header the same way the REST filter reads
  `Authorization`, closing the connection immediately if invalid/missing
  (while `authEnabled` is true). **Deliberately not a `?token=` query
  param** (the original design) — caught by an automated security review
  (2026-09-07): a reverse proxy in front of this app, which this doc
  itself tells operators to add for TLS, commonly logs the full request
  URL by default, so a query param would leak a long-lived credential into
  proxy access logs, undermining the very setup being recommended. Headers
  are far less commonly logged by default. No response-side subprotocol
  selection is needed — per RFC 6455 that's optional, and browsers
  complete the handshake fine without one. **Flagged as needing
  verification against `quarkus-websockets-next`'s actual
  handshake-header API once built** — written against the documented
  shape of `HandshakeRequest.header(String)`, not confirmed by compiling
  it, the same caveat [[0019-rest-and-websocket-layer]] already recorded
  for this class's `@OnOpen`/`@OnClose` usage. A plain header lookup is a
  safer bet to actually exist than the raw-query-string parsing the
  original design would have needed.
- **Brute-force protection**: a simple global (not per-IP — single user,
  and per-IP tracking behind a reverse proxy needs a trusted-proxy header
  policy that's easy to get wrong) failed-attempt counter with exponential
  backoff/lockout in `SessionTokenStore`, reset on a successful login.
- **Deliberately not implementing an IP-allowlist/subnet-bypass** ("trust
  requests from the LAN, only challenge external ones"). This is the exact
  shape of feature that produced a real qBittorrent WebUI CVE (a spoofable
  header was trusted to decide "is this request local"). If LAN-only trust
  is wanted, a reverse proxy in front of GrimTorrenter is the right place
  for it, not this app.

## Transport security is a prerequisite, not an alternative

Bearer-token auth (like every scheme considered here — Basic auth, and
cookie sessions too) only protects against someone who **doesn't have** a
valid credential. It does nothing against someone who can read the wire:
if traffic is plaintext HTTP, a man-in-the-middle captures the token (or,
for Basic auth, the actual password — worse, since it doesn't expire) off
the login request or any later request and replays it for as long as it
stays valid. This is not a weakness specific to tokens; it's true of any
credential sent over an unencrypted channel, cookie sessions included
(that's exactly what "session hijacking" is).

The only real defense is TLS. **Confirmed with the user (2026-09-07):
GrimTorrenter doesn't terminate TLS itself** (no cert management, ACME,
etc. planned) — anyone choosing to expose this to the internet is expected
to already understand that means fronting it with a TLS-terminating
reverse proxy (Caddy, Traefik, nginx), the same infrastructure already
floated above as the right place for LAN-bypass policy if that's ever
wanted. The README states this plainly rather than the app trying to
detect or enforce it. Two things this doc's own scheme can still do as
defense-in-depth (they reduce exposure, they don't replace TLS): short-ish
token TTL (limits how long a stolen token stays useful) and never logging
the token/password server-side.

## Stability considerations ([[0051-stability-as-a-standing-consideration]])

- **Unbounded growth**: `SessionTokenStore`'s token map needs eviction of
  expired entries (a periodic sweep on the existing `maintenanceScheduler`
  used by seeding limits/watch folder, or a check on every `validate()`
  call) — otherwise a long-running server accumulates one dead entry per
  login forever. Not unbounded in practice either way (bounded by how often
  one user logs in), but the sweep is cheap and avoids relying on "in
  practice."
- **Hostile-peer angle**: none — this only guards the management API, not
  the BitTorrent wire protocol surface, so it doesn't interact with
  [[0007-concurrency-model]]'s peer-connection concerns at all.
- **Concurrency**: `ConcurrentHashMap` for the token store and the failed-
  attempt counter needs an atomic increment (`AtomicInteger`/
  `LongAdder`-style, not a read-then-write race) — no `synchronized` needed,
  consistent with [[0007-concurrency-model]].
- **Cleanup on every exit path**: logout removes the token immediately;
  expiry removes it lazily/on sweep either way, so there's no path that
  leaks a token entry outside of the sweep interval.
- **Failure mode when misconfigured**: if `authEnabled=true` but `auth.json`
  has no password stored, the settings update is rejected up front (see
  above) rather than allowing a state where the app is both "exposed" and
  "unloggable-into."

## Alternatives considered

- **HTTP Basic Auth** — simplest possible, no server-side session state at
  all. Rejected: no real logout, credentials resent on every request
  (fine under TLS, but this app doesn't terminate TLS itself and shouldn't
  assume every deployment puts a proxy in front of it), and it's a worse
  fit for the REST-API-as-a-strength framing that motivated this doc.
- **Cookie-based session** — natural for the Angular UI (browser handles
  it automatically), but needs real CSRF protection on every
  state-changing endpoint once the cookie is ambient, and is a worse fit
  for a script/tool hitting the REST API directly (has to manage cookie
  jars instead of just holding a token). Rejected in favor of the bearer
  token, confirmed with the user.
- **Delegate entirely to a reverse proxy** (Authelia, Traefik forward-auth,
  nginx basic auth) — genuinely valid and still compatible with everything
  above (a proxy can sit in front of this regardless). Not chosen as the
  *only* answer because it would mean this app ships with zero
  in-the-box story for "make this safe to expose," pushing a mandatory
  extra piece of infrastructure onto every internet-facing deployment.

## Open questions

- Whether `bootstrap-password` being unset should be treated as "auth
  feature not available at all" — moot in practice once `PUT
  /api/auth/password` can set the initial password with no password
  stored, but worth a final look once that endpoint exists.

## Resolved parameters

- **Token TTL is a new live `Settings.authTokenTtlDays` field** (revised
  2026-09-07, at the user's request, from an original hardcoded constant)
  — default 30 days, sliding (refreshed on every successfully validated
  request, not fixed from login), editable from the Settings page's
  Security group alongside the password fields. `SessionTokenStore` reads
  it fresh from `SettingsStore` on every `issue()`/`validate()` call, the
  same "genuinely live" treatment `encryptionMode`/the rate limits already
  get — a change takes effect immediately, including for tokens already
  issued (each token's stored expiry is an absolute `Instant` computed
  from whatever the TTL was at that moment, not a live reference back to
  the setting, so an existing token's *next* refresh uses the new value,
  it doesn't retroactively rewrite one already in flight). Same **no**
  "0/negative means unlimited" treatment as `eventLogRetentionDays`/
  `watchFolderRetentionDays` — silently normalized to 30 by `Settings`'
  own compact constructor, not rejected at the REST boundary; a session
  store that can be told to never expire a token is exactly what this
  field exists to prevent.

## Implementation notes (2026-09-07)

Built end to end: `PasswordHasher`, `AuthStore`/`JsonAuthStore`/`auth.json`,
`SessionTokenStore` (token issuance/validation/sweep, lockout), `AuthResource`
(`/api/auth/status`, `/login`, `/logout`, `/password`), `AuthenticationFilter`,
`Settings.authEnabled`/`authTokenTtlDays`, `SettingsResource`'s
enable-without-a-password rejection, `TorrentWebSocket`'s handshake-token
check, and the full frontend (`AuthService`, an `authInterceptor` and
`authGuard`, a standalone `/login` route/page outside the app shell, and a
new Security settings group with the password fields and the session-length
control). Backend unit/integration tests were written alongside
(`PasswordHasherTest`, `JsonAuthStoreTest`, `SessionTokenStoreTest`,
`AuthResourceTest`), and the full `mvn test` reactor (364 tests, both
modules) now passes - see the real bugs found along the way, below.

A few things still worth a second look, none of them caught by the
automated test suite:

- **The `quarkus-websockets-next` handshake-header API is a guess.**
  `TorrentWebSocket.tokenFromSubprotocol()` calls
  `connection.handshakeRequest().header("Sec-WebSocket-Protocol")` -
  written against the documented shape of that API, not confirmed against
  its real signature (same caveat this class already carried per
  [[0019-rest-and-websocket-layer]]). If it doesn't compile, that's the
  method to fix. (Revised 2026-09-07 from an original `?token=` query
  param, after an automated security review flagged that a reverse proxy
  in front of this app - which this doc itself recommends, for TLS -
  would commonly log the full request URL including that token by
  default; the subprotocol/header approach avoids the URL entirely.)
- **A password change doesn't revoke already-issued tokens.** Deliberate,
  for now - `SessionTokenStore` has no reverse index from password version
  to issued tokens, and the sliding TTL means an abandoned session dies on
  its own regardless. Worth revisiting if it ever matters in practice.
- **An already-open WebSocket connection survives `authEnabled` being
  turned on mid-session.** The handshake check only runs at `@OnOpen` -
  a connection opened while auth was off keeps streaming until it
  naturally drops/reconnects, at which point the new handshake enforces
  the token. Not an externally exploitable gap (the connection had to
  already exist inside a trusted browser session before auth was turned
  on), just a minor asymmetry worth knowing about.
- **`SecuritySettings`'s own auto-login-after-password-save** (so the
  browser holds a fresh token before the user can also flip `authEnabled`
  on and save, which would otherwise 401 that very save) is a frontend-only
  convenience, not covered by any backend test - worth a manual
  end-to-end browser check.

**Real bugs found across the user's `mvn test` runs (2026-09-07), all fixed same-day:**

- **`SessionTokenStoreTest`'s own `createStore()` test helper called itself
  instead of `new SessionTokenStore()`** - `StackOverflowError` on every
  test in that class. Introduced by an overly broad find/replace across the
  file (renaming every `new SessionTokenStore()` call site to `createStore()`
  for consistency) that also matched the one call site *inside*
  `createStore()`'s own body. Fixed by restoring that one line to
  `new SessionTokenStore()`.
- **`AuthResourceTest` failed with `Failed to start quarkus`** -
  `grimtorrenter.bootstrap-password`'s `@ConfigProperty(defaultValue = "")`
  turned out not to work the way every other optional `String`
  `@ConfigProperty` in this codebase's precedent (`defaultValue = "config"`,
  `defaultValue = "downloads"`, etc.) suggested it would: Quarkus/SmallRye's
  build-time config validation treats an **empty-string** `defaultValue` on
  a `String`-typed property as "no default provided," and demanded the
  property exist in some config source, failing the whole app's startup
  for any test that actually boots Quarkus (only `AuthResourceTest` does -
  the others construct `JsonAuthStore` directly, bypassing CDI/config
  validation entirely, which is why they didn't catch this first). Fixed by
  declaring `bootstrapPassword` as `Optional<String>` instead (no
  `defaultValue` at all - `Optional`-typed config properties are inherently
  allowed to be absent), the standard idiom for a config property with no
  natural non-empty default.
- **`AuthResourceTest` then failed for real** (expected 200, got 429) once
  the two bugs above were fixed and the test actually ran end to end -
  `SessionTokenStore.recordFailure()`'s original backoff formula
  (`1L << failures`) locked out after just **one** failed login attempt (2
  seconds), which collided with the test's own single deliberate
  wrong-password check earlier in the same sequence. This wasn't only a
  test artifact - locking out a real user after one honest typo is bad UX
  in its own right. Fixed with a `FREE_FAILURES` grace count (4): the
  first 4 consecutive failures cost no delay at all, and the exponential
  backoff only starts counting from the 5th. Two unit tests added
  (`aFewFailuresInARowDoNotLockOut`, and
  `locksOutOnceFreeFailuresAreExhaustedAndClearsOnSuccess` replacing the
  old single-failure-locks-out test, which asserted the since-corrected
  behavior).
- **`AuthResourceTest` then failed a fourth time** (expected 204, got 401)
  on its `PUT /api/auth/password` password-change calls once inside the
  `authEnabled=true` block - a test bug this time, not an app bug.
  `AuthenticationFilter`'s allowlist deliberately excludes
  `/api/auth/password` (so changing a password requires a valid session on
  top of the current-password check, once auth is actually on) - working
  exactly as designed. The test's own two calls to that endpoint in this
  section simply hadn't been updated with a bearer token header when the
  surrounding test was restructured to turn `authEnabled` on first. Fixed
  by attaching `Authorization: Bearer <token>` to both.

**UX issue found by the user manually exercising the Settings page
(2026-09-07), fixed same-day:** the "Require a password" toggle was
disabled (greyed out, unclickable) until a password existed, and sat
*above* the password field in the group's layout - so a user's natural
first move (try the toggle) hit a silently-inert control with no
indication why. Two changes: (1) reordered the Security group so "Set
password" appears first, the toggle and session-length control after it;
(2) per the user's own suggestion, replaced the disabled-control approach
entirely with a validity-based one - the toggle is now always clickable,
but flipping it on with no password set marks that `FormControl` invalid
(`{ passwordRequired: true }`) via `setErrors()`, which propagates up
through `SettingsFormGroup` the normal Angular reactive-forms way and
disables `SettingsPage`'s own global Save button (already gated on
`settingsForm.invalid` for every other group's validation) - with a
visible, specific reason (`.settings-row-error`, a new small reusable
row-level style using the one reserved alarm color) shown right at the
toggle, rather than a generic "Could not save settings" toast only
discoverable after clicking Save. `SettingsResource`'s own server-side
rejection ([[0061-authentication]]'s "Decision" section) is unchanged and
still the authoritative guard either way - this is purely a client-side
UX improvement on top of it.
