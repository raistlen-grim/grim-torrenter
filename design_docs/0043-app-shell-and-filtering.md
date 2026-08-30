# 0043 — App shell (header/sidebar/footer) and torrent filtering

**Status:** Accepted

## Decision

First structural change to the frontend beyond the single-screen dashboard scoped in
[[0020-frontend-torrent-dashboard]]: a persistent app shell (header, left sidebar, footer)
wrapping every route, plus status- and name-based filtering of the torrent list. Confirmed
with the user up front via an options round rather than assumed:

- The style guide's lexicon (`GrimTorrenter Style Guide.dc.html`) explicitly calls out
  **"Harvest"** as "the completed-downloads view — one nav label, used consistently" — used
  verbatim as the sidebar's completed-torrents filter label, not replaced with plain
  "Completed."
- **Disk free space** (shown in the guide's footer mock as "412 GB free") had no backing
  endpoint at all before this — added as a small new one (see below), in this same pass.
- The header's settings gear is a **stub route only** — `/settings` exists and is reachable,
  but the real rate-limit settings UI (PROGRESS.md's priority #1 next step) is deliberately
  left as its own separate follow-up, not folded in here.
- Extras built alongside the core layout: **sortable list columns**, a **per-row
  right-click context menu** (guide §06/§08), and a **global pause-all/resume-all** toolbar
  action.

### Layout: header / sidebar / footer (`frontend/src/app/shell/`)

Three new presentational-ish components, composed directly in `app.html` (no separate
"AppShell" wrapper component — `App` already is the shell's owner):

- **`AppHeader`** — sticky bar styled after the style guide's own document chrome
  (`var(--p-primary-900)` background, matching the guide's `--color-accent-900`, since the
  app has no `--color-accent-*` tokens of its own — only the PrimeNG semantic tokens
  [[0032-style-guide-and-primeng-theme]]'s preset actually produces). Shows the wordmark, an
  aggregate ↓/↑ rate (summed client-side from `TorrentEventsService.torrents()` — no backend
  change needed, same derivation `torrent-row.ts` already does per-row), a DHT status pill
  (new `DhtService`, polls the existing `GET /api/dht/status` every 5s while the header is
  mounted — which, being the header, is always), and a settings gear linking to `/settings`.
- **`AppSidebar`** — sticky left nav, one entry per `StatusFilter` (`All` / `Downloading` /
  `Seeding` / `Paused` / `Error` / `Harvest`), each showing a live count and behaving as a
  `routerLink="/"` that also selects that filter — so clicking a filter from the detail or
  settings view navigates back to the (now-filtered) list, matching how a persistent nav is
  expected to behave. A `Settings` link is pinned at the bottom, `routerLinkActive` for the
  current-page indicator.
- **`AppFooter`** — torrent count, aggregate ↓/↑ rate, upload ratio (all derived client-side,
  same aggregation as the header), and disk free space (new `SystemService`, polled every
  30s — free space changes slowly, no need for the header's 5s cadence). **Pinned to the
  bottom of the viewport** (`position: fixed`, not just the end of the document) rather than
  a sticky-footer flex trick — the user asked for it to stay visible on screen at all times,
  symmetric with the header's own `sticky top: 0`. Needs an explicit opaque background
  (`var(--p-content-background)`, Aura's own light/dark-aware "page content" token) since it
  now floats over scrolled content instead of sitting after it in normal flow;
  `.shell-main` gained matching bottom padding (`--shell-footer-height`, a hardcoded
  estimate alongside `--shell-header-height`) so real content never renders underneath it,
  and `AppSidebar`'s own sticky `max-height` was adjusted the same way so its last item
  doesn't end up hidden behind the footer at the bottom of a long scroll.

**Filter state is a new `TorrentFilterService`** (`signal<StatusFilter>` +
`signal<string>` for the name search), not synced to the URL/query params — this app has no
existing precedent for query-param-driven state, and the simpler in-memory-signal pattern
already used for `TorrentEventsService` was preferred over introducing one. Accepted
trade-off: the active filter/search resets on a page reload. Revisit if that proves
annoying in practice.

**`Harvest` filter semantics**: `completedPieces === totalPieces` (and `totalPieces > 0`),
not `progress >= 1` or `state === 'SEEDING'` — a paused-but-fully-downloaded torrent is
still "harvested" even though it isn't actively seeding, and piece counts avoid any
floating-point-equality question `progress` (a derived fraction) would raise.

**Toolbar (`torrent-list.html`) gains a name-search input**, composing with the sidebar's
status filter (both must match) — the sidebar answers "what kind of torrent," the toolbar
search answers "which one," and neither replaces the other.

**Page-level padding moved from each routed component into the shell's `<main>`** —
previously only `TorrentDetail` had its own `:host` padding (`TorrentList` had none at all,
relying on nothing); centralizing it in the shell avoids every future route needing to
remember to set its own. `TorrentDetail`'s `max-width: 960px; margin: 0 auto` (a reading-
width constraint, not a page gutter) stayed on its own `:host` at the time — **since
superseded**: [[0044-torrent-detail-drawer]] moved `TorrentDetail` off the app-level route
entirely (into a drawer owned by `TorrentList`), and removed that sizing altogether since
the drawer itself now constrains both width and padding.

### `GET /api/system/disk-usage` — new backend endpoint

Mirrors `DhtResource`'s shape exactly: a small, separate, global (not torrent-scoped)
resource. Reads `grimtorrenter.download-directory` directly via `@ConfigProperty` (the same
property `TorrentEngineProducer` already reads) rather than routing through `TorrentEngine`
— it's deploy-time config already safely readable from the app layer, so no new engine
accessor was needed.

**Calls `Files.createDirectories(directory)` itself before `Files.getFileStore(...)`**,
rather than assuming `TorrentEngine` has already created it. That assumption was tried
first and was wrong: `TorrentEngine` only ever creates `baseDownloadDirectory` as an
incidental side effect of `loadOrGenerateDhtNodeId` persisting a DHT node-id marker, which
(a) never runs at all when DHT is disabled — the seeded default for this project's own test
suite, and a legitimate production setting via [[0041-live-settings-store]] — and (b) even
when DHT is enabled, only creates the directory on the branch where no marker file exists
yet, with `IOException` swallowed to a warning log either way. **Caught by
`SystemResourceTest` itself**: it has no `TestSettingsResource`/`CleanDownloadsResource`, so
Quarkus boots it against a fresh `target/test-downloads` that nothing else had created yet,
and the endpoint failed with `NoSuchFileException` before this fix. `createDirectories` is
idempotent, so calling it on every request is harmless once the directory exists.

```java
public record DiskUsageView(long freeBytes) { }
```

Deliberately just the one field — the footer only ever shows "N free," not "N free of M,"
so a `totalBytes` field would be speculative until something actually displays it.

### `GET /api/system/resource-usage` — JVM heap/CPU in the footer

Added later in the same "small global `/api/system` resource" vein as `disk-usage` above:
the user wanted container memory/CPU visibility similar to what Spring Boot Actuator exposes,
without pulling in Actuator's Spring-specific tooling. `ResourceUsageView` mirrors
`DiskUsageView`'s shape — a plain record, no envelope:

```java
public record ResourceUsageView(long heapUsedBytes, long heapMaxBytes, double processCpuLoad,
        int availableProcessors) { }
```

**No Micrometer/SmallRye Health dependency.** Considered and rejected: this app has no
external scrape target (no Prometheus/Grafana deployment expected for a single self-hosted
container) and no existing `/health`-style consumer, so the standard Quarkus observability
extensions would add a dependency and a `/q/*` management surface for a single UI widget that
three JDK management-bean calls already answer. Uses
`com.sun.management.OperatingSystemMXBean` (ships in every mainstream JDK, not a new Maven
dependency, just not part of the strict Java SE platform API) rather than the plain
`java.lang.management.OperatingSystemMXBean`, which has no per-process CPU figure at all —
only a system load average. Both `availableProcessors()` and the CPU load figures are already
container-quota-aware on modern JDKs (active by default since JDK 10, refined further for
cgroup v2 in later releases), so the numbers reflect what the container is actually allotted,
not the host's full core count.

`processCpuLoad` is `-1.0` when the JVM can't determine it — passed straight through as the
JDK's own sentinel rather than inventing a new "unavailable" convention; the frontend renders
that as an em dash the same way `ratioDisplay`/`freeSpaceDisplay` already do for their own
no-data cases.

**Footer icons** (`pi-database` for free space, `pi-server` for heap, `pi-microchip` for CPU)
were added in the same pass after the user flagged the plain numbers as ambiguous once a
third stat joined free space — same `<i class="pi ...">` pattern the header's ↓/↑ rate spans
already used, so no new visual language introduced. `cpuDisplay()` dropped its `% CPU` text
suffix once the icon existed to label it (matching how the rate spans never spelled out
"download"/"upload" either); `freeSpaceDisplay()` kept its "free" suffix since the icon alone
doesn't distinguish free-vs-total space.

**Numbers only, not a graph.** The user asked directly whether the data supported a
time-series view: it doesn't without new work — the endpoint is a stateless live snapshot on
every call, nothing is retained server-side, so a graph would mean either a client-side
rolling buffer (bounded to the current tab session, lost on refresh) or a new backend
time-series store. Deferred as a separate future decision if wanted; the footer only needed a
glanceable current value, matching `disk-usage`'s own precedent.

**Stability** ([[0051-stability-as-a-standing-consideration]]): no unbounded growth — the
endpoint reads live MXBean state and returns it, nothing is buffered or persisted on either
side. Polled by the frontend on the same 30s cadence as `disk-usage` (`SYSTEM_POLL_INTERVAL_MS`,
renamed from `DISK_USAGE_POLL_INTERVAL_MS` now that it covers both calls), so no new unbounded
client-side history either — `toSignal` holds only the latest snapshot, same as `diskUsage`
already did. Not reachable by remote peers/trackers at all (pure host-JVM introspection, no
torrent-derived input), so no hostile-input surface. No locking/concurrency involved.

### Row selected highlight (`torrent-list.ts`/`torrent-row.ts`)

Picked from `TODO.md`: nothing in the list previously indicated which row the open detail
drawer ([[0044-torrent-detail-drawer]]) belonged to. The style guide already specs a "Selected
row" token, in full (`STYLE_GUIDE_NOTES.md`, not just `TODO.md`'s shorter background-only
paraphrase — see "First cut missed part of the token" below): `inset 2px 0 0 0
var(--color-accent)` left edge **plus** an 8% accent wash, never a fully filled row.
Originally spec'd for multi-select checkboxes — out of scope for the whole restyle pass — but
the same treatment applies naturally to "this row's infoHash matches the open
`torrents/:infoHash` route" in this app's simpler no-multi-select model, exactly as `TODO.md`
already anticipated.

`TorrentList` gained a `selectedInfoHash` signal, same `router.events` +
`NavigationEnd`-filtered pattern `isDetailOpen` already uses (read from
`route.firstChild?.snapshot.paramMap.get('infoHash')` rather than a separately-maintained
signal, so it can't drift from the drawer's actual open/closed state) — passed down to each
`TorrentRow` as a new `selected` input, applied via a `row-selected` host class alongside the
existing `row-pending` one. The CSS rule is declared after the existing `:host(:hover)` rule
so a selected row keeps both cues even while hovered, rather than the plain hover tint winning
on equal specificity.

**First cut missed part of the token.** The initial implementation applied only the 8% accent
wash, taken from `TODO.md`'s own shorter paraphrase of the guide rather than re-checking
`STYLE_GUIDE_NOTES.md` itself. Live, the user flagged that a partially-downloaded torrent's own
`.progress-underlay` (a very similar 11% accent wash, no edge) made a selected in-progress row
read ambiguously close to "this row is partway downloaded." The left-edge bar — present in the
full token all along — is what actually disambiguates "selected" from "in progress" at a
glance; adding it (matching the same "2px accent left edge, never a filled row" language
`app-sidebar.scss`'s own `.nav-item.active` already uses) resolved it. Consistent with this
project's own "verify deviations, don't assume" habit — worth re-checking the primary source
before treating a paraphrase as complete, even one written down in `TODO.md`.

### Sortable columns (`torrent-list.ts`)

Gains a new **Size** column (`totalLength`, via the existing `FormatBytesPipe`) between
Name and State, matching the guide's own §06 column order - the list previously had no
standalone size column at all (only the byte-progress text inside the Progress cell), so
"sortable by size" had no header to sort from without adding one.

**Not** PrimeNG's built-in `pSortableColumn`/`sortField` mechanism — `p-table`'s rows here
are a discriminated union (`TableRow`, pending-upload vs. real torrent, see
[[0029-optimistic-upload-feedback]]), and `Table`'s built-in sort resolves a flat field path
against the row object itself, which would either need every field path prefixed
`torrent.foo` (fragile, and pending rows have no `.torrent` to resolve against, so sorting
mid-upload would throw) or fighting the union apart before handing it to `p-table`. Instead:
a plain `sortField`/`sortDirection` signal pair in `TorrentList`, applied to the *torrent*
half of `rows()` only — pending uploads stay pinned at the top regardless of sort, same as
they already are regardless of filter. Clickable `<th>`s (not PrimeNG's sortable-column
directive) toggle direction on repeat-click and show a `pi-sort-*` icon, accent-colored on
the active column per the guide's own rule ("the sorted column header takes the accent").

### Right-click context menu (`torrent-row.ts`)

A `p-contextMenu` owned by each `TorrentRow`, opened via a `(contextmenu)` host listener on
the row's own `<tr>` — kept row-scoped (like the row's existing pause/resume/remove buttons)
rather than wired through `p-table`'s `[contextMenu]`/`contextMenuSelection` integration,
which pairs awkwardly with the same union-row problem sorting hit above.

**Trimmed from the guide's full §08 spec** (Pause, Open folder, Limit rate…, Add label…,
Copy magnet, Remove…) to only what's actually backed by something real today: Pause/Resume,
Copy magnet link (constructed client-side from `infoHash`/`name` — a magnet URI needs
nothing else), Remove, and Remove and delete files. **Open folder** (no filesystem access
from a browser, and nothing server-side exposes a browsable path), **Limit rate…** (only a
global rate limit exists yet, see [[0042-rate-limiting]] — no per-torrent override to set),
and **Add label…** (no label/tag concept exists anywhere in the backend) were left out
rather than shipped as dead menu items with nothing behind them — consistent with this
project's existing discipline around not building UI ahead of real backend capability (see
[[0031-torrent-detail-endpoints]]'s own Summary-endpoint reasoning). Revisit each if/when
its backing feature gets built.

Added *alongside* the existing inline pause/resume button and remove split-button, not as a
replacement — removing already-shipped, tested controls wasn't asked for; the context menu
is a power-user supplement, matching how the user scoped it ("a richer alternative to /
supplementing the inline buttons").

### Global pause-all/resume-all (`torrent-list.ts`)

Two toolbar buttons. No backend bulk endpoint exists (or was needed) — each just loops
`TorrentService.pause()`/`resume()` client-side over every currently-affected torrent
(`DOWNLOADING`/`SEEDING` for pause-all, `STOPPED` for resume-all; `VERIFYING`/`ERROR`
torrents are left alone, same as a single row's own pause/resume button being disabled
while `isVerifying()`), firing all requests concurrently rather than sequentially - same
"just call the existing per-item endpoint" shape [[0042-rate-limiting]]'s
`RateLimiters.unlimited()`-everywhere pattern already established for "no new bulk
primitive needed, existing per-item ones compose fine." A single summary toast reports how
many were affected. **No per-row pending-spinner feedback** the way a single row's own
button click gets ([[0033-per-entry-action-feedback]]) - `TorrentRow.pendingAction` is
private to each row instance with no external setter, and wiring bulk-triggered per-row
spinners through would have meant a real state-sharing change, not proportionate to what a
toolbar action needs; affected rows still visibly update via the next state-changed push or
2s snapshot, same as any other externally-triggered change already does today.

## Alternatives considered

- **`totalBytes` alongside `freeBytes` in `DiskUsageView`** — rejected per "no speculative
  fields," see above.
- **Filter state in the URL (query params)** — rejected for now; no existing precedent in
  this app, and the simpler service-signal pattern matches how every other piece of shared
  client state here already works.
- **PrimeNG's built-in table sort / context-menu integration** — rejected for both features,
  same root cause: the union-typed `TableRow` shape doesn't fit cleanly against APIs that
  expect a flat row object. See each section above.
- **Micrometer/SmallRye Health for resource usage** — rejected as disproportionate for a
  single-user, single-container app with no external scrape target; see the
  `resource-usage` section above.
- **A memory/CPU usage graph over time** — rejected for now, no backing time-series data
  exists on either side yet; see the `resource-usage` section above.
