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

## Alternatives considered

- **Replacing Aura outright with a from-scratch preset** - rejected; `definePreset(Aura,
  overrides)` is the supported PrimeNG extension mechanism specifically so every
  un-overridden token (spacing, transitions, focus rings, per-component structural tokens)
  keeps working exactly as Aura already ships it, which is the whole point of "vanilla as
  possible."
- **Hand-styling the guide's registration-mark corners and row-underlay progress onto
  PrimeNG components anyway** - rejected per the user's explicit maintenance-cost
  priority; see Decision above.
