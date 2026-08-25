# 0053 — Rate-limit burst allowance

**Status:** Accepted

## Decision

The next rate-limiting settings-group addition [[0045-settings-page]]/[[0046-rate-limit-schedule]]
already anticipated. Extends `RateLimiter`'s existing token-bucket capacity - previously
hardcoded to "one second's worth of the current limit" - with a configurable multiplier, so a
naturally bursty workload can save up unused bandwidth and spend it faster than the steady-state
rate would otherwise allow, instead of always being smoothed to exactly the per-second cap.

### One shared `rateLimitBurstSeconds`, not a value per direction/window

A single new `Settings` field (`long rateLimitBurstSeconds`), not a separate burst value per
upload/download or per base/scheduled limit. The capacity it controls
(`limit * burstSeconds`) is always derived from whatever limit is actually in effect at the
time `RateLimiter.acquire()` runs - the same limit-resolution seam `RateLimiters.from()`
already built for the schedule ([[0046-rate-limit-schedule]]'s `ToLongFunction<Settings>`) - so
one duration naturally scales correctly regardless of which direction or which window's limit
is active, without needing four separate fields to stay in sync with four separate limits.

### `0` (or negative) means the original 1-second default, not "no burst"

Every other 0-or-negative-means-X convention in `Settings` uses it to mean "unlimited" (the
rate limits themselves, [[0042-rate-limiting]]). Burst couldn't reuse that meaning: an
unconfigured or legacy `settings.json` missing this field deserializes it to Java's default
`0` for a primitive `long` (no null to detect, unlike [[0052-message-stream-encryption]]'s
`encryptionMode` - see that doc's own compact-constructor fix), and if `0` meant "no burst",
every existing installation would silently become *stricter* than it was before this feature
existed the moment they upgraded. `RateLimiter.burstSeconds()` treats any value `<= 0` as the
original default of `1` instead, preserving exactly today's behavior for anyone who never
touches the new field.

### `RateLimiter` changes

`refill()` gained a `burstSeconds` parameter; `acquire()` now reads the whole `Settings`
snapshot once per iteration (rather than the limit alone) so both the resolved limit and the
resolved burst come from the same live snapshot, not two separately-timed reads. Capacity
becomes `Math.max(limit * burstSeconds, pendingRequestBytes)` - the existing
`pendingRequestBytes` widening (a single request larger than the steady-state capacity - see
[[0042-rate-limiting]]'s own real-bug writeup on why that's needed at all) is preserved
unchanged alongside the new multiplier.

### Frontend

A new row in the existing `rate-limit-settings` group (not a new group - this is exactly the
kind of addition [[0045-settings-page]]'s "grouping by topic means new rate-limiting fields
slot in here" was written for), a plain seconds input shared by both directions, placed above
the scheduled-window section since it applies to that window's limits too. No "Unlimited"-style
paired checkbox needed here, unlike the rate limits themselves - there's no meaningful
"unlimited burst" concept, just a plain numeric value with `0` documented inline as "use the
default."

## Testing

- `RateLimiterTest`: a regression guard proving an unconfigured burst still caps capacity at
  exactly one second's worth even after a much longer idle period (protecting today's default
  behavior from silently changing), and a companion test proving a configured 3-second burst
  actually lets that much accumulate and be spent close to instantly. Both are real-time tests
  (multi-second sleeps), matching this file's existing timing-assertion style rather than
  introducing a fake clock just for this feature.

## Alternatives considered

- **Separate burst values per direction** (upload/download) or **per window** (base/scheduled)
  - rejected; adds three more fields for a benefit no one asked for, when a single shared
    duration already scales correctly against whichever limit is active via the existing
    per-call resolution seam.
- **An absolute byte value instead of a duration** - rejected; a fixed byte number would need
  independent re-tuning any time the underlying rate limit itself changes, whereas a duration
  multiplier stays correct automatically.
