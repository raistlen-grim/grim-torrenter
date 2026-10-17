# Code review 03 — frontend

Reviewed 2026-10-17, after the app A/B fixes. Scope: everything under `frontend/src/app`
(~13.5k lines including templates and styles, ~6k of TypeScript). Paths are relative to
`frontend/src/app/`. The visual style and design-guide conformance were not reviewed; they have
their own pass (design_docs/0032).

**Confirmed** means the failure path was traced through the code. **Plausible** means it depends
on framework or runtime behaviour that wasn't verified here.

Longer-term structural items are in `../TECH_DEBT.md`.

---

## A. Fix before release

**Status (2026-10-17): A1–A3 fixed. See design_docs/0095.**
- **A1:** every poll goes through `shared/poll.ts` `pollEvery`; Settings and Events show a Retry
  when their first load fails.
- **A2:** the server now answers a wrong current password with 403.
- **A3:** a log-out button in the header. B1 (the reconnect loop) is still open: logout now stops
  the socket, but a token revoked elsewhere still loops.

### A1. One failed poll stops that poll for good (Confirmed)
Most polls are built as `interval(...).pipe(switchMap(() => http.get(...)))` with no
`catchError` inside the `switchMap`. A single failed request errors the whole stream:
- `interval` is unsubscribed and the poll never runs again;
- `toSignal` then throws that error every time the signal is read (Angular's documented
  behaviour; how it surfaces in the template is Plausible). At best the value freezes until a
  full page reload.

Any server restart triggers it, and that includes every upgrade. So does a network blip, a 404
for a torrent removed from another tab, or a 500.

Affected (no error handling):
- **Always mounted**, so a restart breaks them for the rest of the tab's life:
  `shell/app-header` `dhtStatus`, and `shell/app-footer` `diskUsage` and `resourceUsage`.
- **Detail panel**, everything built on `shared/poll-while-input.ts`: `piece-map`,
  `files-tab`, `peers-tab`, `trackers-tab`, and `torrent-detail`'s `fileCount`. Also
  `tracker-details-dialog`'s local `pollWhileVisible`.
- **One-shot loads behind `toSignal`:** `settings-page` `loadedSettings` (a failed load leaves
  the page empty for good, with no message) and `events-page` `initial`.

Already handled, in three different ways: `app-sidebar` (`catchError → null`), `services-page`
(falls back to the last report), and `blocklist-settings` / `proxy-settings` (`catchError →
EMPTY`).

Fix: one shared poll helper that catches per tick and keeps the last good value. Use it in
`pollWhileInput`, `pollWhileVisible` and the shell polls. Give the two one-shot loads an
explicit error state with a retry.

### A2. A wrong "current password" logs the user out (Confirmed)
`AuthResource.changePassword()` answers a wrong current password with 401. `authInterceptor`
treats every 401 outside `/api/auth/login` and `/api/auth/status` as a dead session: it clears
the token and navigates to `/login`.
- So a typo on the Security settings form logs the user out, and the error toast is never seen.
- With auth **off**, the same typo still navigates to `/login`, a page with nothing to log in to.

Fix: the server returns **403** for a wrong current password. The caller is authenticated,
just not allowed to make the change. `security-settings.ts` then checks for 403 instead of 401.

### A3. There is no way to log out (Confirmed)
`AuthService.logout()` exists, and the server revokes the token and (since design_docs/0094)
closes its sockets, but nothing in the UI calls it. With auth on, the only way to sign a
browser out is to clear site data. That matters most on a shared machine, which is when a user
turns auth on.

Fix: a "Log out" item in the header or sidebar, shown while auth is on and a token is held. It
calls `logout()`, stops the WebSocket (see B1) and navigates to `/login`.

---

## B. Should fix

**Status (2026-10-17): B1–B7 fixed. See design_docs/0096.**
- **B1:** the server now closes auth rejections with 4401, and the client stops reconnecting
  and goes to the login page.
- **B2:** the magnet parsing also moved to `shared/magnet.ts` and `torrent-list/add-state.ts`.
- **B6:** eight spec files for the pure logic. Component tests are still to do.

