import { definePreset } from '@primeuix/themes';
import Aura from '@primeng/themes/aura';

/**
 * Extends PrimeNG's Aura preset with the GrimTorrenter style guide's tokens
 * (style/torrent_list's STYLE_GUIDE_NOTES.md/README.md - colors, border radius, and, as of
 * task 8, the guide's explicit focus/hover/disabled interaction-state values. Component
 * structure and spacing stay exactly what Aura already ships, per the agreed rule: the style
 * guide is a starting point, but PrimeNG's own vanilla component behavior takes precedence
 * wherever the two would otherwise conflict (see the guide's bespoke "registration mark" card
 * corners, deliberately not reproduced here). Focus/hover/disabled states were a deliberate,
 * user-confirmed exception to that rule for task 8 specifically - the guide's own literal
 * values (2px focus ring, 12% ghost-hover tint, 45% disabled opacity) are applied through
 * PrimeNG's own token system below rather than left at Aura's differing defaults, so every
 * PrimeNG control matches this app's own hand-rolled ones (see styles.scss) for UI/UX
 * consistency - the priority the user named when this was flagged as a guide-vs-prior-decision
 * conflict.
 *
 * <p>Verified against this project's actually-installed package (`definePreset` lives in
 * `@primeuix/themes`, not `@primeng/themes` as most current docs/tutorials show; the
 * primitive border-radius token is a single `borderRadius` object, not a nested
 * `border.radius.*` structure, despite semantic tokens referencing it as `{border.radius.md}`)
 * by reading `node_modules/@primeuix/themes/dist/aura/base/index.mjs` directly, rather than
 * guessed - same "read the shipped source, don't guess a JS/TS library's API" precedent
 * design_docs/0020 already set for this frontend.
 *
 * <p>**Ramp-to-role mapping, read from Aura's own source rather than assumed** (the same
 * file above): a scheme's `primary.color` is `{primary.500}` in light but `{primary.400}`
 * in dark (`hoverColor`/`activeColor` step down again from there - 600/700 light, 300/200
 * dark); `text.color` is `{surface.700}` in light but `{surface.0}` in dark; `content.background`
 * is `{surface.0}` in light but `{surface.900}` in dark. The style guide's exact literal hex
 * values are placed at *those specific* indices below, not at whichever index a value's
 * "meaning" (e.g. "the darkest one") suggested - the previous version of this preset put
 * every guide value at a plausible-sounding slot without checking which slot each scheme's
 * role formulas actually read, which landed the dark-mode ramp fully inverted:
 * `content.background` (used directly by `--p-content-background`, e.g. the detail
 * drawer/footer) resolved to a near-white step and `text.color` to a near-black one - the
 * opposite of a readable dark theme. Fixed here; every step in between is straight-line RGB
 * interpolation between the guide's given anchors, not given directly by the guide.
 */
