# 0101 - Frontend clean-up: shared torrent actions, the add controller, checked WebSocket messages

Status: Accepted (2026-10-18). Implements the C items of the pre-release frontend review
(`code_review/03-frontend.md`). Applies [[0051-stability-as-a-standing-consideration]] and
[[0071-thin-frontend-as-a-standing-consideration]].

No backend or REST changes. Three things behave differently: the details panel's menu (section
1), Pause all/Resume all (section 3) and the footer ratio (section 4).

## 1. One torrent action menu: `TorrentCommands`

`TorrentRow` and `TorrentDetail` each had their own copy of the context menu, copy magnet link,
the delete-files confirm, the failure toast and the progress percentage. The copies had drifted:
- the panel's menu lacked **Torrent limits…** and **Labels…**;
- its Pause/Resume and Force recheck weren't disabled for a magnet still fetching metadata.

`TorrentCommands` (`torrent-list/torrent-commands.ts`) now holds:
- `menuItems(torrent, dialogs, onRemoved?)`, the one menu;
- `pause`, `resume`, `recheck`, `reannounce`, `remove` and `confirmRemoveWithData`, each with its
  toast;
- `copyMagnetLink`.

Each host passes the three dialog openers, since it owns its own dialog instances. The panel
also passes `onRemoved`, which closes it if it is still showing that torrent. Each handler takes
the torrent as it was when the action started, so a toast for a slow request names the right
torrent.

