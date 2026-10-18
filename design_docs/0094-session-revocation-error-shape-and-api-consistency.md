# 0094 - Ending sessions, one error shape, and API consistency

Status: Accepted (2026-10-17). From the pre-release app review (`code_review/02-app.md`, items
B1-B9). Revises [[0061-authentication]] (password changes now revoke sessions; login throttle
moved out of SessionTokenStore) and [[0055-library-events]] (tolerant reads). Applies
[[0051-stability-as-a-standing-consideration]] and
[[0071-thin-frontend-as-a-standing-consideration]].

## 1. Ending access (B1)

**Password change revokes every other session.** `PUT /api/auth/password` now calls
`SessionTokenStore.revokeAllExcept(callerToken)`. The caller's own token, if it sent one, stays
valid, so the browser that made the change isn't logged out mid-request. Revoking it too is
cleaner on paper, but any request in flight between the change and the frontend's re-login
would 401 and bounce the user to the login page. 0061's "a password change doesn't revoke
existing sessions" tradeoff is withdrawn.

**Open WebSockets are re-checked.** `TorrentWebSocket` keeps each connection with the token it
opened with. On every 2-second snapshot tick, while auth is on, `TorrentSnapshotScheduler`
closes any connection whose token fails `SessionTokenStore.isValid()`. That covers logout,
expiry, a password change, and auth being switched on (a connection opened with auth off has no
token, so it fails). The browser's reconnect then goes through the handshake check again.
- `isValid()` doesn't refresh the sliding expiry, unlike `validate()`. Otherwise an open tab
  would keep its own token alive forever just by being open.
- At most 2 s of further broadcasts after revocation. Acceptable: closing synchronously from
  logout would need a token → connections index for no real gain.

## 2. Login throttle (B2)

The backoff moved into its own `LoginThrottle` (it was half of `SessionTokenStore`), and gained
a one-permit `Semaphore`: `tryBegin()` → verify → `finish(succeeded)`. Only one password
verification runs at a time; a request that finds the slot busy, or the lockout active, gets
429. Previously a burst arriving while the lockout was clear all passed the check before any
recorded its failure, so each got a guess, and each guess cost a full PBKDF2 hash.

Two people logging in at the same instant means one gets a 429 and retries. That's fine for a
single-password personal server.

## 3. Event log tolerance (B3)

- `readAll()` skips, and logs, a line that doesn't parse, and a day-file that can't be read. A
  crash mid-append leaves one torn line, which used to make `GET /api/events` 500 until that
  day-file aged out (30 days by default).
- `prune()` never throws. It runs from `@PostConstruct`, so one undeletable old file used to
  stop startup. A failed delete is logged and retried on the next hourly tick.

## 4. A WebSocket whose send failed is closed (B4)

`broadcast()` used to drop a failed connection from the map but leave it open. The browser saw a
healthy socket that never received anything again, so its reconnect logic never ran and the UI
went stale (typically after a laptop sleep). It's now closed too.

## 5. Engine I/O failures are 500s (B5)

New `TorrentEngineIoException extends TorrentEngineException`, carrying the `IOException` as its
cause. All 14 throw sites that wrap an `IOException` (marker writes, deletes, directory
creation, the config-directory scan) use it. The two bad-input sites (no usable tracker) keep the
parent. `TorrentEngineExceptionMapper` (now `EngineExceptionMappers.torrentEngine()`, 0100) maps
the subclass to 500 with its cause logged, and the parent to 400 as before.

A subclass rather than a "kind" field so every existing `catch (TorrentEngineException)` and
`assertThrows` keeps working unchanged.

## 6. One error body shape (B6)

New `WebApplicationExceptionMapper`: every thrown `BadRequestException`, `NotFoundException`,
`NotAuthorizedException` and `WebApplicationException(msg, status)` now gets the
`{"error": message}` body `ErrorResponses` and the other mappers already wrote. Status and
headers are kept, a response that already has a body passes through, and 5xx is logged. The
`AuthenticationFilter`'s 401 carries a body too.

`new NotAuthorizedException("Invalid password")` was passing the message as the
`WWW-Authenticate` challenge (that's the one-argument constructor), so the message never reached
the client. It now uses the `(message, challenge)` form with challenge `Bearer`.

The settings page now shows the server's reason in its "Could not save settings" toast.

## 7. Settings bounds (B7)

`SettingsResource.update()` rejects, with a 400 naming the field and range, numeric settings
whose extremes would hurt the server or other peers. Examples: `magnetFetchConcurrencyLimit` up
to 1024, `maxConnectionsPerTorrent` up to 500. The DHT re-query, DHT refresh and LSD intervals
must be at least 60 s; BEP 14 asks for at most one LSD announce a minute, and the frontend's
minimum for those three inputs is now 60. The bounds are generous on purpose. Rate and seeding
limits aren't bounded, since their extremes only mean "unlimited". A null body is a 400, not an
NPE.

The bounds are checked at the REST boundary, not in the `Settings` constructor, so an existing
`settings.json` with an out-of-range value still loads. The user sees the error the next time
they save from the UI.

## 8. Consistent 404s (B8)

`TorrentEngine.removeTorrent`, `pauseTorrent` and `resumeTorrent` now return whether the
torrent existed. The REST endpoints 404 like every other per-torrent endpoint. Pause and resume
of a pending magnet are 404 too: it isn't a torrent yet. The frontend's remove treats a 404 as
success, since the torrent being gone (removed from another tab, say) is what was asked for.

## 9. No snapshot without listeners (B9)

`broadcastSnapshot()` returns straight after the token re-check when no socket is open. That's
the usual state for a headless server, and building the snapshot walks every torrent's pieces.

## Stability

- B1: one map entry per open socket, removed on close, failure or revocation. The re-check is
  O(connections) every 2 s.
- B2: a single permit, always released in `finally`. A verification that throws counts as a
  failure, so throwing can't bypass the throttle.
- B3/B4/B9 are stability fixes in their own right; nothing new is retained.
- B5/B6: 5xx responses are now logged with their cause, where some previously weren't logged at
  all.
- B7 is a resource bound on admin input.