### B1. A rejected WebSocket reconnects every 3 s forever (Confirmed)
`TorrentEventsService.openSocket()` reconnects on every close, whatever the reason. When the
server closes it on purpose (no token on the login page, or a token revoked by logout, expiry or
a password change), the reconnect is rejected again, 3 s later, forever.
- Each rejection logs a WARN on the server ("Rejecting WebSocket handshake…"), so one tab left on
  the login page adds a log line every 3 s.
- While a revoked tab makes no REST call, nothing sends it to `/login` either. The list just
  stops updating.

Fix, server and client together:
- The server closes auth rejections with code **1008** (policy violation).
- The client treats 1008 like a 401: clear the token, stop reconnecting, go to `/login`.
- The client also doesn't reconnect while auth is on and no token is held; `connect()` after
  login restarts it.

### B2. A stray `%` in a pasted magnet breaks the list view (Confirmed)
`parseMagnetParams()` (`torrent-list/torrent-list.ts:129`) calls `decodeURIComponent` on each
parameter. A malformed escape, such as `dn=50%` or `dn=%zz`, throws `URIError`.
- It runs inside the `addState` computed, which `addPrimary`, `echo` and `addFieldTone` read
  from the template, so the list's change detection throws for as long as that text is in the
  field. The global paste handler puts it there without the user clicking anything.
- Esc still clears the field, because event handlers keep working.

Fix: catch `URIError` and keep the raw value. Also decode `+` as a space in `dn`, which is the
form-encoding most magnet generators use.

### B3. Every snapshot does O(n²) work, and rate history is never pruned (Confirmed)
`TorrentEventsService.replaceAll()` calls `recordReading()` once per torrent, and each call copies
the whole `ratesByHash` map to add one entry. With n torrents that's n map copies of size n every
2 s: about 1M entry copies per tick at 1,000 torrents.
- A torrent removed by another tab or client is dropped from `torrentsByHash`, but its rate
  history and `ratesByHash` entry are kept for the life of the tab.

Fix: build the new rates map once per snapshot, from the torrents in it. That prunes as a side
effect; also drop the rate trackers' keys for torrents that are gone.

### B4. Server error reasons are dropped (Confirmed)
Since design_docs/0094 every 4xx/5xx carries `{"error": "..."}`, but most callers ignore it:
- `settings-page/blocklist-settings` `reload()` has no error handler at all, so a failed reload
  shows nothing.
- `login-page` shows "Incorrect password." for every non-429 failure, including a network error
  or a 500.
- `files-tab` assumes any 400 means "At least one file must stay wanted".
- Row, detail and bulk actions all show "Please try again."
- The three callers that do read it each type the error differently (`any`,
  `{ error?: { error?: string } }`, untyped `err?.error?.error`).

Fix: one helper, `apiErrorMessage(error: unknown, fallback: string)`, used for every error
toast and inline message.

### B5. Leaving Settings drops unsaved changes, but keeps a previewed theme (Confirmed)
There's no `canDeactivate` guard, so navigating away from an edited settings form loses the
edits without a word. `AppearanceSettings` previews a theme the moment it's picked, and nothing
reverts it when leaving without saving, so the app keeps showing a theme the server doesn't have.
A reload then flips it back.

Fix:
- A `canDeactivate` guard that asks while the form is dirty.
- On destroy without a save, `ThemeService.preview()` puts back the saved theme.

### B6. Effectively no frontend tests (Confirmed)
There is one spec, `app.spec.ts`, which checks that `App` constructs. None of the pure logic has
a test. That logic is cheap to cover with vitest, which is already set up:
- magnet normalising and parsing, and `resolveAddState`;
- `RateTracker`;
- the filter predicates in `torrent-filter.service.ts`;
- `TorrentRow.splitFileName`;
- the settings `build*Form` / `*Patch` pairs, including the KiB↔bytes conversions;
- the Shift-click range in `extendSelection`.

B2 and B7 would both have been caught by a single test each.

