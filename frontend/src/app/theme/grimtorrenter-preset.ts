import { definePreset } from '@primeuix/themes';
import Aura from '@primeng/themes/aura';

/**
 * Extends PrimeNG's Aura preset with the GrimTorrenter style guide's tokens
 * (style/torrent_list's STYLE_GUIDE_NOTES.md/README.md - colors and border radius only.
 * Everything else (component structure, spacing, states, focus handling) stays exactly
 * what Aura already ships, per the agreed rule: the style guide is a starting point, but
 * PrimeNG's own vanilla component behavior takes precedence wherever the two would
 * otherwise conflict (see the guide's bespoke "registration mark" card corners, deliberately
 * not reproduced here).
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
});