`TorrentCommands` is provided by `TorrentList`, not root. It needs `TorrentList`'s
`MessageService` and `ConfirmationService`, whose `<p-toast>` and `<p-confirmDialog>` live in its
template. The row and the panel both render inside `TorrentList`, so DI finds that instance. The
shared pending state stays in the root `TorrentActionsService`
([[0033-per-entry-action-feedback]]'s addenda).

**Behaviour change:** the details panel's "⋯" menu now has Torrent limits… and Labels…, and
disables the same items for a fetching magnet as the row does. Today a fetching magnet can't
open the panel, so in practice the visible change is the two new items.

Two small pure helpers came out of the same duplication:
- `progressPercent()` (`shared/progress-percent.ts`), the "nonzero rounds up to 1" percentage;
- `magnetLinkFor()` (`shared/magnet.ts`), the copied link.

Both have unit tests. A server-side `magnetUri` field remains a thin-frontend item in TECH_DEBT.

## 2. The add control's logic: `AddTorrentController`

`torrent-list.ts` was over 1,100 lines doing six jobs. The biggest of them, the add control, now
lives in `AddTorrentController` (`torrent-list/add-torrent-controller.ts`). It holds:
- the bonded field and primary button's state;
- the echo strip;
- page-wide paste and drag/drop;
- the pending placeholder rows;
- uploads and magnet adds;
- the failed-magnet toasts.

`TorrentList` is now about 740 lines.

**Why a controller and not a component**, as the review suggested? The control's markup sits in
three places in the list's template:
- the add unit, inside the toolbar's flex row;
- the echo strip, between the toolbar and the table;
- the pending rows, at the top of the table.

A component would have to own all three, or split them, and moving the markup moves its layout
and CSS too. That needs browser verification this change shouldn't depend on. So the markup and
styles stay where they are, and the template binds `add.*`. The controller is provided by
`TorrentList`, so it lives and dies with it, and removes its window listeners on destroy as
before. `TorrentList` hands it the file input and the paste field through `attachElements()`.

**Not done: a keyboard-shortcut directive.** The shortcuts read and change most of the list's
state: the visible rows, the selection and its anchor, the panel, the remove dialog and the
search field. A directive would need nearly all of it passed back in. They stay in `TorrentList`,
grouped in one section. The split item in TECH_DEBT is narrowed to say so.

## 3. One way to act on many torrents

Pause all/Resume all looped `subscribe()` themselves: they didn't skip torrents with a request
already in flight, and said nothing on failure. They now go through the same `runOnEach()` as
the selection bar and the keyboard: idle torrents only, and one summary toast if any fail.

**Behaviour change:** the "Pausing N torrents"/"Resuming N torrents" info toast is gone. Each row
already shows its own pending state, which is what the selection bar has always relied on.

## 4. One definition of ratio

The footer divided session `bytesUploaded` by `bytesDownloaded`. The details panel uses
`lifetimeUploadedBytes` ([[0064-persistent-lifetime-stats]]). After a restart the footer dropped
toward 0 while every torrent's own ratio didn't. The footer now uses `lifetimeUploadedBytes` too.

**Behaviour change:** the footer ratio no longer resets on a server restart.

## 5. Polling

Every poll already went through `pollEvery()` ([[0095-frontend-failure-recovery-and-logout]])
except two:
- **The tracker-details dialog's `pollWhileVisible`** was a local near-copy of
  `pollWhileInput`. It is now exported from `shared/poll.ts` beside `pollEvery`.
- **The sidebar's health poll** had its own `interval`/`startWith`/`switchMap`. It now uses
  `pollEvery`, keeping its own `catchError(() => of(null))` inside the fetch: for the health
  badge, a failed poll means "no claim", not "keep the last answer".

## 6. Event timestamps compared as times

The failed-magnet toast tracked its cursor by comparing ISO strings. Java's `Instant` drops
trailing fractional zeros, so `…:00.55Z` sorts before `…:00.5Z` as text, and a failure could be
skipped. The cursor is now epoch millis from `Date.parse()`.

## 7. WebSocket messages are checked

`onmessage` did `JSON.parse` with no try, then cast the payload by `type`. One bad frame threw out
of the handler, or put a malformed object into the torrent list.

`parseEventMessage()` (`shared/event-message.ts`, unit-tested) now:
- parses the frame and checks the envelope;
- checks the payload shallowly, on the fields the client keys on: a torrent's `infoHash`,
  `name`, `state` and `labelIds`; an event's `timestamp` and `type`; a label's `id` and `name`;
- returns a typed union, so `handleMessage` needs no casts.

A frame that fails is dropped with a console warning. The next snapshot (2 s) carries the full
state anyway.

## 8. Comments

- **Citations of the style guide's files** (`ADD_CONTROL.md`, `STYLE_GUIDE_NOTES.md`,
  `SETTINGS_PAGE.md`, `ADDENDUM_02`, the guide's `README.md`) were in 13 files, but those files
  are not in the repo. They now say "the style guide", "the add-control guide (design_docs/0032)"
  or "the settings guide (design_docs/0045)". Those design docs summarise what was taken from the
  guide. Committing the guide itself is the owner's call, and stays in TECH_DEBT.
- **The two stale comments the review named** are fixed:
  - `TorrentDetail.activeTab` said a fetching magnet can't reach the panel "because of 0028". It
    is the row refusing to open one that makes it true.
  - `ThemeService` cited a `connect()` call in `App` that isn't there.
- **The module-wide pass** over "an earlier version…" narratives stays in TECH_DEBT, as for the
  engine and app ([[0099-engine-shared-helpers-and-hot-path-counters]],
  [[0100-app-shared-beans-and-rest-contract]]). The comments rewritten here describe current
  behaviour.

## Stability

- **Nothing new is retained.** `AddTorrentController` and `TorrentCommands` live exactly as long
  as `TorrentList`, which is where their state lived before. The controller's window listeners
  are removed through the same `DestroyRef`, now the component's via the element injector.
- **Polls are unchanged in count and cadence.** Both moved polls still stop when their gate
  closes: dialog hidden, component destroyed.
- **Hostile input.** A malformed or hostile WebSocket frame can no longer throw out of
  `onmessage` or insert an object without an info hash into the torrent map. The checks are
  shallow by design: the server is the same origin, and this guards against bugs and version
  skew, not a hostile server.
- **Pause all/Resume all** now skip torrents with a request in flight. Pressing one twice no
  longer sends two requests per torrent.
