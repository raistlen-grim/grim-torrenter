# 0033 — Per-entry action feedback (style pass, part 1)

**Status:** Accepted - action feedback done for the list row. The broader visual style pass
against [[0032-style-guide-and-primeng-theme]]'s theme continues in
[[0034-ink-weight-status-display]] (status display) and
[[0035-spacing-table-density-and-empty-state]] (spacing, table density, empty state) -
all three parts are now complete.

## Decision

Raised by the user going into the style pass: Pause/Resume/Remove in the torrent list row
fired their request and gave **zero** feedback until the next 2-second WebSocket snapshot
happened to reflect the change - or, on failure, forever, since none of the three had an
error toast at all (unlike upload/magnet-add, see [[0029-optimistic-upload-feedback]]).
Explicitly **not** a global spinner/loading state - the ask was for a per-entry indicator,
scoped to whichever row an action is actually running against.

**`TorrentRow` gained a `pendingAction` signal** (`'pause' | 'resume' | 'remove' | null`),
set the instant a button is clicked and cleared via RxJS `finalize` once the response
arrives (success or failure) - `finalize` rather than doing it in both `next` and `error`
separately, so there's one place that can't be missed if a fourth action is ever added.
Drives two things at once:
- **The clicked control's own state**: Pause/Resume are `p-button`, which has a native
  `loading` input (auto-shows a spinner, auto-disables) - no custom spinner markup needed.
  Remove is a `p-splitButton`, which has no `loading` input, so it's handled manually:
  `[disabled]` while any action is pending, and its icon swapped to `pi pi-spin
  pi-spinner` specifically while `pendingAction() === 'remove'`.
- **A whole-row dim** (`opacity: 0.6` via a `[class.row-pending]` host binding, using the
  `host: {...}` object per this frontend's Angular conventions rather than `@HostBinding`)
  while *any* action is pending against that row - confirmed with the user over a
  button-only spinner: reinforces "this entry has something in flight" at a glance, not
  just on the specific control clicked. Pause/Resume/Remove are also all disabled while
  any one of the three is pending (not just their own action) - clicking Pause and then
  immediately Remove before the first request lands isn't a case worth supporting.

**Failed actions now toast** (`MessageService`, already provided by `TorrentList` and
already reachable from `TorrentRow` via Angular's hierarchical DI - `TorrentRow` already
injected `ConfirmationService` from the same provider set for the delete-confirmation
dialog). A failed action silently clearing its pending state and reverting to normal would
have read as even more confusing than the original no-feedback-at-all problem, so this
closes that gap the same way upload/magnet-add's toasts already do.

**Confirmed out of scope**: adding Pause/Resume/Remove to the torrent-detail header. The
detail page has no mutating actions today; adding some is a feature addition, not a style
pass, and wasn't asked for - this doc covers only the list row's existing three actions.

## Implementation notes

- `torrent-row.ts`: `pendingAction` signal, `notifyActionFailed()`, and
  `onPause`/`onResume`/`onRemove`/`confirmRemoveWithData` all rewired through the signal +
  `finalize` + toast pattern above.
- `torrent-row.html`: `[loading]`/`[disabled]` added to Pause/Resume; `[icon]`/`[disabled]`
  added to the Remove `p-splitButton`.
- `torrent-row.scss`: `:host(.row-pending) { opacity: 0.6; transition: opacity 0.15s ease; }`.

## Future work

**The row's displayed state (tag, progress, etc.) still only catches up on the next
periodic WebSocket snapshot** (up to ~2s, see [[0019-rest-and-websocket-layer]]) after
`pendingAction` clears - the pending dim/spinner covers the request's own round trip, but
there's still a gap between "request succeeded" and "the row visibly reflects it" where the
row looks normal again but hasn't actually caught up yet. Not fixed now - flagged by the
user as something to revisit if it ends up feeling unresponsive in practice, e.g. by
applying an optimistic local state change (mirroring `TorrentEventsService.upsert()`'s
existing role for uploads, [[0029-optimistic-upload-feedback]]) instead of waiting for the
next broadcast.

## Alternatives considered

- **Global loading spinner/state** - explicitly rejected by the user; the whole point was
  a per-entry indicator, not an app-wide one that doesn't say *which* torrent is busy.
- **Custom spinner markup for Pause/Resume instead of `p-button`'s native `loading`** -
  rejected; `loading` already exists on the component being used vanilla, per
  [[0032-style-guide-and-primeng-theme]]'s vanilla-PrimeNG-first rule.
- **Row-level `pointer-events: none` while pending** - rejected; only the opacity cue was
  wanted, not blocking navigation via the row's name link to the detail page while an
  action is in flight against it.
