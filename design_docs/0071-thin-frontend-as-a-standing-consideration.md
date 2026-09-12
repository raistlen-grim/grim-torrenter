# 0071 — Thin frontend as a standing consideration for every decision

**Status:** Accepted

## Decision

Stated directly by the user (2026-09-11) while scoping [[0070-pending-magnet-as-first-class-torrent]]:
**the frontend should stay as thin as possible - a different client built against the REST API
alone should have to recreate as little UI-side logic as possible.** Not a one-off preference
for that feature; a standing consideration for every decision recorded in `design_docs/` from
here on, the same way [[0051-stability-as-a-standing-consideration]] already is.

The concrete case that surfaced it: a narrower fix for "a pending magnet disappears on page
refresh" would have kept an entirely frontend-only concept alive (a client-side placeholder row,
resolved by watching for a matching torrent or failure event to show up) - solving the symptom
without questioning why the frontend needed bespoke logic to track something the backend itself
had no durable opinion about. The chosen design instead made a pending magnet a state
(`FETCHING_METADATA`) on the exact same resource shape a real torrent already is - restart-
durable, REST-visible, removable through the one generic delete endpoint - and the frontend
lost an entire mechanism (`pendingInfoHashEffect` and friends) as a direct result, rather than
gaining one.

**What this means concretely, when weighing a design that touches both layers:**

- Prefer modeling new behavior as a *state* or *field* on an existing, already-generic resource
  shape over inventing a parallel concept the frontend has to know how to merge, correlate, or
  resolve on its own. [[0070]]'s `TorrentView.fromPendingMagnet()` riding the exact same DTO
  `TorrentView.from()` produces is the template: one shape, one set of generic list/get/delete
  operations, a new state value where the closed-enum-like `state` field already left room for
  one (see [[0031-torrent-detail-endpoints]]'s own precedent of `state` being a plain
  `String` specifically to leave that room).
- Prefer a synchronous REST response carrying the real, current resource over an
  acknowledgement the client has to poll or wait on and reconstruct state around
  (`POST .../magnet` returning the pending `TorrentView` immediately, not a bare 202).
- When frontend-only bookkeeping already exists for something (a placeholder row, a
  reconciliation effect watching two data sources to resolve a local guess), treat that as a
  signal worth checking against this principle before extending it further - it may be
  covering for state the backend should just be modeling and exposing directly instead.
- This doesn't mean *no* client-side logic ever - input validation/preview
  (`resolveAddState()`'s magnet-parsing, kept as-is in [[0070]] since it's genuinely about
  guiding what the user is about to submit, not about tracking backend state) and per-viewer
  ergonomics (remembered UI preferences, local sort/filter state) are exactly the kind of thing
  that belongs client-side and this doesn't argue against. The target is specifically logic that
  exists to *stand in for* durable backend state or to *reconcile* multiple data sources the
  backend could have already reconciled itself.

## Why recorded here rather than folded into `CLAUDE.md` prose

Same reasoning [[0051]] already gave for itself: this is a pattern decision about how decisions
get made, which per `CLAUDE.md`'s own convention gets a file rather than being silently
absorbed into prose. `CLAUDE.md` points here rather than restating the rationale.

## Testing

N/A — process/convention decision, same as [[0051]]. Its only real "test" is whether a future
design doc touching both the engine/app and frontend layers actually shows this was considered,
enforced by habit and review, not tooling.

## Alternatives considered

- **Treat the [[0070]] redesign as a one-off judgment call, not a standing principle** -
  rejected (user decision): the user was explicit that this should inform future decisions, not
  just this one.
- **Fold this into [[0051]] itself** (both are "standing considerations for every decision") -
  rejected; they're orthogonal axes (resource/failure safety vs. where logic lives/how thin the
  client is) that will sometimes pull in different directions on the same decision, and
  conflating them would make either harder to check for cleanly on its own.
