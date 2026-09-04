# 0059 — Service status (DHT / peer server health in the UI)

**Status:** Accepted

## Decision

A **Services** section (new sidebar page + nav badge) surfaces whether the engine's two
singleton subsystems — DHT and the inbound peer server — are running, disabled, or failed.
Picked up from `TODO.md`'s "Backend health/degraded-state surfaced in the UI" item: today,
both `createDhtNode()`/`createPeerServer()` catch bind failures (e.g. `BindException`, see
[[0058-socket-reuseaddr-on-rebind]]'s own investigation of this exact failure mode) and just
log a `WARNING` — the engine silently continues degraded, with no way for a user to know short
of reading server logs.

**Scoped to engine-wide singleton subsystems only** (confirmed with the user up front) — not
per-torrent status, which already lives on the torrent itself (row/detail state, tracker
status). DHT and the peer server are the only two subsystems that fail in this well-defined,
one-time way today; the watch folder's differently-shaped failure mode (directory
create/scan IO errors, already logged separately) was deliberately left out of this pass.

## One set of triggers, two outputs

The same bind-failure catch block now does two things: records a normal `LibraryEvent`
(`DHT_UNAVAILABLE`/`PEER_SERVER_UNAVAILABLE`, engine-wide like `SERVER_STARTED` -
`infoHash`/`torrentName` both null) so the failure shows up with a timestamp in the existing
Events tab ([[0055-library-events]]), and leaves the engine able to report live current state
via a new `TorrentEngine.serviceStatuses()`.

**Current state is tracked directly, not derived from replaying the event log.** DHT/peer
server only ever bind once, at construction — there's no periodic re-check, no retry, and (for
now) no way to recover without a process restart. That makes "current state" trivial to track:
two new `final boolean` fields (`dhtBindFailed`/`peerServerBindFailed`), set once at
construction from whether the subsystem was *requested* (`enableDht`/
`acceptIncomingConnections`) but the corresponding nullable field (`dhtNode`/`peerServer`)
ended up null. No new begin/end event-type pairing was needed (unlike the deferred
tracker-unreachable/recovered idea from [[0055-library-events]]'s own "Deferred from this
pass" section, which is inherently a live, repeatable, per-torrent-per-tracker condition, not
a one-time-at-startup one) — deriving "currently failed" from the log would have meant
inventing that same kind of pairing for no reason, when the existing nullable-field pattern
already answers it directly.

```java
public enum ServiceState { RUNNING, DISABLED, FAILED }
public record ServiceStatus(String name, ServiceState state) { }
public List<ServiceStatus> serviceStatuses() { ... }
```

