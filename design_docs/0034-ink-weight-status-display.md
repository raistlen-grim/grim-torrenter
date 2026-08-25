# 0034 — Ink-weight status display (style pass, part 2)

**Status:** Accepted - the shared status display is built and applied everywhere a status
currently appears. See [[0035-spacing-table-density-and-empty-state]] for the rest of the
visual style pass (spacing scale, table conventions, empty-state copy).

## Decision

The style guide's most emphasized rule (one of its 5 core principles, "One hue, one
alarm") is: never color-code the torrent states. Status is carried by ink weight (opacity)
and an icon against a single accent color, with the guide's alarm color reserved
exclusively for an actual error - explicitly "no green seeding badge."

This directly conflicted with what was already built: every status-shaped value in the app
(torrent state in the list row and detail header, peer choke status, tracker
WORKING/ERROR/UNKNOWN) used `p-tag` with PrimeNG's built-in severity colors
(success/info/warn/danger/secondary → green/blue/orange/red/gray). [[0032-style-guide-and-primeng-theme]]'s
own tie-break rule ("PrimeNG's functionality wins over fighting it with custom CSS") would
normally argue for keeping that as-is, but this rule is central enough to the guide's
identity that it was raised with the user explicitly rather than resolved either way
silently. **Confirmed with the user: adopt the ink-weight approach**, across all four
usages, not just torrent state.

**Resolved without actually fighting `p-tag`**: the guide's own markup for status was
never a filled chip/badge to begin with - it's plain inline icon + text with color/opacity
applied directly, no pill shape at all. So this isn't "override `p-tag`'s severity CSS
with `!important`"; it's "don't use `p-tag` for status at all, use a different, smaller
component that matches what the design actually calls for." Vanilla-first meant picking
the right built-in tool, not force-fitting `p-tag` and then fighting it.

### `shared/status-indicator/status-indicator.ts` (new)

A tiny presentational component: `icon` (a PrimeIcons class, e.g. `pi-arrow-down`),
`label`, and `tone` (`'active' | 'dim' | 'alarm'`) inputs, rendering `<i aria-hidden>` +
label text. `tone-active` = `var(--p-primary-color)` at full opacity, `tone-dim` = inherited
ink at 55% opacity, `tone-alarm` = `var(--p-red-500)` at full opacity - three fixed rules,
not a fifth severity axis to keep synchronized with PrimeNG's own palette. `aria-hidden` on
the icon since the label text already carries the meaning (per this frontend's
accessibility conventions) - matches the guide's own rule that "icons never carry meaning
alone in a row."

Built once and reused everywhere a status renders, rather than four separate ad hoc
icon+color mappings - the four call sites (list row, detail header, Peers tab, Trackers
tab, plus the pending-upload placeholder row) would otherwise have had to independently
reinvent the same three-tone rule.

### `shared/status-display.ts` (new)

Two lookup functions, `torrentStateDisplay(state)` and `trackerStateDisplay(state)`,
mapping the backend's enums to `{icon, label, tone}`. Centralizing this also cleaned up a
small pre-existing duplication: `stateSeverity()` had been independently redefined
identically in both `torrent-row.ts` and `torrent-detail.ts`.

**Torrent state mapping** (derived from the guide's own `states` data table, not its
summary prose, where the two disagreed slightly on which states are "full-strength"):
DOWNLOADING (`pi-arrow-down`, active), SEEDING (`pi-arrow-up`, active), VERIFYING
(`pi-refresh`, active), STOPPED (`pi-pause`, **dim** - displayed as "Paused", matching the
guide's own vocabulary; the backend enum itself is untouched, this is a display-only label),
ERROR (`pi-exclamation-triangle`, alarm).

**Tracker state mapping** (the guide doesn't cover this directly - extrapolated from the
same one-hue principle): WORKING (`pi-check-circle`, active), ERROR
(`pi-exclamation-triangle`, alarm), UNKNOWN (`pi-circle`, dim).

**Peer choke status** (also not in the guide, same extrapolation) is simple enough to stay
inline in `peers-tab.html` rather than a third lookup function - Unchoked → `pi-lock-open`/
active, Choked → `pi-lock`/dim.

**Icons chosen as static, non-spinning** (`pi-refresh` for Verifying, not a spinning
`pi-spinner`) - deliberately calm, matching the guide's "twenty legible rows... comfort
comes from alignment and rhythm, not decoration" principle for the resting view. This is
distinct from [[0033-per-entry-action-feedback]]'s pending-action spinners, which *do*
spin - those represent an actual in-flight request the user just triggered, not a resting
status.

**The pending-upload placeholder row** (`torrent-list.html`, from
[[0029-optimistic-upload-feedback]]) was also converted, from a `secondary`-severity
`p-tag` to the same `app-status-indicator` (`pi-spin pi-spinner`, dim) - for consistency,
even though it's not one of the five real torrent states.

**The detail header's DHT badge** was also converted (from `p-tag severity="info"`, which
would have reintroduced a second non-accent hue right next to the now-fixed state
indicator, to `app-status-indicator` with `tone="active"`) - same reasoning applied
consistently rather than leaving one remaining spot exempted.

## Testing

Not independently unit-tested - `StatusIndicator` is a pure presentational component with
no logic beyond input passthrough, and `torrentStateDisplay`/`trackerStateDisplay` are
plain lookup tables with no branching to get wrong. Verified visually per the user's
"builds and tests are run manually" convention.

## Alternatives considered

- **Keep `p-tag` severities, treat "one hue" like the corner-marks/row-underlay
  exceptions** - the vanilla-first default per 0032's tie-break rule, and what would have
  been chosen without asking; raised explicitly instead because this rule is central to the
  guide's identity in a way the other two skipped items aren't. User chose to adopt it.
- **Override `p-tag`'s CSS custom properties per-severity** to force ink-weight colors
  out of the existing component - rejected once it became clear the guide's actual status
  markup isn't a badge/chip shape at all; there was nothing to override, only a different
  component to reach for.