export const GrimTorrenterPreset = definePreset(Aura, {
  primitive: {
    borderRadius: { none: '0px', xs: '0px', sm: '0px', md: '0px', lg: '0px', xl: '0px' },
  },
  semantic: {
    /** design_docs/0032 task 8, README.md "Interaction states": `outline: 2px solid
     * var(--color-accent); outline-offset: 2px` on every `:focus-visible`, "never the browser
     * default." Aura's own default focusRing is `{width:"1px", style:"solid",
     * color:"{primary.color}", offset:"2px", shadow:"none"}` - style/color/offset already
     * match the guide exactly (color resolves to our own accent since `primary` *is* the
     * accent ramp), only `width` needs bumping. Every component's own focusRing token
     * (button, inputs, tabs, ...) references this shared one rather than hardcoding its own
     * width, so this one change is what makes every PrimeNG component's ring 2px app-wide -
     * see styles.scss's own `:focus-visible` rule for the hand-rolled elements this doesn't
     * reach. */
    focusRing: { width: '2px' },
    /** README.md "Interaction states": "Disabled: 45% opacity" - Aura's own default is 60%
     * (`disabledOpacity: "0.6"`, read from @primeuix/themes/dist/aura/base). One token,
     * applies to every disabled PrimeNG control app-wide. */
    disabledOpacity: '0.45',
    /** One ramp, shared by both schemes (Aura convention) - light reads `.500/.600/.700`
     * for color/hover/active, dark reads `.400/.300/.200`. `.900` (`--color-accent-900` in
     * the guide, the chrome top bar's "dark plate" field) is identical in both schemes, so
     * it only needs one slot. `.500`/`.400`/`.300`/`.200`/`.900` are the guide's exact
     * literals (`--color-accent` light/dark, `--color-accent-600`, `--color-accent-700`,
     * `--color-accent-900`); the rest is interpolation. */
    primary: {
      50: '#e9f4ff',
      100: '#d0e7fe',
      200: '#b5d9fd',
      300: '#94bce3',
      400: '#749dc4',
      500: '#5980a6',
      600: '#466685',
      700: '#334b63',
      800: '#203142',
      900: '#0d1620',
      950: '#080b10',
    },
    colorScheme: {
      light: {
        surface: {
          0: '#ffffff',
          50: '#f5f5f8',
          100: '#e9e9ea',
          200: '#e7e7ea',
          300: '#d4d4d7',
          400: '#b7b7ba',
          500: '#98989b',
          600: '#7a7a7d',
          /** `.700` is what `text.color`/`formField.color` actually read in light mode -
           * the guide's exact `--color-text` literal moved here from `.950` (unused by any
           * light-mode role), where the previous version of this preset had placed it. */
          700: '#1d1f20',
          800: '#151617',
          900: '#0e0f10',
          950: '#08090a',
        },
      },
      dark: {
        /** `.0` is `text.color`/`text.hoverColor`, `.900` is `content.background`, `.950`
         * is `formField.background` - the guide's exact `--color-text`/`--color-surface`/
         * `--color-bg` (dark) literals, in that order. Everything else is interpolation
         * between `.0` and `.900`, continued one step further to `.950`. */
        surface: {
          0: '#e6e8ea',
          50: '#d2d4d6',
          100: '#bec1c3',
          200: '#aaadaf',
          300: '#96999b',
          400: '#828588',
          500: '#6f7274',
          600: '#5b5e61',
          700: '#474a4d',
          800: '#333639',
          900: '#1f2325',
          950: '#171a1c',
        },
      },
    },
  },
  /** design_docs/0032 task 8, README.md "Interaction states": "Hover: ... 12% accent on ghost
   * controls." "Ghost controls" = PrimeNG's `text` button severity (every icon-only row
   * action, pause/resume-all, the panel's footer-menu button) - Aura's own default text-button
   * hover/active backgrounds are much fainter (`{primary.50}`/`{primary.100}` in light,
   * ~4%/~16% accent tints in dark) and differ between schemes, which doesn't read as "one
   * consistent ghost-button treatment" the way the guide (and this project's UI/UX priority on
   * consistency, confirmed with the user for task 8) wants. Overridden here identically for
   * both `primary` and `secondary` severity text buttons - the only two actually used in this
   * app - and identically across both color schemes, rather than leaving Aura's own
   * scheme-specific defaults, so every ghost button anywhere looks and behaves the same
   * regardless of severity or theme. `secondary` (only the seeding-limits dialog's Cancel
   * button today) intentionally gets the same accent tint rather than a neutral gray one, for
   * that same consistency reason - "one ramp step past base" is not otherwise closer to a
   * yes/no here than treating every ghost control identically. `activeBackground` (press) is
   * a stronger version of the same tint, not the guide's literal `--color-accent-600` example
   * from the same section - that example reads as aimed at *filled* controls (Aura's existing
   * default primary-button hover/active already resolves to exactly `--color-accent-600`/`-700`,
   * confirmed against this preset's own `primary` ramp comment above), and a solid ramp-color
   * fill would look glaringly inconsistent against every other interaction state in this app
   * (row hover, row selection, this same ghost hover) being a translucent wash rather than a
   * solid fill. */
  components: {
    button: {
      colorScheme: {
        light: {
          text: {
            primary: {
              hoverBackground: 'color-mix(in srgb, {primary.color}, transparent 88%)',
              activeBackground: 'color-mix(in srgb, {primary.color}, transparent 80%)',
            },
            secondary: {
              hoverBackground: 'color-mix(in srgb, {primary.color}, transparent 88%)',
              activeBackground: 'color-mix(in srgb, {primary.color}, transparent 80%)',
            },
          },
        },
        dark: {
          text: {
            primary: {
              hoverBackground: 'color-mix(in srgb, {primary.color}, transparent 88%)',
              activeBackground: 'color-mix(in srgb, {primary.color}, transparent 80%)',
            },
            secondary: {
              hoverBackground: 'color-mix(in srgb, {primary.color}, transparent 88%)',
              activeBackground: 'color-mix(in srgb, {primary.color}, transparent 80%)',
            },
          },
        },
      },
    },
  },
});
