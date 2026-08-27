# 0032 — Style guide reconciliation and PrimeNG theme preset

**Status:** Accepted, theme preset built and wired in

## Decision

The user supplied a generated style guide (`style_guide/GrimTorrenter Style Guide.dc.html`
+ `style_guide/ds/industry.css`, the "Industry" design system - square corners, one steel
accent, tabular numerals, Barlow Condensed/Barlow, six torrent states carried by ink
weight rather than color). Confirmed with the user: PrimeNG components stay as close to
vanilla as possible to keep maintenance low - the guide is a starting point for theming
(colors, radius, type), and wherever it and PrimeNG's own supported component behavior
conflict, PrimeNG's functionality wins rather than being fought with custom CSS.

Two things in the guide are deliberately **not** reproduced under that rule:
- **Registration-mark corner decorations** on every card/panel - pure decoration with no
  PrimeNG concept behind it; reproducing it means hand-styling every `p-card`/panel
  instance, exactly the maintenance cost the vanilla-first rule exists to avoid.
- **Progress rendered as a full-row background underlay** in the torrent list - `p-table`
  rows don't support that pattern natively. The existing `p-progressBar` in its own column
  (already built, see [[0020-frontend-torrent-dashboard]]) stays as-is.

Also substituted: **PrimeIcons instead of the guide's Lucide icons**, since PrimeIcons is
already installed and is what PrimeNG's own components expect for icon inputs (buttons,
tags) - matching icon sets to what the component library natively takes is more "vanilla"
than wiring in a second icon system. Lucide would only be worth reaching for if a spot has
no PrimeIcons equivalent at all - not encountered yet.

### The preset (`app/theme/grimtorrenter-preset.ts`)

