# 0020 — Frontend torrent dashboard

**Status:** Accepted, API usage confirmed against installed PrimeNG 21.1.9

## Decision

Single dashboard view (`TorrentList`, lazy-loaded at `/`) — Phase 1 has
exactly one screen's worth of functionality (upload, list, progress,
peer count, pause/resume/remove), so no further routing structure is
built yet.

**`TorrentEventsService` is the single source of truth for torrent state**
on the client, backed by a `signal<Map<string, Torrent>>` (map keyed by
info hash, for O(1) upsert/remove) with a `computed()` array view for
templates. It seeds itself via one REST `list()` call, then stays current
via the backend's hybrid push model from [[0019-rest-and-websocket-layer]]
(periodic snapshot replaces the whole map; `state-changed` events upsert a
single entry). The WebSocket auto-reconnects on close (3s delay) — a
missed reconnect window self-heals via the next periodic snapshot anyway,
so this doesn't need to be more sophisticated than a flat retry.

**`removeLocal(infoHash)` is a client-side-only workaround**: the backend
has no "torrent removed" push event (`TorrentEngine.removeTorrent` doesn't
fire anything through `TorrentSessionListener`), so without this, a
deleted torrent would linger in the UI for up to the ~2s snapshot interval
after the DELETE call succeeds. The component calls it directly after a
successful delete rather than waiting for backend confirmation via push.

**Dev-server proxy** (`proxy.conf.json`, wired into `angular.json`'s
`serve.options`): forwards `/api` and `/ws` to `localhost:8080` (Quarkus's
default dev port) so `ng serve` and `quarkus:dev` can run side by side
without CORS configuration. Production doesn't need this at all — the
Dockerfile bakes the Angular build into Quarkus's own static resources
per [[0010-frontend-integration-and-docker-build]], so it's same-origin
there by construction.

**Styling is deliberately minimal** (PrimeNG defaults, no custom theme
work) — the user has a reference app for the intended look and feel;
visual design is a follow-up pass once that's shared, not part of this
slice.

## Verification history

After `ng add primeng` installed PrimeNG 21.1.9, the API guesses flagged
here were checked directly against the package's shipped type
declarations and compiled ESM source in `node_modules/primeng`
(`types/*.d.ts`, `fesm2022/*.mjs`) - reading a package's own shipped
source/type-declaration files is a normal way to verify a TypeScript
library's public API, unlike digging into compiled Java bytecode, which
[[0001-backend-language-and-framework]]'s working conventions are about.
All confirmed correct, nothing needed changing:

- `<ng-template pTemplate="header"|"body"|"emptymessage">` - `Table`'s
  `_headerTemplate`/`_bodyTemplate`/`_emptyMessageTemplate` content
  queries match on exactly these `PrimeTemplate` predicate strings.
- Selectors `p-table`, `p-fileupload, p-fileUpload`,
  `p-progressBar, p-progressbar, p-progress-bar`, `p-button`, `p-tag`,
  `p-toast` - all present as declared.
- `FileUpload` class, `customUpload`/`auto` inputs, `uploadHandler`
  output, and the `FileUploadHandlerEvent` interface - all present as
  imported/used in `torrent-list.ts`.
- `ariaLabel` input on `p-button` - present.

Confirmed working end-to-end against the real Quarkus backend and a real
torrent (Ubuntu ISO) after two real bugs found via manual testing:

