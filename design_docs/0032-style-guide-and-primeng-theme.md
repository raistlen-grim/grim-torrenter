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

## Alternatives considered

- **Replacing Aura outright with a from-scratch preset** - rejected; `definePreset(Aura,
  overrides)` is the supported PrimeNG extension mechanism specifically so every
  un-overridden token (spacing, transitions, focus rings, per-component structural tokens)
  keeps working exactly as Aura already ships it, which is the whole point of "vanilla as
  possible."
- **Hand-styling the guide's registration-mark corners and row-underlay progress onto
  PrimeNG components anyway** - rejected per the user's explicit maintenance-cost
  priority; see Decision above.
