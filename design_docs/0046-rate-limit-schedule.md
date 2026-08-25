# 0046 — A daily off-hours rate-limit schedule

**Status:** Accepted

## Decision

The first of [[0045-settings-page]]'s "more rate-limiting settings are planned" additions,
and [[0042-rate-limiting]]'s "not built in this pass" per-torrent/burst items' sibling: lets a
user set a *different* upload/download limit that applies automatically during a daily time
window (e.g. higher limits overnight), instead of only ever having the one flat global cap.

**Scoped down from the outset**, confirmed with the user before building: a single window,
the same every day (not an arbitrary list of day-of-week-specific rules), and the scheduled
limits are just another upload/download limit pair - not constrained to be *higher* than the
base ones. Both were explicit, deliberate scope cuts, not oversights - see Alternatives.

### `Settings`: five new fields, a convenience constructor to keep every existing call site compiling

`rateLimitScheduleEnabled` (boolean), `rateLimitScheduleStart`/`rateLimitScheduleEnd` ("HH:mm"
24h strings, `LocalTime.parse`-compatible), `scheduledUploadRateLimitBytesPerSec`/
`scheduledDownloadRateLimitBytesPerSec` (same "0 or negative means unlimited" contract as the
base fields). Time-of-day is stored as a plain `String`, not `java.time.LocalTime`, so
`Settings` doesn't need a Jackson JSR-310 module wired in just to (de)serialize it - matches
the record's existing "plain primitives only" shape, and a native `<input type="time">`
already speaks exactly this format on the frontend, so nothing has to convert it either.

Growing `Settings`' canonical constructor from 4 to 9 parameters would have broken every
existing `new Settings(...)` call site across both modules' test suites. Instead, `Settings`
keeps its old 4-arg constructor as an explicit **second, non-canonical constructor**
delegating to the 9-arg one with the schedule defaulted to disabled - a plain Java records
feature, not a hack - so every pre-existing test compiles and behaves exactly as before,
matching this project's already-established "add a sibling overload, touch zero existing
call sites" pattern (0042's `enableDht`/`acceptIncomingConnections` precedent, cited in its
own Javadoc).

### `RateLimitSchedule`: a pure function of `(Settings, LocalTime)`, not a new stateful component

Resolving "is the schedule active right now, and if so what's the limit" doesn't need its own
poller, timer, or engine-lifecycle object - `RateLimiter` (design_docs/0042) already re-reads
`SettingsStore.current()` on every `acquire()` call, and already takes its limit as a
`ToLongFunction<Settings>` supplied at construction rather than a fixed value. `RateLimiters
.from()` just changed what that function does:
`Settings::uploadRateLimitBytesPerSec` became `settings -> RateLimitSchedule
.effectiveUploadLimit(settings, LocalTime.now())` - one new call reading the real clock, in
the one place `RateLimiter` already asks "what's the limit right now." No new polling, no new
background thread, no new engine wiring.

`RateLimitSchedule` itself takes the `LocalTime` as a parameter rather than calling
`LocalTime.now()` internally, purely so `RateLimitScheduleTest` can assert against fixed times
instead of depending on when the test happens to run.

**Window semantics**, each a small deliberate choice:
- **Crossing midnight is supported** (e.g. `23:00`-`07:00`) - "now is at/after start OR before
  end" instead of "between start and end," since "off hours" is the whole reason this exists
  and off-hours windows routinely cross midnight.
- **Start is inclusive, end is exclusive** - matches how time ranges are conventionally read
  ("from 9 to 5" doesn't include 5:00:00 itself), and keeps a hypothetical back-to-back pair of
  windows (not something this version has multiple of, but worth not painting into a corner)
  from double-covering the boundary instant.
- **`start == end` is treated as never active**, not as "always on" (24h) or "always off"
  applied some other way. Either of the other two readings would be a surprising, silent
  effect from what looks to a user like an empty/unset window.

### REST boundary: parseability checked in `SettingsResource`, not in the engine's hot path

`SettingsResource.update()` parses `rateLimitScheduleStart`/`End` with `LocalTime.parse()` and
rejects the request (400) if they don't parse - **only when `rateLimitScheduleEnabled` is
true**, since a disabled schedule's start/end are display-only and never read by
`RateLimitSchedule`. This is the system boundary handling user input
(`design_docs/CLAUDE.md`'s own "validate at system boundaries" rule) - `RateLimitSchedule`
itself trusts its input is already valid, rather than re-parsing defensively on every
`RateLimiter.acquire()` call in the hot path.

### Frontend: inline in the existing `RateLimitSettings` component, not a new nested one

The schedule is a sub-section of `rate-limit-settings.html`/`.ts`, not a further-nested
child component - it's a sub-feature of the same "Rate limiting" topic
[[0045-settings-page]] already gave its own group to, and at today's scope (one window) a
further split isn't earning its keep. `RateLimitSettingsForm` grew seven new controls
(`scheduleEnabled`, `scheduleStart`, `scheduleEnd`, and an Unlimited-checkbox pair each for
the schedule's upload/download limits, reusing [[0045-settings-page]]'s "Unlimited" checkbox
pattern verbatim) rather than becoming a nested `FormGroup` - keeping `rateLimitSettingsPatch`
a single flat function, consistent with the rest of this group.

**Two layers of disabled-state to keep in sync**: the section's own `scheduleEnabled` toggle
gates every field in it; the two limit fields *additionally* have their own Unlimited
checkbox on top of that. `syncScheduleDisabled()` recomputes both limit fields' disabled state
from *both* inputs' current values whenever either changes, rather than expressing it as two
independent subscriptions that could otherwise race and leave a control in the wrong state
(e.g. re-enabling a limit field the section toggle had just disabled).

## Not built in this pass

- Multiple schedule rules / day-of-week-specific windows - explicitly deferred; see Decision.
- A constraint that the schedule must loosen (not tighten) the base limit - explicitly not
  built; the schedule is just another limit pair.

## Alternatives considered

- **Multiple arbitrary schedule rules with day-of-week selection** - the more powerful
  alternative offered to the user; rejected in favor of the single-window scope above, which
  covers the stated "higher limits during off hours" need directly without a bigger data
  model, REST shape, and settings-page UI (a rule list, add/remove, per-rule day pickers) to
  build and maintain for a want that hasn't been expressed yet.
- **Require the scheduled limit to be higher than the base limit** - rejected; it would have
  meant cross-field validation (in both `RateLimitSettings`' reactive form and
  `SettingsResource`) for a constraint the user didn't actually want, since a schedule that
  *tightens* the cap during a window (e.g. daytime hours when the connection is needed for
  other things) is just as legitimate a use as loosening it overnight.
- **`java.time.LocalTime` as the `Settings` field type** - rejected; would need a Jackson
  JSR-310 module registered specifically for this, and buys nothing a plain "HH:mm" string
  doesn't already give a native `<input type="time">` for free.
- **A new nested settings group for the schedule** - rejected; see "Frontend" above.
