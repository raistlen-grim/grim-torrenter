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
