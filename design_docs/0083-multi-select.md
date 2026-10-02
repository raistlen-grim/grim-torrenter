# 0083 - Multi-select on the torrent list

Status: Accepted (2026-10-02). Builds the part of the style guide's list spec
([[0032-style-guide-and-primeng-theme]]) that the restyle pass explicitly left out, on top of
[[0043-app-shell-and-filtering]] (the list) and [[0033-per-entry-action-feedback]]'s shared
`TorrentActionsService`.

## Problem

Pause, resume and remove worked on one torrent at a time, or on every torrent (Pause all/Resume
all). There was no way to act on several chosen torrents.

## Decision

Frontend only. No backend or REST change.

- **A selection set**, held in `TorrentList` as a signal of info hashes. Not persisted (the
  guide: "Do not persist `selection`").
- **How rows get into it**: a checkbox column (new first column, 30px), Ctrl/Cmd-click on a row
  (toggle), Shift-click on a row (extend from the anchor - the last row ticked or toggled),
  and a header checkbox (select all / clear all within the current filter; indeterminate when
  only some are selected).
- **Selection bar**: while at least one visible row is selected it takes the toolbar's place -
  same position, same margins, no animation. `N torrents selected` · Pause · Resume · Remove… ·
  Clear, with the guide's 10% accent wash. The toolbar is hidden with CSS, not removed, so the
  add field keeps its contents.
- **Bulk actions** loop the existing per-torrent endpoints through `TorrentActionsService`, all
  at once, so each row shows its own pending state. Pause acts on downloading/seeding rows,
  Resume on paused ones; each button is disabled when nothing in the selection qualifies.
  Failures produce one toast with a count, not one per torrent. Pause/Resume leave the selection
  in place; Remove clears it.