Built with `definePreset(Aura, {...})`, overriding only `primitive.borderRadius` (all
steps to `0px`, matching the guide's square-corners rule) and `semantic.primary` /
`semantic.colorScheme.{light,dark}.surface` (the guide's accent ramp and
background/surface/text roles). Nothing else - component paddings, focus rings,
transitions, and every other Aura default stay untouched.

**Verified against the actually-installed package rather than guessed** - `definePreset`
turns out to live in `@primeuix/themes`, not `@primeng/themes` as most current docs/
tutorials for older PrimeNG versions show (`@primeng/themes`'s own runtime export is just
`export * from "@primeuix/styled"` - checked directly in `node_modules`), and the
primitive border-radius token is a single `borderRadius: { none, xs, sm, md, lg, xl }`
object, not the nested `border.radius.*` structure its semantic-token references (`{border.radius.md}`)
would suggest. Read straight from `node_modules/@primeuix/themes/dist/aura/base/index.mjs`
- the same "read the shipped source for a JS/TS library's real API, don't guess" precedent
[[0020-frontend-torrent-dashboard]] already set for this frontend (as opposed to decompiling
Java bytecode, which [[0001-backend-language-and-framework]]'s conventions are about).
**`@primeuix/themes` was only present transitively** (pulled in by `@primeng/themes`) -
added as an explicit `dependencies` entry in `package.json` (version pinned to what was
already resolved in `node_modules`) since the app now imports it directly; the lockfile
itself needs `npm install` to catch up, left for the user per this project's "builds run
manually" convention.

**Color-scale gaps filled by interpolation, not given directly by the guide**: PrimeNG's
primary/surface scales need steps 50 and 950; the guide's own tokens only go 100-900.
Likewise the guide's dark-mode override only gives explicit values for a handful of roles
(bg/surface/text/divider/accent), not a full 11-step dark surface scale. Reasonable
interpolated values were used for the missing steps - worth a look once real components are
rendered in dark mode, since these weren't validated against anything the guide specified.

Type (Barlow Condensed for headings, Barlow for body) and tabular numerals are plain global
CSS (`styles.scss` + a `.heading-font`/`.tabular-nums` utility class), not part of the
PrimeNG preset - orthogonal to component theming, applies equally to any markup.

## Correction: dark-mode surface ramp was inverted, and several literal values were off by one ramp slot

**Found while starting the second style-guide pass** (a more detailed handoff bundle -
`style/torrent_list/`, superseding the original `style_guide/` folder as the source
document; the visual system itself is unchanged, just specified more precisely). Re-deriving
the preset's ramp-to-role mapping directly from Aura's own source (rather than assuming, per
this doc's own established practice) turned up a real bug, not just a fidelity gap: this
preset's original dark `colorScheme.surface` block put the guide's `--color-text` (dark)
value at ramp slot `.900` and its `--color-bg` (dark) value at slot `.300`/`.100`-ish - but
Aura's dark scheme actually reads `text.color` from `.0` and `content.background`
(`--p-content-background`, used directly by the detail drawer and `AppFooter`) from `.900`.
The ramp was fully inverted: dark mode's page/panel backgrounds resolved to a near-white
step and body text to a near-black one, opposite of a readable dark theme. Same root cause,
smaller effect, on the **light** ramp: `text.color`/`formField.color` read `.700`, but the
guide's exact `--color-text` (light) literal had been placed at `.950`, which no light-mode
role reads - light-mode text rendered as `.700`'s original placeholder gray instead. The
**primary** ramp had the same one-slot-off pattern (light's `.500` and dark's `.400`/`.300`/
`.200` are what `primary.color`/`hoverColor`/`activeColor` actually resolve, and the guide's
exact accent/accent-600/accent-700/accent-900 values weren't sitting on those slots).

Fixed by placing each guide-given exact literal at the specific ramp index its scheme's role
formula actually reads (documented inline in `grimtorrenter-preset.ts`), with every other
step a straight-line RGB interpolation between those anchors - not given directly by the
guide, same category of gap-filling as the original interpolated steps this doc already
called out below. Not independently verified in a browser per this project's "builds run
manually" convention - worth a visual check in both color schemes once this lands.

Also added as of this pass: `--rule`, `--color-divider`, `--color-accent`/`-600`/`-700`/
`-900`, `--color-bg`, `--color-surface`, `--color-text`, `--field-ink`, and `--alarm` as
plain global custom properties (`styles.scss`), aliasing the now-corrected PrimeNG semantic
vars where one exists (e.g. `--color-accent: var(--p-primary-color)`) and set directly where
none does (`--color-bg`, `--field-ink` - PrimeNG has no "page ground distinct from raised
surface" role). Lets later component work use the style guide's own token vocabulary
directly instead of re-deriving which PrimeNG variable backs each role each time. `--alarm`
is a literal `oklch(0.58 0.13 27)` (the guide's own value, not a converted hex) rather than
PrimeNG's red scale - **not yet applied to every red in the app**: `severity="danger"`
buttons (the row's remove split-button, the delete-files confirm dialog) still use PrimeNG's
own red tokens, deliberately deferred to the tasks that rework those specific controls
rather than swept in here. The four plain `error-detail` text spots (status-indicator, the
list row, the Trackers tab, the detail header) were switched to `--alarm` in this pass, since
that's a same-file, same-shape value swap with no structural change.

Also added: `--font-display` (Cinzel, loaded via the existing Google Fonts link in
`index.html`) as a third, distinct token alongside the pre-existing `--font-heading`
(Barlow Condensed) and `--font-body` (Barlow) - the guide's Typography section specifies all
three as separate roles, but only two were wired up (`.heading-font` conflated "display" and
"heading"). Not yet applied anywhere new (Cinzel isn't actually called for inside the torrent
list/details panel screens this bundle covers - "filenames are data, never display type,
never Cinzel" - it's really an app-shell/wordmark concern); available as `.display-font`/
`var(--font-display)` for whichever future work touches the header.

## Reversal: the row-underlay exemption is dropped

**Confirmed with the user** when starting the row-anatomy rewrite (`TorrentRow`/
`TorrentList`, the second style-guide pass's "Row anatomy" section): progress as a
full-row background underlay - one of the two things this doc originally exempted as
"`p-table` rows don't support that pattern natively" - is adopted after all, in exchange for
real custom CSS `p-progressBar` doesn't need. The technique: `:host` (the row) is
`position: relative`; every `<td>` except the state-icon cell gets `position: relative`
too, purely so its content paints in the CSS "positioned" layer, above the underlay, which
would otherwise paint over static content regardless of DOM order (the underlay is itself
`position: absolute`, and CSS's default painting order puts *all* positioned content above
*all* non-positioned in-flow content, independent of source order); the underlay div lives
inside the state-icon cell in markup but is positioned against `:host`, not that cell, so it
visually spans the full row rather than being clipped to one column's width. Documented
inline in `torrent-row.scss` rather than only here, since it's exactly the kind of
non-obvious constraint future edits to that file need to not accidentally break.

The **registration-mark corner decorations** exemption (the other thing this doc originally
declined) stands - not revisited, still pure decoration with no PrimeNG concept behind it.

## Row anatomy: further row-only decisions, not literal guide transcription

A few calls made implementing the row rewrite, beyond the token/underlay items above:

- **The row (not the name) is now the click target**, navigating to the torrent's detail
  route - the name is plain text, never a link, per the guide's explicit rule. Built as
  `tabindex="0"` + `(click)`/`(keydown.enter)` on the row's own host, replacing the previous
  `<a routerLink>` on the name; row actions stop click propagation so clicking them doesn't
  also navigate. `cursor: default` throughout, per the guide - a data row, not a hyperlink,
  even though it's now a full click/keyboard target.
- **The `Peers` column is dropped from the row entirely**, matching the guide's actual
  column list (state icon, name, size, state, done, down, up, ETA, actions - peer count only
  appears in the details panel's fact grid, not the row). This removes a value that was
  previously visible per-row without opening the panel - flagged explicitly rather than
  silently dropped, since it's real information leaving the row, not just a restyle.
- **Byte totals (downloaded/uploaded) leave the row.** The old Progress/Uploaded columns
  each showed a rate *and* a byte total on two lines; the new Done/Down/Up columns are a
  single value each (percent, download rate, upload rate) - byte totals move to the details
  panel, per the guide.
- **The always-visible remove button is gone from the row.** Actions are now exactly two
  24px ghost icon buttons (pause/resume toggle, `ellipsis`) - `ellipsis` opens the *same*
  `p-contextMenu` instance right-click already opened (already built per design_docs/0043),
  just from a second trigger, rather than a separate menu. Removal (both variants) now lives
  only in that menu.
- **Not adopted**: the guide's finer per-state icon-color table (distinct accent vs.
  accent-600 for Downloading vs. Seeding/Verifying, and an icon-vs-text opacity split for
  Paused). `StatusIndicator`'s existing three-tone system (active/dim/alarm, design_docs/0033)
  is shared by three other call sites this rewrite didn't touch; chasing the extra nuance
  would mean either widening that shared component's contract for one row's benefit or
  duplicating a near-identical fourth tone system just for this cell. Treated as a visual
  refinement to set aside, not a UI/UX location - the user's own stated exception for this
  pass.
- **`table-layout: fixed` + an explicit `pTemplate="colgroup"`** were added to `p-table`
  (`torrent-list.html`) - needed for the name column's ellipsis truncation to actually take
  effect at all. Without a fixed column width, a `<td>`'s default auto table-layout sizes to
  its content, so `text-overflow: ellipsis` on a flex child never triggers (the cell just
  keeps growing to fit the full filename instead of truncating it). Both are PrimeNG's own
  supported inputs/template slots (`[tableStyle]`, `pTemplate="colgroup"`), not a CSS
  workaround - consistent with 0032's vanilla-first rule.

## Toolbar: the bonded add control, adopted after an initial override

`ADD_CONTROL.md` (and its revision, `ADDENDUM_02_add_control_revision.md`) replace the
guide's original three-control toolbar ("Add Torrent" button, a separate magnet field, a
separate "Add Magnet" button) with one bonded unit: a single paste field that also accepts
`.torrent` drops, plus a primary button whose icon and label follow the field's contents
(`file-plus`/"Add file…" while empty → `plus`/"Add magnet" once a valid magnet is pasted).
The user's first review of this addendum explicitly rejected it for this app ("I think keep
both buttons, side by side... leave the function as is"), reasoning that on the web a magnet
paste is at least as likely as a file pick, since protocol-handler registration for
`magnet:` links is a fight most users never win - so hiding either path behind a state
change felt like the wrong tradeoff. That version shipped first (two restyled but
functionally separate Add Torrent / Add Magnet controls).

After seeing both toolbars side by side, the user reversed that call and asked for the
bonded control as specified. Implemented per `ADD_CONTROL.md`/`ADDENDUM_02` in full:
regex-plus-hardening validation (`xt=urn:btih:` required, not just the `magnet:` scheme; a
bare 40-char hex infohash is accepted and synthesised into a magnet URI), the field's
border as the validation channel (divider/accent/alarm), the echo strip below the toolbar,
page-wide `Cmd/Ctrl-V` paste (ignoring events targeting another text input/textarea/
contenteditable) and a full-window `.torrent` drop target with a dashed-border overlay,
Enter-to-add/Esc-to-clear, and newline-separated multi-magnet paste as a batch add.

**Deliberately not wired to a real action**: the guide's "Add link" path (pasting a
`.torrent` URL fetches and adds it server-side). There is no backend endpoint for
server-side URL fetch today, and building one silently would mean adding an
attacker-controlled-URL fetch surface (SSRF) without it having been asked for or designed.
A recognised `.torrent` URL is shown in the field's alarm state with copy explaining it
isn't supported yet, rather than either faking success or crashing on click. If this is
wanted, it needs its own scoping pass (endpoint + SSRF mitigation - allow-list or
deny-list of internal/link-local ranges, timeout, size cap - before it's built).

**Duplicate detection** ("Already in the list", primary reads "Show existing" and clicking
navigates to that torrent's row) is done client-side by comparing the pasted magnet's
`btih` infohash against `TorrentEventsService.torrents()` - no backend change needed, since
`addMagnet()` itself has no synchronous already-existed signal (design_docs/0028's
fire-and-forget async add). Only checked for a single 40-char-hex magnet; a base32 infohash
or a multi-magnet batch skips the check rather than mis-comparing or adding per-line
complexity.

**Not implemented from `ADD_CONTROL.md`**: the guide's responsive collapse rules
(filter field shrinking below 1080px, the add unit stacking full-width and growing to 44px
below 720px) - out of scope per this whole pass's earlier scoping decision to restyle only
the existing (desktop) layout, not build responsive breakpoints. The toolbar's trailing
`Columns` and `Details panel` icon buttons from the guide's "Toolbar, final order" are also
still absent, for the same reason (Columns popover and the details panel itself are
separate, not-yet-started tasks in this pass's breakdown).

## Details panel: dock-and-push (reverses design_docs/0044)

`README.md`'s "How the panel opens" section explicitly resolves an open question from the
original build: **the panel docks as a grid column and pushes the list, and must never float
over the table.** design_docs/0044 had deliberately chosen a fixed-position overlay drawer
(escaping `.shell-main`'s normal document flow via `position: fixed` + `top`/`bottom` pinned
to the shell header/footer height, with its own independent scroll region and a box-shadow) -
that choice is now reversed for this pass, per the user's own explicit pick between the two
options when this was scoped ("Dock-and-push (recommended)").

Implemented as a single `.list-panel-grid` wrapper around both the existing list content and
the panel: `grid-template-columns: minmax(0, 1fr) 0`, widening the second track to the guide's
exact `392px` via a `.panel-open` class bound to the same `isDetailOpen()` signal design_docs/
0044 already introduced (no new state - the panel's open/closed state is still fully
route-driven, `router.navigate(['/torrents', infoHash])` / `router.navigate(['/'])`). Because
the closed-state track is `0` width rather than the panel being conditionally rendered, the
`<router-outlet>` inside it never leaves the DOM - design_docs/0044's reason for a plain CSS
class toggle instead of `@if` still applies unchanged, just to a grid track now instead of a
`transform`. The guide's Motion section explicitly excludes a panel slide ("no panel slide" -
nothing here animates), so this is an instant class toggle, matching the guide, not a
regression from the old drawer's `transition: transform 0.2s ease`.

One structural side effect, called out because it's a behavior change beyond styling: the old
drawer used `position: fixed` specifically to escape `.shell-main`'s normal document flow and
manage its own independent scroll region, sized to the viewport between the shell header and
footer. A grid item can't do that (that's exactly the "floats over/independent of the table"
behavior the guide rules out) - so the panel now flows and scrolls with the rest of the page,
same as the list beside it, rather than being a separately-scrolling pane. This matches what
"docks and pushes" means literally, but is worth knowing if a very long tab content list
(Peers, Trackers) later wants its own internal scroll region - that's a `min-height`/`flex: 1`
concern for task 7's tab content, not something this task's grid mechanics provide for free.

**Row shedding while the panel is open** (guide: list keeps only state icon/name/Size/Done)
required a cross-component solution, not just CSS on one file: the column header and colgroup
live directly in `torrent-list.html`'s own `pTemplate` blocks (reachable by a plain scoped
selector, as established in the row-anatomy section above), but each torrent's own `<td>`s are
rendered by `TorrentRow`, a separately-encapsulated child component that a `torrent-list.scss`
selector cannot reach at all. Solved with a new `compact` input on `TorrentRow`
(`[compact]="isDetailOpen()"` from the parent), bound per-cell (`[class.col-hidden]`) on the
same five `<td>`s (state-text, down, up, eta, actions) that the parent hides via matching
`<col>`/`<th>` indices - `.col-hidden { display: none; }` lives in the global `styles.scss`
specifically because it's the one selector both components' otherwise-separate stylesheets
need to share. `display: none` (not `visibility: collapse`) was used throughout because it's
what actually removes a cell from the table's column-assignment box model; the reason it stays
aligned is that the *same* column indices are hidden everywhere at once (colgroup, header, and
every row variant including the pending-upload row, which was restructured from one
`colspan="5"` cell to five plain `<td>`s so each one can be targeted individually) - hiding
matching indices symmetrically keeps the remaining visible cells and the remaining visible
`<col>`s in the same relative order, so nothing misaligns.

**Not adopted**: the guide's toolbar `panel-right` toggle button and its `I` keyboard shortcut
(a persisted `panelPinned` pin/unpin state, independent of what's selected). Raised explicitly
with the user rather than added silently, since it's a real interaction-model decision (a
persisted pin vs. today's per-navigation state), not just a missing button - the user chose to
leave the panel fully route-driven (opening is "click a row," closing is "Esc or the panel's
own close") for this pass.

## Details panel: header, fact grid, progress bar, footer

Rebuilt `torrent-detail.html`/`.scss`/`.ts` per `README.md`'s "Panel structure" points 1-3 and
6 (header, fact grid, progress bar, action footer) - points 4-5 (tab strip/content) are task 7
and untouched here beyond a plain interim padding wrapper (`.tabs-wrap`) so they don't sit
flush against the panel edge in the meantime.

**Header**: the old three-line Cinzel `<h1>` and top-left "✕ Close" text link are gone, per the
guide - name is plain body text now (never Cinzel, filenames are data), clamped to two lines
with the full name in `title`; close is a 26px ghost icon button at top-right. `.header-name`
carries an explicit `min-height: calc(1.35em * 2)` (added once the user noticed everything
below it shifted depending on whether the current torrent's name happened to wrap) - without
it, a one-line name rendered a shorter header than a two-line one, since `-webkit-line-clamp`
only caps the *maximum*, it doesn't reserve room for it.

**Fact grid, 7 of the guide's 8 cells**: Size, Done, Down, Up, Ratio, Peers, Added - "Saved to"
was already dropped, see [[0057-torrent-added-at]]. `Ratio` (uploaded/downloaded) and `Peers`
are both worth a note:
- `Ratio` has no backend field - computed client-side, em-dash (never `∞`) when nothing's been
  downloaded yet, so there's no division to do.
- `Peers` reads as a bare connected count, not the guide's `9 of 43` (connected of known) -
  there is no reliable "known peers" figure available today (that would mean summing live
  tracker seeder/leecher counts, which are only fetched on-demand for the Trackers tab, not
  part of the always-pushed snapshot this header reads). Decided when this was raised with the
  user directly, before task 6 started.

**Not in the guide's fact grid, kept anyway**: `lastError`. The guide's 8-cell list has no
error cell at all, but this torrent's error was the single most load-bearing piece of
information the pre-restyle header showed (`No space`, `Hash mismatch`, etc.) - dropping it
silently to match the guide's cell count would be losing real information, not restyling, the
same call already made for the row's Peers column and byte totals. Rendered as a plain
`--alarm` line directly under the header instead.

**Also dropped from the header** (not in the guide, and now redundant or relocated):
- State text/icon - the row keeps its own state icon while the panel is open (see the
  dock-and-push section above), and that icon's own `title` tooltip already carries the label;
  repeating it here would be exactly the "progress reported three times" pattern this whole
  redesign exists to remove.
- DHT/tracker-count summary (`usesDht`, `trackerCount`, `dhtBackstopActive`) - not the guide's
  header at all; it's a natural fit for the Trackers tab's own per-source line (`[DHT]·[PeX]·
  [LSD] / Enabled`) that task 7 builds, so it's moving rather than disappearing.
- ETA - genuinely absent from the guide's panel (it's a row-only column, dropped along with
  the row's other rate columns while the panel is open - see the dock-and-push section above).
  While the panel is open, ETA is now nowhere visible for that torrent. Not raised as a
  question since this is what the current guide itself specifies, not a deviation from
  anything already agreed - flagged here for visibility, same as the row's dropped Peers
  column was.

**Progress bar**: 5px, renders only below 100% (a completed torrent just shows `Done: 100%` in
the fact grid, no dead bar underneath); striped fill + a `Checking N of M pieces` caption while
verifying, reusing the exact same striped-gradient values as the row's own verifying underlay
(`torrent-row.scss`) so the two read as one signal.

**Footer**: pause/resume (secondary/outlined) and a ghost `ellipsis` button carrying the full
overflow menu (same trimmed item set as `TorrentRow`'s own context menu - Copy magnet link,
Seeding limits, Remove, Remove and delete files) - duplicated here rather than shared with
`TorrentRow`, following the same self-contained-per-instance precedent
`SeedingLimitsDialog`'s embedding already set. `ConfirmationService`/`MessageService` are
injected, not re-provided: `TorrentDetail` is always rendered inside `TorrentList`'s own
`<router-outlet>`, so it resolves `TorrentList`'s existing instances (and its `<p-toast>`/
`<p-confirmDialog>`) via ordinary hierarchical DI, the same way `TorrentRow` already does.
Removing from inside the panel also navigates back to `/` afterward (unlike `TorrentRow`'s own
`onRemove()`), since the torrent that just disappeared is the entire view here, not one row
among many.

**Not built**: `Open folder`. Raised alongside "Saved to" ([[0057-torrent-added-at]]) and
rejected for the same reason - the guide's own affordance for it ("reveal in the file
manager") is a desktop-app action a browser tab cannot perform, and there's no download path
surfaced to act on anyway now that "Saved to" isn't built.

**Panel padding restructured**: the panel wrapper (`torrent-list.scss`'s `.detail-panel`) lost
the flat `var(--space-6)` padding it carried over from the old drawer in task 5 - header, fact
grid and footer now each own their exact padding straight from the guide instead (`13px 14px
12px`, `8px 14px` per cell, `10px 14px`), which only reads correctly if the wrapper around them
adds none of its own.

## Details panel: tabs

Restyled the tab strip and rebuilt all four tab-content components per `README.md`'s "Panel
structure" points 4-5 and its per-tab content specs.

**Tab strip**: `<p-tab>`/`<p-tablist>`/`<p-tabs>` all render with `ViewEncapsulation.None`
(confirmed by reading `primeng-tabs.mjs`) - but since these elements are written directly in
`torrent-detail.html`'s own template (not projected in through another component's
`pTemplate`), they still carry `TorrentDetail`'s own Angular-emulated scoping attribute like
any element there would, so a plain scoped selector in `torrent-detail.scss` reaches them with
no `::ng-deep` - same reasoning already used for `th`/`.sort-header` against `p-table`, now
against a fully-styled PrimeNG component instead of markup we fully own. Active-tab styling
targets PrimeNG's own reactive `data-p-active` attribute directly rather than a class binding
of our own.

**Default tab and persistence**: Files by default, Pieces only while downloading/verifying
("never open a completed torrent on Pieces"). Once the user picks a tab for a given torrent,
that choice persists for the rest of the session, per the guide - needed a new small
`TorrentDetailTabService` (a `Map<infoHash, tab>` behind a signal, same shape as the existing
`TorrentFilterService`) rather than a plain component field, since `TorrentDetail` is
destroyed and recreated whenever the `torrents/:infoHash` route deactivates and reactivates
(closing the panel and reopening it on the same torrent later), even though the router reuses
the same instance when just navigating between two already-open torrents. The guide's other
reason to hide the Pieces tab - "a magnet still fetching metadata" - doesn't apply to this app
at all: a torrent only ever becomes visible/selectable in the first place once its metadata is
already fully known (design_docs/0028), so there's no in-between state to guard against.

**Tab counts**: three of the four (Peers/Trackers/Pieces) read straight off fields already on
the torrent snapshot. Files has no such field, so `TorrentDetail` runs its own independent
poll of the same `files()` endpoint the Files tab itself polls, purely for a `.length` - traded
off against either a new backend field for a cosmetic count, or restructuring `FilesTab` to
take its data as an input just so the two could share one fetch.

**Per-tab content, real gaps found and how each was handled**:

- **Files**: type icon substituted from PrimeIcons (`pi-image`/`pi-video`/`pi-box`/`pi-file` -
  no direct file-text/file-video/file-archive equivalents exist). The guide's caption
  ("Priority is set per file from the right-click menu — skip, normal, first.") describes a
  feature that doesn't exist anywhere in this app - no per-file priority concept exists in the
  backend at all, and there's no right-click menu on file rows either. Dropped rather than
  shown pointing at nothing. Multi-select also not built - out of scope for this whole pass.
- **Peers**: the guide's `Done` column (per-peer completion) has no backing data - no per-peer
  piece-availability/bitfield is exposed anywhere, session-level or otherwise - dropped, grid
  narrowed to three columns. Client-name decoding (next to the address) was already a known,
  previously-deferred gap (design_docs/0031), not solved here either. The row's existing
  choke/interest indicators (previously full `StatusIndicator` badges) don't fit the guide's
  tight column grid at all, but dropping that protocol state entirely would be losing real
  information rather than restyling - kept as small inline icons next to the address instead
  of a full badge row. Sort-by-upload-rate-descending is implemented (no data gap there).
- **Trackers**: working trackers collapse into one summary line, per the guide, and per-tracker
  seeders/leechers/announce times move into a tooltip on each still-individually-listed
  (non-`WORKING`) tracker - not shown anywhere for a collapsed healthy tracker, matching the
  guide's own "a list nobody reads" reasoning rather than working around it. The guide's peer
  sources line drops `[LSD]` entirely - Local Service Discovery isn't implemented anywhere in
  this engine, unlike the other gaps here, which had real data just not surfaced yet. `[PeX]`
  always reads Enabled (no per-torrent toggle exists - it's unconditionally advertised); `[DHT]`
  reflects `usesDht || dhtBackstopActive`.
- **Pieces**: fixed `repeat(26, 1fr)` - the guide's own "reduce column count as the panel
  narrows" responsive rule is moot at this panel's current fixed 392px width (no narrower
  breakpoint exists in this pass's scope). `Availability 2.1×` dropped from the caption per
  the guide's own documented fallback for exactly this case ("if [piece availability] is not
  already exposed, the caption can drop [it] rather than adding a request"). Piece size
  ("512 KB each") *was* added: `TorrentMetadata.pieceLength()` already existed engine-side but
  was never exposed - added a small `PiecesView` wrapper record (`{pieces, pieceLength}`)
  around the `/pieces` endpoint's previously-bare array, a genuinely no-persistence,
  no-migration addition (unlike [[0057-torrent-added-at]]), after checking with the user
  first rather than assuming.

**Deferred, not solved here**: "pinned to the bottom" (footer) and tab content "scrolling
internally" (README point 5) both need a bounded-height ancestor to flex/scroll within, which
this panel's chain no longer has anywhere - task 5 removed the old drawer's
`position: fixed` viewport clamp specifically because that was the floating-overlay behavior
the guide's own docking spec rules out. Reintroducing a bounded height just to make the footer
sticky and give tab content its own scrollbar would mean re-litigating that task 5 call, not a
task-7-sized change - left as a real, flagged gap. Today the whole page scrolls together when
tab content runs long, and the footer sits after the tabs in normal flow rather than pinned.

## Post-task-7 visual bug pass

Six issues surfaced from actually running the restyled list/panel in a browser (something
none of tasks 1-7 could verify directly - see CLAUDE.md's "builds and tests run manually by
the user"). Fixed together since several share a root cause:

- **Docked panel not full height when content is short** and **page margin around the whole
  list/panel area**: both trace back to `.shell-main` (`app.scss`) never giving a routed
  page's own host a real, bounded height or width to fill - `:host`'s `min-height: 100vh`
  only padded the *page* to a full viewport, not any element inside it, so a short torrent
  list left a visible gap between where the docked panel's own background actually ended and
  where the page's own bottom edge was. Fixed by threading a proper flex chain down from the
  app root (`:host` → `.shell-body` → `.shell-main`, each now `flex: 1; min-height: 0` on the
  next link) so `TorrentList`'s own host, and `.list-panel-grid` inside it, can do the same
  and genuinely fill whatever vertical space is available - short content still leaves
  whitespace, but now *inside* the panel's own tinted background, not below it.
  Separately, **full bleed for the torrent list page specifically** (explicit user choice,
  not the shell's default - every other routed page keeps `.shell-main`'s existing padding
  unchanged): `TorrentList`'s own `:host` cancels that padding horizontally via a negative
  margin, restoring a left-only inset on `.list-column` (sidebar clearance) and leaving the
  panel side at 0 so it reaches the browser's true right edge, matching "docks... no
  shadow, no backdrop, no overlay" read literally rather than floating inside a leftover gap.
- **Horizontal scroll from a long tracker error, plus a resulting stray vertical scrollbar**:
  the Trackers tab's status column was `auto`-width with no cap (per the guide's own literal
  spec), which sizes itself to a raw tracker error string's full length - and a real tracker
  error can echo back a full announce URL, easily hundreds of pixels wide. Capped to
  `minmax(0, 160px)` with ellipsis + a `title` for the full text (the row already carries a
  tooltip with seeders/leechers/announce times, so hovering surfaces both). The vertical
  scrollbar was very likely a side effect of the same overflow, not a separate bug - fixing
  the horizontal overflow is expected to resolve both.
- **Row name/extension split visually detaching**: `.name-cell` (the `<td>`) had no
  `overflow: hidden` of its own - a table cell doesn't clip its content by default, so a name
  long enough to outrun its column's fixed width before the flex child's own ellipsis
  "caught up" would visually spill past the cell, dragging `.name-extension` far to the right
  with it rather than sitting right after the truncated stem. Also gave `<p-table>` an
  explicit `width: 100%` alongside `table-layout: fixed` - the fixed-column-width algorithm
  needs a determinate table width to divide the unspecified (Name) column's share of space
  from; without it, that column's width could end up governed by content instead.
- **Size column values wrapping to two lines**: `.size-cell` had no `white-space: nowrap` of
  its own (nothing in this app's `td` styling sets it globally), so a value like
  "1004.8 MB" could wrap at the space once its column was even slightly narrower than the
  text. Added `nowrap` to it and the other short single-value cells (Done, Down, Up, ETA -
  *not* the state-text cell, which can carry a genuinely long `lastError` line in
  `.error-detail` that should keep wrapping normally) and widened the Size column from 72px
  to 84px.

## Second visual bug round

- **Panel footer not pinned to the bottom** (a short-content gap between the tabs and the
  footer, then a much larger gap before the app footer): the bounded-height chain built for
  the "panel not full height" fix above turned out to be exactly the missing piece task 7's
  own deferred "pinned to the bottom" note was waiting on. `TorrentDetail`'s `:host` is a
  flex column, and `p-tabs`/`p-tabpanels`/the active `p-tabpanel` are `flex: 1` within it, so
  the tab area grows to fill whatever's left, pushing the footer to the true bottom - but that
  alone did nothing the first time around: `.detail-panel` (the `<aside>` wrapper) was still a
  plain block element, and flex/grid "stretch" only reaches as far as the *immediate* child -
  `.list-panel-grid`'s own `align-items: stretch` correctly gave `.detail-panel` itself the
  full height, but nothing propagated that any further down, so `TorrentDetail`'s `flex: 1`
  had no flex container to actually apply against. Fixed by also giving `.detail-panel` its
  own `display: flex; flex-direction: column`, completing the chain. Still `min-height`, not
  a capped `max-height`: nothing
  above this in the chain is actually capped (`min-height: 100vh` at the app root is a floor,
  not a ceiling, precisely so the whole page keeps growing and scrolling as one unit for long
  content - see the dock-and-push section's own reasoning). So this only visibly pins the
  footer when content is short enough to fit in the available space; genuinely long tab
  content still just grows the whole page, same as before - a real fix for the reported gap,
  not the harder "tab content scrolls internally" requirement, which still needs an actual
  height ceiling somewhere to mean anything and remains its own explicit follow-up.
- **Nav sidebar (unrelated, found while diagnosing the above under a misread report)**:
  Events/Settings now pin to the bottom of the sidebar (`align-self: stretch` restored instead
  of `start`, `margin-top: auto` on the Events item) rather than trailing directly under the
  filter list - not something the guide bundle specifies (the nav sidebar predates this restyle
  pass entirely), kept because it's a reasonable, low-risk improvement in its own right.
- **Page margin / horizontal scrollbar, still unresolved**: added `overflow-x: hidden` at both
  `TorrentList`'s own `:host` and `.list-column`, matching the guide's explicit "never let the
  row scroll horizontally" rule regardless of root cause. Also confirmed PrimeNG's own
  `.p-datatable-table` already carries `width: 100%` by default (found reading
  `@primeuix/styles`'s datatable source) - the earlier `[tableStyle]` `width: '100%'` addition
  was redundant, not wrong, and table-layout:fixed's column math should already have had a
  determinate width to work against before that change. Root cause of the still-reported
  margin/scrollbar not confirmed; the CSS reasoning for the full-bleed negative-margin
  approach checks out on paper (verified against the flexbox spec's stretch-with-negative-
  margins behavior), so this needs a live re-check rather than more blind changes.

## Third visual bug round

- **Horizontal scrollbar directly under the table, root cause found**: PrimeNG's `Table`
  component always sets `overflow: auto` on its internal `.p-datatable-table-container` via
  an *inline* style (`TableStyle.inlineStyles` in `primeng-table.mjs`) - unconditional, not
  gated behind the `scrollable` input this app doesn't set. An inline style beats any external
  stylesheet rule regardless of specificity, which is why the previous round's `overflow-x:
  hidden` on ancestor elements (`TorrentList`'s `:host`, `.list-column`) never touched it -
  those targeted the wrong element entirely, and even a correctly-targeted rule couldn't have
  won against an inline style without `!important`. This element is also PrimeNG's own
  internal markup, not declared in any of this app's own component templates, so no *scoped*
  selector could reach it either way - global `styles.scss` plus `!important` is the only
  actual way in, added there rather than the previous round's now-superseded ancestor rules
  (left in place; harmless, and still the right defensive belt-and-suspenders for anything
  else in the list that might someday try to force width). A sub-pixel layout-rounding
  mismatch between the table's own `width: 100%` and this container's content box is enough
  to trigger PrimeNG's always-on `overflow: auto` and show a scrollbar for nothing there was
  ever a reason to scroll to.
- **Row height changing when the panel opens**, five attempts:
  1. No row height was ever set explicitly - it fell out of whichever cell's content happened
     to be tallest, and `.actions-cell` (real `p-button` elements, taller than any plain
     text/icon cell) is exactly the column this doc's dock-and-push work hides while the
     panel is open (`TorrentRow`'s `compact` input). Losing that cell dropped the row's
     tallest content, so the row visibly shrank. First fix: an explicit `height: 34px` on the
     row (README's own row-anatomy number), intended as a floor (not a cap) since `height` on
     a `<tr>` is documented as a *minimum* per the CSS table-layout spec.
  2. That alone didn't close the gap: `size="small"` p-buttons render taller than the guide's
     actual 24px row-action-button spec once their own padding is added, so the non-compact
     row's *natural* content height already exceeded the 34px floor - a floor only stops a
     row going *below* its value, it can't pull an already-taller one down. Second fix: sized
     the row-action buttons to the guide's literal 24px (`.row-action-btn` in `styles.scss` -
     see below for why it has to live there, not in `torrent-row.scss`).
  3. Live-tested after that and the two states were still measurably different (21px content
     + 6px padding open vs. 24px + 6px closed) - `height` on the `<tr>` itself turned out to
     have no real effect at all in practice, confirmed live, not just a remaining few pixels
     of imprecision. Third fix: moved the floor from `:host` (the row) to `min-height: 34px`
     on every `<td>` instead, reasoning that a table row's rendered height is always the max
     of its cells' heights regardless of whatever the `<tr>`-height quirk turned out to be.
  4. Also confirmed to have no live effect: `min-height: 34px` on every `<td>`, reasoning
     that a table row's rendered height is always the max of its cells' heights regardless of
     whatever the `<tr>`-height quirk from attempt 1 turned out to be.
  5. Plain `height: 34px` on every `<td>` (not `min-height`) - the CSS table-layout spec does
     give cells (unlike rows) an actual defined rule: "the height of a cell is the maximum of
     its own `height` and the height its content needs," which should make `height`
     specifically a real floor there even though `min-height` isn't defined for table parts
     at all. Live-inspected the computed box model directly this time (devtools, not just
     eyeballing) and it was **still** measurably different - 24px content + 6px padding closed
     vs. 21px + 6px open, neither anywhere near 34. So all four of the "set a floor value
     somewhere" attempts above were dead ends in this app's actual rendering, not just
     imprecise. The devtools measurement also revealed *why* a floor could never fully work
     even in principle: the closed state's real content need (36px total, from the 24px
     button) is already **above** the guide's 34px number, so a 34px floor could only ever
     have affected the open state - the two states' natural heights were never going to
     converge on a value neither of them naturally exceeds.

     Fifth fix, the one that actually held: stopped trying to impose a floor on the `<tr>`/
     `<td>` at all, and instead gave `.name-inner` (name-cell's existing flex wrapper, present
     and identical in both states) an explicit `height: 24px` - exactly matching
     `.row-action-btn`'s own height. `.name-inner` is a normal flex box, not a table part, so
     an explicit height on it is unambiguously respected (no table-specific ambiguity to run
     into); "a cell grows to fit its content" and "every cell in a row shares the row's
     height" are both uncontested, un-ambiguous parts of table layout. With `.name-cell`
     needing the identical 24px of content height as `.actions-cell` in *every* row now, by
     construction, the row's actual height is the same in both states without relying on any
     explicit height/min-height on a `<tr>` or `<td>` succeeding at all.

  **Why `.row-action-btn` lives in `styles.scss`, not `torrent-row.scss`**: `<p-button>`'s
  actual rendered `<button class="p-button">` is built by `Button`'s own template (also
  `ViewEncapsulation.None`, like `Table`/`Tabs`) - but unlike `p-tab`/`p-tabpanel`, whose
  template is just `<ng-content>` (so *our own projected content* stays attributed to
  whichever component wrote it), `Button` constructs the `<button>` itself from scratch. It
  isn't content this app projected into it, so it carries neither `Button`'s own scoping
  attribute nor `TorrentRow`'s, and no selector in `torrent-row.scss` can reach it regardless
  of encapsulation - same unreachability as `.p-datatable-table-container` above, different
  cause. `.row-action-btn` (a plain class on the `<p-button>` host tag itself, which *is*
  reachable from `torrent-row.scss` since that tag is written directly in `torrent-row.html`)
  exists purely so a global rule can target just these two buttons instead of every `p-button`
  in the app.
- **Name/extension detaching for short filenames**: `.name-stem`'s `flex: 1 1 auto` was meant
  to let it *shrink* (the truncation case, flex-shrink: 1) but the same declaration's
  flex-grow: 1 also made it *expand* to fill any unused space in the column - which a short
  filename, not needing the column's full width, always leaves some of. The visible text
  doesn't stretch to fill that wider box (it just sits left-aligned inside it), but
  `.name-extension` - a flex sibling, positioned at the end of the *stem's box*, not the end
  of its *visible text* - ends up stranded off to the right, worse the shorter the name is
  (more leftover space to grow into). This one was never actually about `.name-cell`'s
  overflow/table-width (the fix from the first visual bug round) at all - that fix was real
  and needed (long names genuinely were spilling past the column before it), but a second,
  unrelated bug remained for short names specifically, only caught once the user could compare
  a short name's rendering against a long one's side by side. Fixed by dropping the stem's
  flex-grow to 0 (`flex: 0 1 auto`) - still shrinks when the row's too narrow, never grows
  past its own text's natural width otherwise.

## Fourth visual bug round: the deferred viewport-height cap, resolved

Every earlier round that touched the panel's height explicitly deferred one thing: task 5
removed the old drawer's `position: fixed` viewport clamp because it was the literal
floating-overlay behavior the guide's docking spec rules out, and everything built afterward
(footer-pinning, tab strip/content) was declared "min-height, not max-height" - a floor that
lets the whole page grow and scroll together, with no ceiling anywhere for tab content to
scroll *within*. The user hit this directly: a torrent with enough trackers/peers grew the
panel past the bottom of the viewport, pushing the footer off-screen entirely rather than
just needing an internal scrollbar.

**Resolution**: a real viewport-height cap on `.detail-panel.open` - `position: sticky; top:
var(--shell-header-height); max-height: calc(100vh - var(--shell-header-height) -
var(--shell-footer-height)); overflow: hidden;` - plus `overflow-y: auto` on the active
`p-tabpanel`, now meaningful since there's finally something real to be constrained against.

This is *not* a reversal of task 5's call, even though it looks similar on the surface -
`app-sidebar.scss`'s nav sidebar already uses the exact same `position: sticky` + `max-height`
+ `overflow-y: auto` pattern, and nobody has called *that* a floating overlay. The distinction
the guide's docking spec actually cares about is `position: fixed` (escapes the document
entirely, paints over content regardless of scroll position, needs its own z-index stacking)
versus `position: sticky` (a normal flow/grid item that happens to stay in view as its
scrolling ancestor scrolls, still occupies its column, still pushes nothing, no z-index
games). The panel remains exactly where the grid puts it; it just also stays visible and
caps its own height the same way the sidebar beside it always has.

**What changes in practice**: header, fact grid, progress bar, tab strip and footer all stay
on-screen and fully visible regardless of tab content length; only the active tab's own
content area scrolls once it exceeds the space actually available in the viewport. Short
content still behaves exactly as the previous rounds already fixed it (footer sits right
after it, not pinned artificially high) - the cap and the scroll only ever activate once
content genuinely needs more room than the viewport has to give.

## Alternatives considered

- **Replacing Aura outright with a from-scratch preset** - rejected; `definePreset(Aura,
  overrides)` is the supported PrimeNG extension mechanism specifically so every
  un-overridden token (spacing, transitions, focus rings, per-component structural tokens)
  keeps working exactly as Aura already ships it, which is the whole point of "vanilla as
  possible."
- **Hand-styling the guide's registration-mark corners and row-underlay progress onto
  PrimeNG components anyway** - rejected per the user's explicit maintenance-cost
  priority; see Decision above.
