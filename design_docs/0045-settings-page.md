# 0045 — Real settings page: REST endpoint + extensible grouped form

**Status:** Accepted

## Decision

Fills in the `/settings` route that's existed as a placeholder since [[0043-app-shell-and-filtering]]
([[0009-phased-scope]]'s priority #1 next step per `PROGRESS.md`). Two parts: a REST endpoint
exposing `SettingsStore` ([[0041-live-settings-store]]), and a frontend page built to keep
absorbing new settings over time without restructuring - the user's explicit ask, since
today's four fields (DHT/incoming-connections toggles, upload/download rate limits) are
expected to grow, and rate limiting specifically will gain more fields of its own (per-torrent
overrides, a burst allowance - [[0042-rate-limiting]]'s "not built in this pass" list).

### Backend: `SettingsResource` (`/api/settings`)

`GET` returns `SettingsStore.current()`; `PUT` calls `update()` and returns the stored value
back (not just an echo of the request body, so the caller sees exactly what was persisted).

**No `SettingsView` wrapper record**, unlike `TorrentView`/`DhtStatusView`/`DiskUsageView` -
those all translate a live engine object (`TorrentSession`, `TorrentEngine.DhtStatus`) into a
DTO of primitives. `Settings` already *is* that DTO (a plain record, no engine internals) -
`JsonSettingsStore` already hands it straight to Jackson for the on-disk file, so doing the
same over HTTP isn't a new precedent, just the same serialization used a second way.

### Frontend: one component per settings group, composed by a thin container

The page is a container (`SettingsPage`) plus one presentational component per topic -
today `NetworkSettings` (DHT, accept-incoming-connections) and `RateLimitSettings` (upload/
download caps). **Rate limiting is its own group, not folded into Network**, specifically
because more rate-limiting-specific fields are already anticipated - grouping by topic now
means they slot into the existing `RateLimitSettings` component later rather than forcing a
regroup once there are more fields to sort.

Each group's `.ts` file exports three things, forming the contract a new group has to
implement:

- A typed `FormGroup` shape (e.g. `NetworkSettingsForm`) - just the fields that group owns.
- `buildXSettingsForm(settings: Settings): XSettingsForm` - constructs the group's form from
  the full `Settings` loaded from the backend.
- `xSettingsPatch(value): Partial<Settings>` - converts the group's form value back into the
  slice of `Settings` it owns.

The component itself is purely presentational: `readonly form = input.required<XSettingsForm>()`,
templated with PrimeNG form controls bound via `formControlName`. `SettingsPage` owns a single
top-level `FormGroup<{ network: NetworkSettingsForm; rateLimiting: RateLimitSettingsForm }>`,
built once (an `effect()` keyed on the one-shot `GET` resolving) by calling each group's
`buildXSettingsForm()`, and passes `settingsForm.controls.network` /
`.controls.rateLimiting` down to each child.

**Adding a settings group later** (e.g. a future "Storage" or "Peers" group) means: a new
component following the same three-export contract, a new key on `SettingsPage`'s top-level
`FormGroup`, a new `<app-x-settings [form]="...">` in the template, and folding
`xSettingsPatch(value.x)` into `save()`'s spread. None of the existing groups change.

**Single atomic save, not one per group.** `SettingsResource`'s `PUT` only exposes "replace
the whole `Settings` record" - there's no partial-update endpoint - so `save()` merges every
group's patch onto the last-loaded/saved `Settings` (`this.baseline`) and sends one `PUT`.
A per-group save button was considered and rejected: it would need either a partial-update
REST shape (more surface area for a page that's explicitly expected to keep growing) or each
group re-sending fields it doesn't own, just to feed the same all-or-nothing endpoint - neither
is worth it for what's currently a small, fast form.

### Rate limits: KiB/s display, bytes/sec model

`Settings.uploadRateLimitBytesPerSec`/`downloadRateLimitBytesPerSec` stay in bytes/sec
end-to-end on the wire (matching the backend record exactly, so `settings.model.ts` doesn't
invent its own shape) - the KiB/s conversion for display is entirely local to
`rate-limit-settings.ts`, at the two edges where a `FormGroup` is built from `Settings` and
where its value is turned back into a `Settings` patch. `0` (or a value that rounds to it)
stays exactly `0` in both directions rather than rounding into a tiny nonzero cap, matching
`Settings`' own "0 (or negative) means unlimited" contract.

### Update: an "Unlimited" checkbox instead of a "0 = unlimited" hint

Originally shipped as a plain hint string ("0 = unlimited") next to the KiB/s fields. Revised
after the user flagged it as too easy to miss and asked for either a clearer label or
replacing the displayed `0` with the word "Unlimited". Rejected replacing the number
in-place - `p-inputnumber` has no clean way to swap its displayed value for arbitrary text
while staying editable, and it would leave "did the user type 0 on purpose, or is that a
placeholder?" ambiguous. Instead, each field got a paired `uploadUnlimited`/
`downloadUnlimited` checkbox (`p-checkbox`, `[binary]="true"`) that `.disable()`s/`.enable()`s
the numeric control it sits next to - a `FormControl.disable()`/`enable()` pair driven from an
`effect()` in `RateLimitSettings`' constructor, not a template `[disabled]` binding (Angular
warns against binding `[disabled]` directly on a control already managed by
`formControlName`).

These two checkboxes aren't new `Settings` fields - they're a UI-only convenience derived from
`bytesPerSecond <= 0` when the form is built, and folded back into a plain `0` in
`rateLimitSettingsPatch()` when checked, before the PUT. `FormGroup.getRawValue()` (not
`.value`) is what makes this work end-to-end - it still returns a disabled control's last
value, so the numeric field's value survives being disabled without needing to be cleared or
special-cased on submit.

### Restart-required settings surfaced inline, not silently

`NetworkSettings`' template states directly that both its fields only take effect after a
restart - the same fact `Settings`' own Javadoc already calls out
([[0041-live-settings-store]]'s explicit, acknowledged exception) surfaced to the user instead
of only living in a code comment they'll never see.

## Not built in this pass

- Per-torrent rate limit overrides, a burst allowance - deferred, as before
  ([[0042-rate-limiting]]). `RateLimitSettings` is the seam they'll be added into.
- No client-side validation beyond `p-inputnumber`'s `[min]="0"` - the backend already treats
  any non-positive value as unlimited, so there's no invalid numeric state to guard against.

## Alternatives considered

- **A `SettingsView` DTO mirroring `Settings` field-for-field** - rejected; `Settings` has no
  engine internals to hide, so the wrapper would be a pure duplicate with no seam it's actually
  protecting.
- **One flat reactive form with no per-group components** - rejected given the user's explicit
  ask for the page to be built with many more settings in mind; a flat form works fine at four
  fields but every future field would mean editing an already-large template instead of adding
  a self-contained piece.
- **A per-group save button / partial-update endpoint** - rejected; see "Single atomic save"
  above.

## 2026-09-04 addendum: vertical section nav instead of a stacked single page

By this point the page had grown to seven groups (Appearance, Network, Rate limiting, Seeding,
Event log, Watch folder, Magnet fetching), all stacked vertically per the original design above
- long enough to be worth splitting into sections, at the user's request. Only the *presentation*
changes here; each group's `buildXSettingsForm()`/`xSettingsPatch()` contract, and the single
atomic `PUT`, are untouched.

**Considered and rejected: PrimeNG `p-tabs` (horizontal), the pattern
[[0044-torrent-detail-drawer]] already established for its Files/Peers/Trackers/Pieces tabs.**
That precedent is four short, single-word labels in a ~430px-wide drawer. Settings has seven
labels, several multi-word ("Rate limiting", "Event log", "Watch folder", "Magnet fetching"),
on a page that was 640px wide - the tab strip's own padding (`torrent-detail.scss`'s existing
`9px 11px` per tab) plus those label widths runs well past that, meaning wrap-to-two-rows or a
scroll-with-nav-arrows strip. Neither reads well for "here are your settings sections, pick
one," and it only gets worse once a future Notifications/Automation group (see `TODO.md`'s
notification-service and run-script-on-completion items) becomes an eighth entry.

**Built instead: a vertical left-nav list**, the standard treatment for "many settings groups
with long names that will keep growing" (GitHub, VS Code, macOS System Settings all use it for
this reason). A plain `<nav>` of `<button>`s bound to a local `activeGroup` signal (see "Tab
routing" below), not a PrimeNG component - `p-tabs` has no vertical orientation in the installed
version (confirmed against `primeng-tabs.d.ts`), and neither `p-listbox` nor `p-menu` carries the
"selected section" active-state semantics this needs without extra plumbing on top, so a plain
custom nav was the more honest fit than forcing a PrimeNG component into a shape it doesn't
support. Reuses the app's existing selection convention rather than inventing a new one - the
style guide's "2px accent left edge, never a filled row" rule, the same one `app-sidebar.scss`
already applies to the status-filter nav - kept as component-local CSS rather than extracted into
something shared, since the two navs differ enough (buttons vs. `routerLink`s, no icons/counts
here) that a shared partial would mostly be indirection.

`:host`'s max-width grew from 640px to 860px to fit the new nav column (`flex: 0 0 180px`)
alongside a content pane that keeps the same ~640px reading width the single-column page always
had - no child group's own template needed to change; each `<app-x-settings>` still just gets
its slice of the form, only now wrapped in a `[hidden]` div driven by `activeGroup()` instead of
always being on-screen. `[hidden]`, not an `@switch` that would destroy/recreate the inactive
groups' components, for the same reason `torrent-detail.scss` already documents for its own
non-lazy tab panels: every group's `FormGroup`/`FormControl`s already live on the top-level
`form` signal built once in `SettingsPage`'s constructor, so hiding vs. destroying a panel makes
no difference to any entered value's survival - but destroying and recreating a component like
`RateLimitSettings`, whose constructor wires an `effect()` to enable/disable its "Unlimited"
checkboxes' paired numeric fields, is an avoidable re-init on every tab switch for no benefit.

**Tab routing: local `signal`, not URL/query-param-tracked.** The torrent-detail drawer's own
Files/Peers/Trackers/Pieces tabs are route-driven (`route.firstChild`), because that tab
selection is meaningfully deep-linkable - e.g. a future Services-page hint could link straight to
a failed service's Peers tab. Settings groups aren't linked to from anywhere else in the app today
(confirmed with the user), so `activeGroup` is a plain component signal defaulting to
`'appearance'` on every visit - simpler, and avoids adding a `?tab=` query param this page's one
existing consumer (the sidebar's plain `/settings` link) has no need for. Revisit if a future
feature (e.g. a Services-page or Events-page link into a specific settings group) makes
deep-linking a real requirement.

**Known gap, not addressed here:** a validation error in a group that isn't the currently active
one disables Save with no visible indicator of which section has the problem - true of any
tab/nav-style split, not specific to the vertical-nav choice. Not built speculatively; revisit
(e.g. an error indicator on the relevant nav item) if it proves to matter in practice, the same
"wait for it to matter" treatment already applied to other deferred items in this doc.

**Follow-up fix, same day**: initially `.save-bar` was just a plain block after `.settings-layout`
in normal flow, so it sat below whichever was taller - the nav column or the active group's own
content - meaning a long group (Rate limiting's 7 fields, including the schedule block) pushed it
below the fold, flagged live by the user. Fixed the same way [[0044-torrent-detail-drawer]]'s own
`.panel-footer` already solves an identical shape: `:host` became a real flex column (`flex: 1;
min-height: 0`, participating in `.shell-main`'s own flex chain the way `torrent-list.scss`'s
`:host` already does, just without that page's full-bleed opt-out - `.shell-main`'s padding still
applies fine here), `.settings-content` is the one bounded, independently-scrolling region
(`flex: 1; min-height: 0; overflow-y: auto`), and `.save-bar` is a plain flex item after it -
`flex: none`, pinned by construction, not `position: sticky`. `.settings-nav` beside
`.settings-content` (via `.settings-layout`'s `align-items: stretch`) stays fully visible rather
than scrolling with the content pane, matching the reasoning that it's short and is how the user
navigates to the other groups in the first place.

## 2026-09-05 addendum: full control-shape rollout to all 7 groups

Followed `SETTINGS_PAGE.md` (a Claude Design handoff) and the actual prototype export
(`GrimTorrenter Settings (standalone).html`, a `.dc.html` canvas bundle - its real markup had
to be extracted from an embedded, escaped JSON string, not read as plain HTML) to collapse the
7 groups' 7 different control implementations down to 4 shared shapes: toggle, native select,
number+unit, number+unit+enable-toggle (`SETTINGS_LAYOUT_PATTERNS.md` catalogued the original
sprawl this replaces). Each group's own `fieldset`/`legend`/`.setting-row`/`.group-hint` CSS is
gone - that skeleton, and the group heading/hint text, now live once in `SettingsPage`
(`settings-page.ts`'s `SETTINGS_GROUPS` array, rendered by `settings-page.html`), not duplicated
per group. Every row shape is now global CSS (`styles.scss`, "Settings page redesign - shared
foundation" section) rather than per-component, for the same reason.

**Confirmed deviations from a strict reading of the handoff** (each already discussed and
decided with the user before implementing):
- **No registration-mark corners on cards/panels site-wide** - `design_docs/0032`'s existing
  exemption stands. Settings' own frame *does* get them (see that doc's own 2026-09-04
  addendum) - a scoped exception, not a reversal.
- **PrimeIcons, not the handoff's Lucide set**, for the 7 nav icons - `design_docs/0032`'s
  PrimeIcons-over-Lucide rule confirmed still standing; 5 of 7 have exact equivalents
  (`pi-palette`/`pi-wifi`/`pi-gauge`/`pi-arrow-up`/`pi-folder`), 2 substituted rather than
  reaching for a second icon system (`pi-history` for "scroll-text" - also matching the real
  Events sidebar item's own icon; `pi-link` for "magnet").
- **Row descriptions keep their restart/live-timing caveats** (e.g. "Takes effect after the app
  restarts") appended after the handoff's own shorter lead sentence, rather than being dropped -
  this doc's own "Restart-required settings surfaced inline, not silently" decision, upheld
  rather than silently reversed by adopting the handoff's copy verbatim.
- **`p-toggleswitch` stays**, not hand-rolled to match the prototype's own plain `<button>`+
  `<span>` markup (reasonable there - the prototype is static HTML with no framework) - Aura's
  toggle just needed new `components.toggleswitch` tokens (`grimtorrenter-preset.ts`: 34x20px,
  square, hairline-bordered, one knob color regardless of checked state) to hit the same look
  without leaving PrimeNG. Confirmed live once built.
- **A caught error in the handoff itself**: both `SETTINGS_PAGE.md` and the prototype's own code
  list Burst allowance's unit as "KB/s" - wrong, `rateLimitBurstSeconds` is a duration, not a
  speed. Kept "sec" rather than propagating the mistake.

**Rate limiting's fields renamed and inverted**: `uploadUnlimited`/`downloadUnlimited`/
`scheduleUploadUnlimited`/`scheduleDownloadUnlimited` (`true` = uncapped) became `uploadEnabled`/
`downloadEnabled`/`scheduleUploadEnabled`/`scheduleDownloadEnabled` (`true` = capped/active) -
user's explicit call, once flagged as a real conflict rather than silently building either
reading. The design's one enable-toggle shape (pattern 4) unifies what used to be two different
widgets for "on unless capped": Rate limiting's `p-checkbox` "Unlimited" (inverted sense) and
Seeding's `p-toggleswitch` "Enabled" (already the design's sense). Making Rate limiting match
Seeding's existing naming, rather than inverting the toggle's visual meaning at the template
level and leaving the stored semantics inverted, keeps "toggle on" meaning the same thing
everywhere on the page - touches `buildRateLimitSettingsForm`/`rateLimitSettingsPatch`/the
enable-disable sync effect in `rate-limit-settings.ts`, mechanically (every `unlimited` read
flipped to `!enabled`), not a behavior change to what gets persisted.

**Three real CSS bugs found via live verification, all against Aura/PrimeNG base styles rather
than this page's own code** - flagged here since they're exactly the kind of non-obvious
constraint that will bite again the next time a `p-inputgroup`/`p-toggleswitch` gets a custom
look:
1. **A row with a number+unit group *and* an enable-toggle clipped the toggle** - `.p-toggleswitch`
   has no `flex-shrink: 0` of its own, so once its row (`.settings-row-control`, `display: flex`)
   didn't fit, the browser shrank it along the main axis by default. Confirmed via a live
   devtools computed-box check: the clipped toggle's height was the intended 20px exactly (cross-
   axis, unaffected by shrinking) while its width had compressed to ~30px against the intended
   34px (main-axis) - the textbook flex-shrink signature, not a token-override failure. Fixed
   with `.settings-row-control > * { flex-shrink: 0; }` - `.settings-row-label` already has
   `min-width: 0` specifically so *it* is the side meant to compress/wrap under pressure, not the
   controls.
2. **The number+unit box measured wider than the intended 64px input + 84px addon, which is what
   caused (1)'s overflow in the first place**: Aura's own `.p-inputgroup` base CSS sets
   `width: 100%` and gives its wrapped input `flex: 1 1 auto; width: 1%` - built for a group that
   stretches to fill its row and grows the input into whatever space is left, the opposite of
   this design's fixed-width field. `inputStyleClass` only reaches the native `<input>`'s own
   attributes, not this stretch behavior on its `p-inputgroup`/`p-inputtext` ancestors. Fixed by
   overriding both, scoped to `.settings-number-group` specifically (not `.p-inputgroup`
   globally - the seeding-limits dialog's own inputgroups, design_docs/0054, still want Aura's
   default stretch-to-fill).
3. **That fix didn't apply on the first attempt**: `.settings-number-group { width: auto; }` and
   `.p-inputgroup { width: 100%; }` are both single-class selectors of equal specificity, so
   whichever stylesheet the browser applied later wins regardless of what's written - confirmed
   losing that tie live. Fixed by combining the element's own rendered tag name with the class
   (`p-inputgroup.settings-number-group`, confirmed as the real host element tag in devtools),
   which beats a bare `.p-inputgroup` class selector on specificity outright rather than
   depending on injection order.

**The unit-addon (`.settings-number-unit`) is a fixed 84px**, not content-sized like the
prototype's own inline styles - the audit's flagged inconsistency
(`SETTINGS_LAYOUT_PATTERNS.md`: different unit-word lengths rendering different overall box
widths) was still present after the initial rollout (fixing only the *input* to 64px left the
addon free to size itself to "seconds" vs. "peers" vs. "connections"), flagged live by the user.
84px comfortably fits "connections," the longest unit word in use; `text-align: center` so
shorter units don't look flush against the divider with empty trailing space.

## 2026-09-05 addendum: the two treatments extended to Events and Services

Once Settings' redesign was confirmed live, the user asked for the same visual language on the
rest of the app "where appropriate" - scoped deliberately, not a blanket site-wide pass (see
`TODO.md`'s own corner-mark rollout item). Landed on Events and Services specifically; the
torrent list's full-bleed layout ([[0043-app-shell-and-filtering]]) stays untouched - reversing
that deliberate decision was treated as a separate, bigger call the user didn't ask for here.

**Cinzel (`.display-font`) now on three things**: the app header's wordmark (`design_docs/0032`
always earmarked this exact spot - "available... for whichever future work touches the header,"
never used until now), and Events'/Services' own page titles, matching Settings'. Consolidated
into one global `h1.display-font` rule (`styles.scss`) rather than three components each
carrying their own copy of the same margin/size/weight - the user confirmed Settings' original
25px/600-weight sizing as the reference once asked, so that's what the shared rule uses.
`torrent-detail`'s own `<h1>` (the torrent's filename) is correctly excluded - `design_docs/0032`
already ruled that out ("filenames are data, never display type, never Cinzel"), unrelated to
this rollout.

**`.blueprint` frame added to Events and Services**, each now a bordered `.page-frame` (a new
shared global class - the padding/background Settings' own `.settings-content` already used,
consolidated once a second and third page needed the identical treatment) wrapping their
existing list markup, with the same 4-corner-mark markup pattern Settings uses. Neither page had
any bordered panel before this - they were plain full-width lists - so this is a real new layout
element for them, not just a decoration swap.

**All three pages share one `:host { max-width: 1100px }`**, picked deliberately over reusing
Settings' original 900px. Settings' 900px was sized for its own two-column layout (a 208px nav
plus a content pane calibrated to a ~640px form-reading width); applying that same total width
to Events/Services (which have no nav column) would still have left them a wider content pane
than Settings' own (896px vs. 636px) since they don't spend width on a sidebar, but Events'
3-column row (a 120px fixed time column, a 190px fixed type column, plus a body column that
needs real room for long torrent names) plus its own frame padding wanted more headroom than
that math alone suggested was safe, flagged by the user before implementing rather than assumed.
1100px gives Events/Services a ~1016px content pane and Settings a ~836px one - Settings' rows
end up a bit more spread out than the original 636px calibration (label max-width stays 48ch, so
the extra room just shows as more empty space before the fixed-width control side), a deliberate,
discussed tradeoff rather than an oversight.

**The torrent list stays the deliberate exception, full stop - no frame, no shared width, no
new heading.** Considered and rejected extending the `.blueprint` frame or the shared 1100px
width here: its full-bleed `:host` ([[0043-app-shell-and-filtering]]) isn't legacy styling, it's
load-bearing - the docked detail panel needs to reach the browser's true right edge, and the
list/panel scroll independently of each other, both of which depend on this page opting out of
`.shell-main`'s normal layout entirely. It's also the app's one genuinely wide multi-column
table, where the guide's own "density is respect" principle argues for more width, not less.
A `<h1 class="display-font">Torrents</h1>` was added and then reverted the same session - the
user's "keep it consistent" ask turned out to mean font usage generally, not a new page-level
heading on a page that never had one; this page still has no page-level heading, by design (the
toolbar is its only chrome).