`"dht"`/`"peerServer"` are stable string identifiers, matched by name against a frontend
display map (`shared/status-display.ts`'s `SERVICE_DISPLAY`) - the same closed-set-mapped-by-
key shape `EventType` already uses for the Events page, rather than inventing a second
enum-like contract.

## Backend surface

`GET /api/system/services` (new `SystemResource` method, alongside `disk-usage`/
`resource-usage`) returns `ServiceStatusView[]` — `{ name, state }`, `state` serialized as the
enum name. Mirrors `DhtStatusView`'s own "wrap the engine record, don't expose it directly"
shape.

## Frontend

- **Sidebar**: a new "Services" nav item (between the status filters and Events, using the
  `below-filters` separator class that item used to sit on), polling
  `SystemService.services()` on the same 30s cadence `AppFooter`'s system stats already use —
  no shared "live polled state" service exists in this codebase, every consumer polls
  independently (`AppHeader`'s DHT pill, `AppFooter`'s disk/resource stats), so this follows
  that established convention. Badge shows the live failed-service count, **hidden when
  zero** (confirmed with the user) rather than always-rendered like the status-filter counts
  above it — a bare "0" next to a health indicator reads like an alert even when nothing's
  wrong.
- **New `/services` page** (`ServicesPage`) — a fixed checklist, one row per known service via
  the existing `StatusIndicator` component (no new visual component needed). Deliberately a
  checklist, not a freeform issue feed: a fixed, named set of rows is self-documenting about
  what's actually being watched, and an all-`RUNNING` list already reads as "nothing's wrong"
  without needing separate empty-state copy.
- **Events page**: `DHT_UNAVAILABLE`/`PEER_SERVER_UNAVAILABLE` added to the closed `EventType`
  union and `EVENT_TYPE_DISPLAY` map (`tone: 'alarm'`, same icons as the Services page's own
  entries for the same condition) - TypeScript's `Record<EventType, StatusDisplay>` forces
  this update, the same exhaustiveness check that already applies to every other event type.

## Addendum: explicit healthy checkmarks (added after initial ship)

Once live, the user asked for a clearer positive "all good" signal, in two places - the
all-`RUNNING` sidebar nav item read as merely "no badge," and each individual `RUNNING` row on
the Services page relied on `StatusIndicator`'s ink-weight alone (full-strength accent color)
to read as healthy, with nothing calling that out explicitly the way `FAILED`'s alarm-colored
icon does.

**Raised and confirmed with the user first**: this app's style guide deliberately avoids a
red/green severity palette - status is carried by ink weight/opacity plus one reserved alarm
color, not a traffic-light system ([[0032-style-guide-and-primeng-theme]],
[[0034-ink-weight-status-display]], "one hue, one alarm, not a five-tag rainbow"). Rather than
introduce a literal green as a second reserved status color, the fix reuses the same tone the
rest of the app already uses for "healthy" - `StatusIndicator`'s `active` tone, i.e. the app's
own accent color (`var(--p-primary-color)`) at full strength, not a hardcoded green.

- **Sidebar nav item** (`app-sidebar.html`/`.scss`): a `pi-check-circle` icon in the accent
  color renders next to "Services" once `failedServiceCount() === 0` **and** the first poll
  has actually resolved (`services().length > 0`) - guarded on the latter so the badge area
  doesn't flash a false all-clear before any data has loaded, since an empty array is also
  this signal's own `[]` `initialValue`. The failed-count badge itself also picked up an
  explicit alarm color (`.nav-count-alarm`, `var(--alarm)`) in the same pass, for symmetry -
  it previously rendered in the same plain, uncolored style as the status-filter counts above
  it.
- **Services page** (`services-page.html`/`.scss`): each `RUNNING` row gets its own trailing
  `pi-check-circle`, accent-colored, right-aligned via `margin-left: auto` on a now-flex
  `.service-card`. `DISABLED`/`FAILED` rows are unchanged - still just `StatusIndicator`'s
  existing dim/alarm treatment, no added icon.

Both reuse the same token (`var(--p-primary-color)`) rather than each inventing its own
"healthy" color, keeping the two checkmarks visually identical.

## Stability ([[0051-stability-as-a-standing-consideration]])

No unbounded growth — two new `boolean` fields, one small fixed-size list built on demand per
request. The new event types are recorded at most once per process lifetime per subsystem
(construction runs exactly once), so no flood risk from a misbehaving remote peer or a
crash-loop — and neither subsystem is reachable by remote peers/trackers in the first place;
this is pure local bind-failure detection. No locking: both new fields are `final`, set once
at construction, read-only for the rest of the process. No cleanup path needed - nothing here
allocates a resource that needs releasing.

## Known gap

No cheap, deterministic way exists today to force a real DHT/peer-server bind failure in a
unit test (would mean pre-binding the same ephemeral port from the test itself, racy by
nature), so the `FAILED` branch of `serviceStatuses()` and the new event recording it triggers
have no automated coverage — `TorrentEngineTest` only covers `RUNNING`/`DEGRADED`/`DISABLED`
(see this doc's own 2026-09-01 addendum for the `DEGRADED` coverage). Same shape
as the already-noted `TorrentEventListener` `ERROR`-mapping gap in `PROGRESS.md`. Worth a
follow-up if a reliable way to force a bind conflict in-test is found.

## Addendum: DEGRADED state for a sparse DHT routing table (2026-09-01)

Picked up from `TODO.md`'s own follow-on item: `serviceStatuses()` reported `RUNNING` for DHT
as soon as `dhtNode != null` (construction succeeded), with no distinction from "bootstrapped
but the routing table has stayed sparse" - a real, twice-observed condition (see `TODO.md`'s
own DHT-sparseness item, now otherwise closed by [[0028-magnet-links-and-dht]]'s 2026-08-30
routing-table-health and persistence addenda). Those fixes mean a low node count is no longer
expected to persist *indefinitely*, but the status endpoint still couldn't say "still filling
in" - this addendum closes that gap.

**`DhtNode` gained one new method, `isDegraded()`**, reusing the exact same
`MIN_HEALTHY_NODE_COUNT` threshold (`RoutingTable.BUCKET_SIZE`, 8) `refreshRoutingTable()`
already uses to decide "sparse enough to retry full bootstrap rather than a narrow bucket
refresh" - one threshold, not two independently-tunable ones meaning almost the same thing.
`ServiceState` gained `DEGRADED`, DHT-only (the peer server has no equivalent notion, only
bound-or-not); `TorrentEngine.serviceStatuses()`'s DHT branch now checks `isDegraded()` on
every call rather than returning a value fixed at construction.

**This is a live, self-healing signal, not a new failure mode** - confirmed with the user
before building, three decisions:

- **Doesn't count toward the Services nav badge's failed-service count.** DEGRADED isn't a
  problem needing attention the way FAILED is (DHT is running and actively retrying/refreshing
  on its own); only true `FAILED` bumps the badge, unchanged from before this addendum. Also
  means the nav item's own all-clear checkmark (`failedServiceCount() === 0`) is untouched by a
  degraded DHT - deliberately scoped down to the Services page only.
- **Flat threshold, no startup grace period.** `isDegraded()` is checked live on every poll
  with no "has this been running a while" gate - simplest, and accurate rather than
  misleading: a freshly-started DHT node genuinely *is* sparse until bootstrap/refresh catches
  up, and the Services page is only ever viewed on demand (a 30s poll while the page is open),
  not something a grace period would meaningfully smooth over.
- **`GET /api/dht/status` (the header pill) stays unchanged** - still just `{enabled,
  nodeCount}`, no qualitative signal added. Keeps 0059's original separation: the
  always-visible summary pill shows the raw number, the Services page is where a number gets
  interpreted qualitatively. A user watching the pill's raw count already sees exactly what
  "sparse" means numerically.

**Frontend**: `ServiceState` widened to include `'DEGRADED'`. Reuses the existing `'dim'` tone
rather than adding a fourth `StatusTone` - also confirmed with the user, staying inside the
style guide's deliberate 3-tone cap ("one hue, one alarm, not a five-tag rainbow",
[[0032-style-guide-and-primeng-theme]]/[[0034-ink-weight-status-display]]). Reusing `'dim'`
alone would read identically to `DISABLED` though (both currently render as ink-weight-only
with no other marker), so `services-page.html` adds its own explicit "Sparse routing table"
text next to a `DEGRADED` row - the same "don't rely on tone alone" reasoning this doc's own
"explicit healthy checkmarks" addendum already established for `RUNNING`.

