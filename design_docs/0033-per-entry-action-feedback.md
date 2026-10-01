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

## Addendum: the details panel's pending state is per torrent (2026-10-01)

**Bug.** With the details panel open, only one torrent could be paused/resumed at a time. The
panel's `pendingAction` was a single signal on `TorrentDetail`, and that component instance is
reused as the `:infoHash` route param changes, so a request still in flight for one torrent
(pause/resume are synchronous and can take a minute while they wait on the tracker) left the
footer button and menu items disabled for every torrent selected afterwards. The rows never had
this problem - each `TorrentRow` is its own instance - but their action buttons are hidden while
the panel is docked, so the panel footer was the main way in.

**Fix.** `TorrentDetail` keeps a `Map<infoHash, action>` signal; `pendingAction` is now a computed
lookup for the torrent currently shown, so the template is unchanged. Two things the same reuse
got wrong are fixed alongside: the failure toast names the torrent the action was started on (not
whichever is shown when the request fails), and a remove that completes after the panel has moved
to another torrent no longer closes the panel.

## Addendum: one shared pending-action service (2026-10-01)

The fix above left the row and the panel tracking pending state separately: an action started in
the panel (the usual place while it is docked, since the row's buttons are hidden then) didn't
dim the row, one started from the row's context menu didn't disable the panel footer, and closing
the panel forgot it. The user asked for the row to show it too.

**Decision.** A root `TorrentActionsService` (`services/torrent-actions.service.ts`) owns
`pause()`/`resume()`/`remove()` for a single torrent and a `Map<infoHash, action>` signal of
requests in flight. An entry is set when the returned observable is subscribed and cleared in
`finalize`. `TorrentRow.pendingAction` and `TorrentDetail.pendingAction` are both computed lookups
into it (`pendingFor(infoHash)`), so templates and the `row-pending` host class are unchanged.
`remove()` also does the `removeLocal()` both components used to do themselves. This revises the
"duplicated rather than a shared service" call noted in `TorrentDetail`'s own comment - the menus,
confirm dialogs and failure toasts stay per component (`MessageService`/`ConfirmationService` are
provided by `TorrentList`, not root), only the request and its pending state moved.

The toolbar's Pause all/Resume all go through the same service, so every affected row now dims
until its own request returns - [[0043-app-shell-and-filtering]] had noted the lack of per-row
feedback there as a limitation of row-private state. Failures of a bulk action are still not
toasted per torrent.

**Alternatives considered.** A backend-reported "pausing"/"resuming" state on `TorrentView` would
be the [[0071-thin-frontend-as-a-standing-consideration]] answer and would also show in a second
browser; not done here because it needs pause/resume to stop being one synchronous request, which
is the larger change already noted for `start()` in PROGRESS.md's known gaps. This service is
per-viewer feedback on the viewer's own request, which 0071 allows.

**Stability.** One map entry per in-flight request, removed on completion, error or unsubscribe,
so it is bounded by the number of torrents and can't leak. A second action on the same torrent
while one is pending is prevented by the disabled controls, as before.