### B7. A rate limit switched on with a value of 0 saves as "unlimited" (Confirmed)
In `rate-limit-settings.ts`, `kibToBytes(0)` is 0, and 0 means unlimited on the server. The
toggle shows the cap as on, the save succeeds, and on reload the toggle comes back off. The
`[min]` on those inputs is 0. The same applies to the scheduled-window limits.

Fix: while a limit's toggle is on, require a value of at least 1.

---

## C. Code smells and duplication

- **`torrent-list/torrent-list.ts` is 1226 lines** with six jobs: the list and sort, filter
  chips, multi-select, keyboard shortcuts, the remove dialog, and the whole add control (magnet
  parsing, drag/drop, global paste, pending rows, failure toasts). Suggested split:
  - The magnet parsing functions (pure, top of the file) go to `shared/magnet.ts`, where they're
    testable (B6).
  - The add control becomes its own component.
  - The keyboard shortcuts could be a directive.
- **`TorrentRow` and `TorrentDetail` duplicate** the context menu, `copyMagnetLink`,
  `confirmRemoveWithData`, `notifyActionFailed` and `progressPercent`. They have already
  drifted:
  - The detail menu has no "Torrent limits…" or "Labels…".
  - Its Pause/Resume and Force recheck aren't disabled for a fetching magnet; the row's are.

  One menu builder, plus the confirm and copy helpers in `TorrentActionsService`, would keep
  them in step.
- **Four polling/error patterns** (see A1). `pollWhileVisible` is a near-copy of
  `pollWhileInput` kept local to one dialog.
- **Two definitions of "ratio".** The footer divides session `bytesUploaded` by
  `bytesDownloaded`. The detail panel uses `lifetimeUploadedBytes`, deliberately, per
  design_docs/0064. After a restart the two disagree.
- **`pauseAll()`/`resumeAll()` differ from `runOnEach()`**, which the selection bar and
  keyboard use. They don't skip torrents that already have a request in flight, and they say
  nothing on failure, where `runOnEach` shows one summary toast.
- **Magnet-failure toasts compare ISO timestamps as strings** (`torrent-list.ts:989`). Java's
  `Instant` serialisation drops trailing fractional zeros, so `…:00.55Z` sorts before `…:00.5Z`
  and a failure can be skipped (Plausible; it depends on the Jackson output). Compare
  `Date.parse()` values, or use a sequence number.
- **WebSocket messages are trusted as-is:** `JSON.parse` without a try, and payloads cast by
  `type` without a check. One bad frame throws out of `onmessage`.
- **Comments cite documents that aren't in the repo.** `ADD_CONTROL.md`, `STYLE_GUIDE_NOTES.md`,
  `SETTINGS_PAGE.md`, `ADDENDUM_02` and the guide's `README.md` "Copy"/"Interactions" sections
  are referenced from 11 files; `style/` holds only an empty `torrent_list/`. Either commit the
  guide or cite the design_docs that summarise it.
- **Stale or historical comments** (same theme as reviews 01/02):
  - `torrent-detail.ts` `activeTab` says a fetching magnet "can't actually happen here" because
    of design_docs/0028. Pending magnets (0070) do exist; it's the row refusing to open them
    that makes it true now.
  - `theme.service.ts` cites `inject(TorrentEventsService).connect()` in `App`, which isn't
    there.
  - Many "an earlier version…", "previously…", "renamed from…" narratives.

## What looked good
- Signals and `OnPush` throughout; derived state in `computed`. `TorrentRow`'s per-field
  computeds plus `trackByRow` keep a 2-second snapshot from re-rendering unchanged rows.
- The pending-action state is shared across row, detail and bulk actions in
  `TorrentActionsService`, so every view agrees on what's in flight.
- The WebSocket token goes in the subprotocol, not the URL; `localStorage` access is wrapped
  for private browsing.
- The global paste and drop handlers are removed on destroy and leave other text fields alone.
- The add flow uses the server's response as the source of truth (0070/0071), with no
  client-side guessing of torrent state.