**Testing**: `DhtNodeTest` covers `isDegraded()` directly (below/at threshold, seeding the
routing table with `insert()` rather than real bootstrap). `TorrentEngineTest` covers both
`serviceStatuses()` branches - a freshly-constructed engine (empty routing table) reports
`DEGRADED`, not `RUNNING`, right after enabling (a real, if initially surprising, consequence
of "no grace period"); a second test seeds the real `DhtNode`'s routing table (via the
existing package-private `dhtNode()` test accessor) past the threshold and confirms `RUNNING`.

**Stability** ([[0051-stability-as-a-standing-consideration]]): no new state, storage, or
locking - `isDegraded()` is a plain computed comparison against `RoutingTable.size()` (already
maintained), evaluated fresh on each `serviceStatuses()` call. No unbounded growth, no new
resource, no cleanup path needed.

## Alternatives considered

- **Deriving "current issues" by replaying the event log** — rejected; see "One set of
  triggers, two outputs" above. Would need invented begin/end event pairs for every failure
  type with no natural "recovered" counterpart yet.
- **A freeform "Issues" feed instead of a fixed services checklist** — the option originally
  discussed; the user preferred the checklist shape once services (a small, fixed, named set)
  turned out to be the actual failure shape in play today. Revisit if a condition that doesn't
  map to one named service (e.g. the deferred per-torrent tracker-unreachable idea) is ever
  built — that one doesn't fit a single "Trackers: red" row without losing which torrent/
  tracker, so it may need a different treatment when it's picked up.
- **Including the watch folder in v1** — rejected per the user's own scoping; its failure mode
  (directory create/scan IO errors) is shaped differently from a one-time bind failure and
  stays log-only for now.
