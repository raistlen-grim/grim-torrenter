import { definePreset } from '@primeuix/themes';
import Aura from '@primeng/themes/aura';

/**
 * Extends PrimeNG's Aura preset with the GrimTorrenter style guide's tokens
 * (style_guide/ds/industry.css, style_guide/GrimTorrenter Style Guide.dc.html) - colors
 * and border radius only. Everything else (component structure, spacing, states, focus
 * handling) stays exactly what Aura already ships, per the agreed rule: the style guide
 * is a starting point, but PrimeNG's own vanilla component behavior takes precedence
 * wherever the two would otherwise conflict (see the guide's bespoke "registration mark"
 * card corners and full-row progress underlay, both deliberately not reproduced here).
 *
 * <p>Verified against this project's actually-installed package (`definePreset` lives in
 * `@primeuix/themes`, not `@primeng/themes` as most current docs/tutorials show; the
 * primitive border-radius token is a single `borderRadius` object, not a nested
 * `border.radius.*` structure, despite semantic tokens referencing it as `{border.radius.md}`)
 * by reading `node_modules/@primeuix/themes/dist/aura/base/index.mjs` directly, rather than
 * guessed - same "read the shipped source, don't guess a JS/TS library's API" precedent
 * design_docs/0020 already set for this frontend.
 *
 * <p>The style guide only specifies primary/surface color steps 100-900 (PrimeNG's scale
 * needs 50 and 950 too) and gives explicit dark-mode values for only a handful of roles,
 * not a full surface scale - the missing steps below are reasonable interpolation, not
 * given directly by the guide.
 */
export const GrimTorrenterPreset = definePreset(Aura, {
  primitive: {
    borderRadius: { none: '0px', xs: '0px', sm: '0px', md: '0px', lg: '0px', xl: '0px' },
  },
  semantic: {
    primary: {
      50: '#eef6ff',
      100: '#eef6ff',
      200: '#d6ebff',
      300: '#b5d9fd',
      400: '#94bce3',
      500: '#749dc4',
      600: '#597ea3',
      700: '#416180',
      800: '#2c455d',
      900: '#1d2d3d',
      950: '#13202c',
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
          700: '#5d5d60',
          800: '#424244',
          900: '#2b2b2d',
          950: '#1d1f20',
        },
      },
      dark: {
        surface: {
          0: '#26292b',
          50: '#232628',
          100: '#1f2325',
          200: '#1b1e20',
          300: '#171a1c',
          400: '#131517',
          500: '#a9acae',
          600: '#c2c4c6',
          700: '#d4d6d8',
          800: '#e0e2e3',
          900: '#e6e8ea',
          950: '#f2f3f4',
        },
      },
    },
  },
});
