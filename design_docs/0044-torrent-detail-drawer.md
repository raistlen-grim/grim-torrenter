# 0044 — Torrent detail as a non-modal slide-out drawer

**Status:** Accepted

## Decision

`TorrentDetail` previously replaced the whole screen at `/torrents/:infoHash` — clicking a
torrent's name navigated away from the list entirely. Compared against a bottom detail panel
(the pattern several desktop torrent clients use) with the user: a bottom panel keeps the
full list visible but permanently competes with this app's already-fixed header/footer for
vertical space and needs its own resize/scroll handling for tall content (piece map, peer
table); a right-side drawer instead only claims space while something's open, and suits
detail content that's naturally taller-than-wide. **User chose the drawer.**

**Kept routed at `/torrents/:infoHash`** rather than becoming plain component state, after
walking through what routing actually buys here (bookmarkable/shareable URL, browser
back/forward closing and reopening it, surviving a page refresh) versus the cost (keeping
the drawer's visual open/closed state in sync with the router). For a single-user
self-hosted app the shareable-link case matters less than it might elsewhere, but
survives-refresh and back-button-closes-it are free wins given the routing already existed
for the old full-page view — so the router stays the source of truth for "which torrent's
detail is open," not a separate boolean signal.

**Deliberately non-modal**: no backdrop, no focus trap. The whole reason the user preferred
a drawer over a bottom panel was that the list stays visible *and interactive* — a dimmed,
click-blocking backdrop would have undermined exactly that, closer to a modal dialog than
the "detail panel beside a live list" the user actually asked for. Escape closes it, but
only when focus is already inside it (a `(keydown.escape)` binding local to the drawer, not
a document-level listener) — a global Escape hijack could surprise-close the drawer while
the user's doing something unrelated elsewhere (e.g. inside an open dropdown).

### Routing: `TorrentDetail` as a child route of `TorrentList`, not a sibling

`app.routes.ts`:

```ts
{
  path: '',
  loadComponent: () => TorrentList,
  children: [
    { path: 'torrents/:infoHash', loadComponent: () => TorrentDetail },
  ],
}
```

`TorrentList` renders its own `<router-outlet>` (inside the drawer markup, in
`torrent-list.html`), rather than `TorrentDetail` living behind the *app-level* outlet in
`app.html` as a sibling route. This is what makes the list and the detail view coexist at
all: with `TorrentDetail` as a sibling route, activating it would deactivate `TorrentList`
(only one component can occupy one outlet at a time) — exactly the full-page-navigation
behavior being replaced. As a *child* route, navigating to `/torrents/:infoHash` only
activates the child outlet; `TorrentList` itself is never unmounted, so its own state
(scroll position, in-progress uploads, the sort/filter signals) survives opening and closing
the drawer.

`isDetailOpen` (a signal derived from `router.events` filtered to `NavigationEnd`, checking
`route.firstChild !== null`) drives the drawer's `open` CSS class. Closing it
(`closeDetail()`) just calls `router.navigate(['/'])` — the drawer's visual state is a pure
function of the route, not something toggled independently that could drift out of sync
with it.

### Not PrimeNG's `p-drawer`

Tried first, rejected: `p-drawer`'s own template wraps its projected content
(`<ng-content>`) in `@if (modalVisible)`, so its content — which would include our
`<router-outlet>` — is entirely removed from the DOM whenever `visible` is false. That's
fatal for a routed outlet: Angular's router needs `<router-outlet>` to already exist in the
DOM at the moment it activates a route into it, and gating the outlet's very existence
behind the same state the navigation is supposed to produce is a chicken-and-egg problem —
by the time our own `isDetailOpen` signal reacts to `NavigationEnd` and flips `visible` to
true, the router has already tried (and failed) to find an outlet to activate into.

Built instead as a plain fixed-position `<aside>` with a CSS `transform: translateX(...)`
slide, toggled by a CSS class rather than structural removal — `<router-outlet>` stays
permanently in the DOM (only the *component* activated into it comes and goes, which is the
router's own normal, correct behavior), so there's no ordering hazard. Inset between
`--shell-header-height` and `--shell-footer-height` (both already defined in `app.scss` for
[[0043-app-shell-and-filtering]]'s fixed header/footer) rather than spanning the full
viewport, so the drawer never covers that chrome.

### `TorrentDetail` adjustments

- Its own page-gutter/reading-width styling (`max-width: 960px; margin: 0 auto`, a leftover
  from when it was a full page) is gone — the drawer (480px, with its own padding) already
  constrains both.
- The "← Back to list" link is now "× Close" (`close-link`, renamed from `back-link`) —
  it still just `routerLink="/"`, but the old copy assumed leaving a page; inside a drawer
  sitting next to the still-visible list, "back" no longer describes what it does.

## Follow-up: detail-tab content at drawer width

The four detail tabs (Piece map/Files/Peers/Trackers) were built for a full-page,
960px-reading-width view (`design_docs/0031`) and hadn't been touched for the drawer's
~430px content width. Confirmed as a real problem, not just a theoretical one: the
Trackers tab's 7-column table gave the URL column so little room that a real tracker URL
wrapped one character per line (`.tracker-url`'s old `word-break: break-all` made this
worse, not better - it breaks *anywhere*, whereas the fix below only breaks when a run
genuinely doesn't fit). Peers' table had the same problem with 6 columns; Files' 3-column
table was less broken but a full file path still doesn't comfortably share a row with
size/progress columns at this width.

**Trackers, Peers, and Files all moved from a `p-table` to a stacked card list** — one
`<li>` per row, each field on its own line/wrapped row instead of a column, matching the
style guide's own "mobile row" pattern (§06) that was already drawn for exactly this
narrow-width case. Each card keeps the same underlying data and the same
`app-status-indicator` ink-weight convention as before, just re-flowed vertically:

- **Trackers**: URL on its own line (`overflow-wrap: break-word`, not `word-break:
  break-all` — breaks only when an unbroken run doesn't fit, rather than every character),
  then tier + status, then seeders/leechers/last/next-announce as a small wrapped stat row.
- **Peers**: address:port, then both choke-status indicators (relabeled "They
  choke/unchoked us" / "We choke/unchoked them" — clearer stacked than the old table's bare
  "Choked"/"Unchoked" column headers), then download/upload rate. **`peerId` was dropped
  from this view entirely** rather than squeezed in — it's raw BEP 20 hex, not yet decoded
  into a client name (`design_docs/0031` left that decoding as a deferred, separate utility),
  so it wasn't human-meaningful even in the old wide table; it only had a column because
  there was spare width for it, which there no longer is.
- **Files**: full path on its own line, progress bar and byte counts below it.

`Piece map` was left as-is — its grid (`grid-template-columns: repeat(auto-fill,
minmax(10px, 1fr))`) already reflows its column count to whatever width it's given, so it
wasn't actually broken by the narrower space.

Also tightened while in here: `TorrentDetail`'s own header - `.detail-meta` (state tag,
DHT/tracker-count, error text) gained `flex-wrap: wrap` since it could overflow a narrow
width unwrapped, and the `<h1>` torrent-name heading shrank slightly (1.75rem → 1.375rem)
and gained `overflow-wrap: break-word` so one long unbroken filename can't force horizontal
overflow.

## Alternatives considered

- **Bottom detail panel** — the option the user weighed against the drawer; not built, see
  Decision above for the tradeoffs discussed.
- **Inline `p-table` row expansion** (no route at all) — raised as a lighter-weight
  alternative before the drawer/bottom-panel discussion; rejected once the user confirmed
  routing's benefits (refresh/back-button survival) were worth keeping.
- **`p-drawer`** — rejected once its structural content removal turned out to be
  incompatible with a routed outlet; see above.
- **A modal backdrop / focus trap** — rejected; the user's stated reason for preferring a
  drawer over a bottom panel was keeping the list visible *and usable*, which a modal
  backdrop directly works against.
- **Keeping `p-table` for Trackers/Peers/Files and just adding `overflow-x: auto`** —
  rejected for the follow-up above; horizontal scrolling inside an already-narrow,
  already-vertically-scrolling drawer is worse UX than reflowing the content, and the style
  guide already specifies a stacked-card treatment for exactly this width.