- **Real bug: `progressPercent()` rounded small nonzero progress down to
  exactly 0**, and `p-progressBar` explicitly hides its entire value
  display when `value === 0`
  (`[style.display]="value != null && value !== 0 ? 'flex' : 'none'"`,
  confirmed by reading `node_modules/primeng/fesm2022/primeng-progressbar.mjs`)
  and renders a literal 0-width bar. For a multi-GB file, there's a real
  window where actual progress is happening (visible in the raw WS
  payload) but displays as a hard, hidden zero - indistinguishable from
  "hasn't started." Looked exactly like a reactivity/change-detection bug
  at first (peer count *was* updating correctly, via plain interpolation,
  while progress specifically wasn't) before being traced to this
  PrimeNG-specific zero-value behavior combined with naive rounding.
  Fixed: `progressPercent()` now rounds any genuinely nonzero progress up
  to at least 1, per its own doc comment.
- **Dev-server proxy bug**: `proxy.conf.json`'s `/ws` entry originally set
  `"target": "ws://localhost:8080"`. Angular's dev-server proxy expects an
  `http://` target with `"ws": true` to trigger the protocol upgrade - a
  raw `ws://` target doesn't work. Fixed by changing the target scheme.
- Diagnosing both took the same approach as the backend tracker issue in
  [[0021-engine-logging-and-error-visibility]]/[[0022-multi-tracker-fallback]]:
  add visibility (temporary `console.debug` logging in
  `TorrentEventsService`, and permanent connection-lifecycle logging in
  `TorrentWebSocket` at INFO, broadcast activity at DEBUG) rather than
  guessing at fixes blindly. The temporary frontend debug logging was
  removed once the cause was confirmed; the backend logging was kept as
  ongoing operational visibility.

**`ng add primeng`'s schematic left the install incomplete** - it added
the base `primeng` package to `package.json` but did neither of the two
other things a working PrimeNG setup needs:
- **No theme configured at all**: no `@primeng/themes` dependency, no
  `providePrimeNG()` call anywhere. Without a theme, components render
  with no design tokens (colors, etc.) applied - e.g. a progress bar's
  fill div exists at the right width but has no background color, so it's
  invisible regardless of the value-rounding fix above. Fixed by manually
  installing `@primeng/themes` and adding
  `providePrimeNG({ theme: { preset: Aura } })` to `app.config.ts` -
  verified against `node_modules/primeng/types/primeng-config.d.ts` and
  `node_modules/@primeng/themes`'s actual exports rather than guessed.
- **No icon font**: `primeicons` (the package providing the `pi pi-*`
  icon classes used on the pause/resume/remove buttons) wasn't installed
  either, so those buttons would have rendered with no visible icon at
  all. Fixed by installing `primeicons` and adding
  `@import "primeicons/primeicons.css";` to `styles.scss`.

Both gaps were silent - no build error, no console warning, nothing to
indicate the install was incomplete. Worth remembering that `ng add`
schematics aren't guaranteed to fully wire up a library even when they
report success.

**Whole-percent progress is too coarse for large files at slow speeds.**
Observed directly: a 6.5GB torrent with only 0-1 connected peers (no
DHT/UDP tracker support yet, see [[0009-phased-scope]] - the swarm this
torrent's HTTP-only fallback tracker can actually reach is much smaller
than what a full client would find) moved from 5.2MB to 11.5MB downloaded
over several minutes, but both `progress` values rounded to the same
whole percent - the `Math.max(1, ...)` fix from the fix above was
correctly *not zero*, but displaying the identical "1%" twice in a row
still looks frozen. `torrent-list.html` now shows byte counts
(`formatBytes`, e.g. "11.5 MB / 6.1 GB") alongside the percentage, so real
movement is visible even when the rounded percent doesn't change. The low
peer count itself isn't a bug to chase further - it's an expected
consequence of Phase 1's HTTP-only tracker support against a torrent
whose swarm is predominantly reachable via UDP trackers/DHT.

**Download rate is computed client-side**, not part of the backend
`TorrentView` DTO. `TorrentEventsService` keeps a `Map<infoHash, Reading>`
(last known `bytesDownloaded` + timestamp) alongside the main torrent map,
and derives bytes/sec from the delta between successive readings whenever
a snapshot or state-changed event arrives - no backend change needed,
since the ~2s snapshot cadence already provides enough data points. This
is exposed as `TorrentWithRate` (`Torrent` plus `downloadRateBytesPerSec`),
a client-only type that `TorrentEventsService.torrents` returns instead of
plain `Torrent`. Chosen over computing this server-side in `TorrentSession`
specifically to avoid another engine-module change (and the
rebuild/restart cycle that comes with it) for something the client already
has enough information to derive on its own. The rate is clamped to
non-negative - `bytesDownloaded` only ever increases (a verified-complete
piece is never uncompleted in `PieceManager`), so a negative delta
shouldn't occur, but nothing currently prevents clock irregularities from
producing one.

## Alternatives considered

- **Per-torrent detail route** - not built; Phase 1's dashboard already
  shows everything the MVP scope calls for (list, progress, peers) without
  needing drill-down.
- **Waiting for the next snapshot to reflect a delete** instead of
  `removeLocal` - rejected as a worse user experience (a visible stale row
  for up to ~2s) for a one-line fix.
