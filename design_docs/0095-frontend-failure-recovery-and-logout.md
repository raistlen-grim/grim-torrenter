# 0095 - Frontend: polls that survive failures, a 403 for a wrong current password, and logout

Status: Accepted (2026-10-17). From the pre-release frontend review
(`code_review/03-frontend.md`, items A1-A3). Revises [[0061-authentication]] (status code for a
wrong current password; a logout control). Applies
[[0051-stability-as-a-standing-consideration]].

## 1. A failed poll skips a tick (A1)

### Problem
Most polls were `interval(...).pipe(switchMap(() => http.get(...)))` with no `catchError`. One
failed request errored the whole stream, so the poll stopped for good, and `toSignal` then
throws that error on every read. A server restart (every upgrade) froze the header's DHT pill
and the footer's disk and heap figures in every open tab until a reload. The detail panel's tabs
froze the same way after a blip, or a 404 for a torrent removed elsewhere.

### Decision
- New `shared/poll.ts` `pollEvery(intervalMs, fetch)`: `interval` + `startWith(0)` +
  `switchMap(fetch().pipe(catchError(() => EMPTY)))`. A failed fetch emits nothing, so a
  `toSignal` over it keeps its last good value, and the next tick tries again.
- Every poll uses it: `pollWhileInput` (pieces, files, peers, trackers, the file count), the
  tracker dialog's `pollWhileVisible`, the header and footer, the Health page, and the blocklist
  and proxy status lines. Those last three already handled errors, each in its own way; they're
  now the same.
- The sidebar's health poll keeps its `catchError → null`, deliberately: null means "no claim",
  so a failed poll shows neither an alarm nor a false all-clear.
- One-shot loads that fed `toSignal` (Settings, Events) are now plain subscriptions with a
  `loadFailed` signal and a Retry button. A failed Settings load used to leave "Loading
  settings…" up forever.

Keeping the last good value means a value can be stale while the server is unreachable. That's
acceptable here: it's the same thing the WebSocket-fed list already does, and the next
successful tick corrects it.

## 2. A wrong current password is 403 (A2)

`authInterceptor` treats any 401 outside login/status as "the session has ended": it clears the
token and goes to `/login`. `PUT /api/auth/password` answered a wrong current password with 401,
so a typo logged the user out, even with auth off.

The server now answers it with **403** (`ForbiddenException`): the caller may hold a perfectly
good session; it just isn't allowed to make this change. 401 stays reserved, app-wide, for a
missing, expired or revoked token. The Security settings form checks for 403. Changing the
interceptor to special-case the path was rejected: it would leave the API's status codes
meaning two different things.

## 3. Logout (A3)

`AuthService.logout()` existed, but nothing called it.
- A log-out button in the header, shown while auth is on and this browser holds a token.
- It calls `logout()`, which clears the local token even if the request fails (`finalize`).
  A user who asked to log out must end up logged out; a token the server wasn't told about
  still expires on its own.
- It then calls the new `TorrentEventsService.disconnect()`, which closes the socket without
  reconnecting and clears the in-memory torrents, rates and events, and goes to `/login`.
  Logging in again calls `connect()`, which starts from a fresh REST load.

The server side already ends the session properly: logout revokes the token, and since
[[0094-session-revocation-error-shape-and-api-consistency]] the snapshot tick closes that
token's WebSockets.

## Stability

- `pollEvery` holds nothing beyond what a `toSignal` already holds. A server that stays down
  costs one failed request per poll per interval, exactly as many as when it's up, with no
  retry storm.
- `disconnect()` cancels the reconnect timer as well as the socket, so logout leaves no timer
  running.
- The Retry buttons are user-driven; nothing retries a one-shot load automatically.