- **Bulk remove dialog**: the guide's - `Remove 3 torrents?`, `They stop seeding immediately.
  Downloaded files stay on disk unless you also delete data.`, a checkbox `Also delete 14.2 GB
  of data` (the sum of what has actually been downloaded), button `Remove torrents`. Singular
  forms are this app's own. Used for the selection only; the per-torrent Remove and
  Remove-and-delete-files flows are unchanged.
- **Esc**, in the guide's order: clears the filter field if it is focused; else closes the
  details panel; else clears the selection. Ignored while a dialog or context menu is open, and
  while typing in any other field. Closing the panel with Esc from anywhere is new - before, it
  only worked with focus inside the panel.
- **Checked-row styling**: the 8% accent wash without the 2px left edge. The edge stays the mark
  of the one row the panel is showing.

## Deviations from the guide (confirmed with the user)

- **Selection is separate from the details panel.** The guide has one selection concept: a
  click selects a single row and the panel follows it, and with 2+ selected the panel shows an
  aggregate summary. Here a plain click still opens the panel through the route exactly as
  before ([[0044-torrent-detail-drawer]]), the selection set drives only the selection bar, and
  there is no aggregate panel. Chosen as the smaller change that leaves the route-driven panel
  alone.
- ~~**No `Label` button** in the selection bar.~~ Added the same day - see the addendum below.
- ~~**Of the selection keyboard shortcuts, only Esc.**~~ The rest were added the same day - see
  the third addendum below.

## Behaviour worth knowing

- Everything that acts on the selection uses only rows currently in the list. A selected torrent
  hidden by a filter is not counted, not acted on, and not shown in the bar; if the filter is
  lifted before the selection is cleared, it reappears ticked.
- The remove dialog works from the selection as it was when the dialog opened.
- If the torrent open in the details panel is among those removed, the panel closes.
- A magnet still fetching metadata can be selected; Pause/Resume skip it, Remove works.

## Alternatives considered

- **PrimeNG Table's built-in selection** (`p-tableCheckbox`, `selectionMode`) - rejected for the
  same reason its built-in sort was ([[0043-app-shell-and-filtering]]): the table's rows are a
  union of pending uploads and torrents, and each torrent row is its own `tr[app-torrent-row]`
  component.
- **Bulk REST endpoints** (`POST /api/torrents/pause` with a list) - not added. A selection is
  per-viewer UI state, which [[0071-thin-frontend-as-a-standing-consideration]] leaves to the
  client, and another client can loop the same per-torrent endpoints. Worth revisiting only if
  request count becomes a problem.
- **Removing the toolbar from the DOM while the bar is shown** - rejected; the add field's hidden
  file input lives in it.

## Stability ([[0051-stability-as-a-standing-consideration]])

- Backend: none. A bulk action is N ordinary requests; pause and remove return quickly since
  [[0081-background-stopped-announce]], but **a bulk resume still holds one worker thread per
  torrent for as long as each first announce takes** - the same exposure Resume all already has.
- Frontend: the set holds one string per ticked row and is dropped on Clear, on a bulk remove,
  and with the page. Info hashes of torrents removed by other means linger in it harmlessly
  until then.

## Tests

None - this frontend has no component tests for the torrent list. Checked in the browser by the
user (2026-10-02).

## Addendum: bulk Label action (2026-10-02)

Asked for by the user right after the first cut. The selection bar gains the guide's `Label`
button (between Resume and Remove…), opening a new `BulkLabelsDialog`
(`torrent-list/bulk-labels-dialog/`). This is the bulk assignment [[0077-labels]] deferred.

- Same shape as the per-torrent labels dialog (a checkbox per managed label, plus a "new label"
  field that creates and ticks in one step), with one difference: **three-state checkboxes**,
  because the selected torrents rarely carry the same labels. Ticked = all of them have it, a
  dash = only some, empty = none.
- Ticking adds the label to every selected torrent; clearing removes it from every one; a dash
  left alone changes nothing for that label. A label that started mixed cycles dash -> ticked ->
  empty -> dash, so "leave as it was" can always be got back to; one that started uniform just
  toggles.
- Save computes each torrent's own new list and sends it through the existing
  `PUT /api/torrents/{h}/labels` - no backend change, and torrents whose labels wouldn't change
  are not sent. The responses go straight into `TorrentEventsService`. Failures (most likely the
  20-labels-per-torrent cap) produce one toast with a count.
- The dialog works on the selection as it was when it opened. The selection is kept afterwards.
- Magnets still fetching metadata are skipped, as the row's own Labels item already is.

Alternatives: an add-only dialog (simpler, but removing a label from many torrents would still
be one at a time), and a bulk REST endpoint (not needed, same reasoning as the bulk actions
above).

Stability: no backend change; N ordinary quick requests. Frontend state is one small map of
choices, discarded when the dialog closes.

Not covered by tests. Built and confirmed in the browser by the user (2026-10-02).

## Addendum: selection summary panel, opened explicitly (2026-10-02)

The guide: with 2+ rows selected "the panel shows an aggregate summary (combined size, combined
rates, count by state) instead of blanking or showing the first row". The first cut left this out
because the selection is deliberately separate from the panel. Built now in the form the user
chose from two options: **opened explicitly**, not taking the panel over as rows are ticked.

- A `Details` toggle in the selection bar (enabled with 2+ selected) opens a new
  `SelectionSummary` in the docked panel. Ticking or unticking rows never opens or closes it by
  itself, except that it closes when the selection becomes empty.
- Contents: header (`N torrents selected`), a fact grid of combined Size, Done (bytes and
  percent), Down, Up, Ratio and Peers, a combined progress bar, one `2 downloading · 1 seeding`
  line, and the selected torrents themselves, each showing its download rate or its state.
  Combined progress is bytes done over bytes total, and combined ratio is total uploaded over
  total downloaded - neither is an average of the per-torrent figures. The guide gives no layout
  for this panel; the fact grid and header copy the single-torrent panel, and the member list is
  this app's addition in place of the Files/Peers/Trackers/Pieces tabs, which have no combined
  form.
- No actions in the panel - the selection bar beside it carries them.
- **Sharing the panel with a torrent's details.** The panel column is open if either a torrent
  is open (route-driven, unchanged) or the summary is; rows shed their columns for both. If both
  apply, the summary is shown and the routed `TorrentDetail` is hidden (not destroyed - the
  outlet must stay in the DOM), and comes back when the summary closes. Clicking a row, or a
  torrent in the summary's list, opens that torrent's details and closes the summary - the more
  recent request wins.
- Esc closes whichever of the two is showing, the summary first.
- Everything is computed in the browser from the list's existing snapshot; no backend change and
  no request of its own.

Not built: the guide's version where the summary appears automatically, which was the option not
chosen.

Stability: no new state beyond one boolean; the summary recomputes from the same signals the list
already renders from. A hidden `TorrentDetail` keeps polling its tabs while covered, as it would
if visible.

Not covered by tests. Built and confirmed in the browser by the user (2026-10-02).

## Addendum: the rest of the list shortcuts (2026-10-02)

Space, Delete/Backspace, Up/Down, `/` and `I` from README.md's "Interactions" table, on top of
the Esc handling above. One document-level key handler in `TorrentList`, active only while the
torrent list is the routed page.

The guide writes these against its single "selection". With the ticked set and the panel kept
separate here, each needs a target rule, and the same one is used throughout: **the ticked rows
if there are any, otherwise the current row** - the row with keyboard focus, else the row whose
details are open.

| Key | Does |
|---|---|
| `Space` | Pauses whichever targets are downloading or seeding; if none are, resumes the paused ones. |
| `Delete` / `Backspace` | Opens the bulk remove dialog for the targets - also for a single current row, which gets the same dialog (with its delete-data checkbox) rather than the row menu's two separate remove items. |
| `Up` / `Down` | Moves keyboard focus one row; from the top/bottom edge when no row is current. While a torrent's details are open, the panel follows the focus. Does not change the ticked set and does not move the selection summary. |
| `/` | Focuses the filter field. |
| `I` | With 2+ ticked: toggles the selection summary. Otherwise opens the one ticked or current torrent's details. |

Ignored when: a modifier is held (Ctrl/Cmd/Alt - browser shortcuts stay intact; Shift is
allowed), a dialog or context menu is open, the key press is in a field or on a button, link or
tab, or focus is inside the details panel.

Known limits:
- `/` does nothing while the selection bar is showing - the filter field is in the toolbar the
  bar replaces.
- `I` is the guide's key for its pin toggle, which this app doesn't have (the panel is
  route-driven); here it only opens.
- `Backspace` no longer navigates back in browsers that still map it, while focus is on the list.
- Up/Down prevent the list's own scroll-by-arrow; the focused row is scrolled into view instead.

Stability: none - no new state, no new requests beyond the bulk actions already described.

Not covered by tests. Not yet built or seen in the browser.
