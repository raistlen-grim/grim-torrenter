# 0096 - Frontend: ending a session cleanly, error messages, and the first real tests

Status: Accepted (2026-10-17). From the pre-release frontend review
(`code_review/03-frontend.md`, items B1-B7). Builds on
[[0094-session-revocation-error-shape-and-api-consistency]] and
[[0095-frontend-failure-recovery-and-logout]]. Applies
[[0051-stability-as-a-standing-consideration]] and
[[0071-thin-frontend-as-a-standing-consideration]].

## 1. A WebSocket refused for auth stops reconnecting (B1)

### Problem
`TorrentEventsService` reconnected after every close, 3 s later, whatever the reason. When the
server closed it for auth (no token on the login page, or a token revoked by logout elsewhere,
expiry, or a password change), every retry was refused again, forever. Each refusal logged a
server WARN, and a revoked tab that made no REST call was never sent to the login page; its list
just stopped updating.

### Decision
- **The server says why.** `TorrentWebSocket` closes an auth rejection, at the handshake or on
  the snapshot tick's token re-check, with code **4401** ("Authentication required"). That's in
  RFC 6455's 4000-4999 application range, and deliberately distinct from the **1008** an Origin
  rejection now uses. The handshake rejection logs at DEBUG, not WARN: a missing token is normal
  right after auth is switched on.
- **The client treats 4401 like a REST 401:** it clears the token, goes to `/login`, and doesn't
  reconnect. `LoginPage` calls `connect()` again after a successful login.
- **The client also doesn't reconnect while auth is on and it holds no token**, since a retry
  could only be refused.

Every other close (network drop, server restart, Origin rejection) still reconnects as before.

API note: written against `io.quarkus.websockets.next.CloseReason(int, String)` and
`WebSocketConnection.close(CloseReason)`, as documented for quarkus-websockets-next; not checked
against the jar (CLAUDE.md).

## 2. Magnet parsing can't throw (B2)

`decodeURIComponent` throws on a malformed escape, and a literal `50%` in a name is common. It
ran inside the add field's `computed`, which the list template reads, so a pasted magnet like
that broke the list view's change detection until the field was cleared. Parameters are now
decoded with a fallback to the raw text, and `+` decodes to a space.

The parsing moved out of `torrent-list.ts` into `shared/magnet.ts` (pure helpers) and
`torrent-list/add-state.ts` (`AddState`/`resolveAddState`), both unit-tested. That's also the
first cut at C's "split torrent-list.ts".

## 3. Snapshot handling is linear (B3)

`replaceAll()` added each torrent's rates to a fresh copy of the whole map, so n torrents meant n
copies of n entries every 2 s. It now builds the rates map once per snapshot. Rate history for
torrents missing from the snapshot (removed in another tab or client) is dropped via the new
`RateTracker.retainOnly()`; it used to live as long as the tab.

## 4. One way to read an error (B4)

New `shared/api-error.ts` `apiErrorMessage(error, fallback)`:
- the server's `{"error": "..."}` reason when there is one (every error response has carried
  one since 0094);
- otherwise "Could not reach the server." for status 0;
- otherwise the caller's fallback.

It's used by the settings save, row and detail actions, bulk actions (the first failure's reason
stands for the batch), upload and magnet add, file priorities, proxy password and test, and the
blocklist reload, which previously had no error handler at all. The login page now says
"Incorrect password." only for a 401; a 429, a 500 or no connection each say what actually
happened. The label dialogs keep their status-specific wording, which already matched the
server's cases.

## 5. Unsaved settings (B5)

- `unsavedChangesGuard` (a `CanDeactivateFn` over a small `HasUnsavedChanges` interface) on the
  settings route asks "Leave without saving?" while the form is dirty.
- Closing or reloading the tab gets the browser's own prompt (`beforeunload`).
- When the page is destroyed, `ThemeService.preview()` puts back the saved theme, so a theme
  that was previewed but not saved no longer outlives the page. After a save this is a no-op,
  because the baseline is the saved settings.

## 6. Tests (B6)

The frontend had one spec, `App` constructs. It now has unit tests for the pure logic, using the
existing vitest setup:
- `shared/magnet.spec.ts` and `torrent-list/add-state.spec.ts`
- `shared/rate-tracker.spec.ts`
- `shared/file-name.spec.ts` (`splitFileName`, moved out of `TorrentRow` to be testable)
- `services/torrent-filter.service.spec.ts`
- `shared/api-error.spec.ts`
- `shared/poll.spec.ts`
- `settings-page/rate-limit-settings/rate-limit-settings.spec.ts`

Component tests (TestBed) are still out of scope; see TECH_DEBT.

## 7. A rate limit switched on must be at least 1 KB/s (B7)

0 means unlimited to the server, so a cap switched on at 0 saved as "no cap" and reloaded with
its toggle off. The four limit controls now carry `required` + `min(1)` and the inputs
`[min]="1"`. A disabled control (cap off) isn't validated, so this only applies while a cap is
on. An invalid form already disables Save, and an inline "Set at least 1 KB/s, or switch the cap
off." says why.

## Stability

- B1 removes an unbounded loop: one refused handshake every 3 s per tab, forever, each costing a
  server log line.
- B3 turns O(n²) per-tick work into O(n), and bounds rate history to the torrents that exist.
- B2 removes a client-side crash path driven by pasted (untrusted) input.
- Nothing new is retained. The guard's confirm Observable completes on either answer.
