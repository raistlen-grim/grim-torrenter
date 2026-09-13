# 0073 — Tracker and peer-source details dialog

**Status:** Accepted

## Decision

Picked from `PROGRESS.md`'s "Suggested next steps"/`TODO.md`'s "UI idea, still open"
(2026-09-06): qBittorrent's Trackers tab lists DHT/PeX/LSD as rows alongside real trackers, each
with its own peer/seed/leech counts, and shows every tracker's own live seeders/leechers/peers.
Investigation found **both gaps are pure display gaps - zero backend or REST changes needed**:

- `TrackerView`/the frontend `Tracker` model already carry `seeders`/`leechers`/`peers` for
  every tracker ([[0031-torrent-detail-endpoints]]/[[0067-per-peer-progress-and-tracker-metrics]]),
  and `trackers-tab.ts` already polls the *full* list every 3s - `notWorkingTrackers()` just
  filters it client-side for display. A working tracker's own numbers were already sitting in
  memory, just never shown (only a non-working tracker gets a tooltip with them).
- Per-peer `source` ([[0066-peer-diagnostics]]) and `percentAvailable`
  ([[0067-per-peer-progress-and-tracker-metrics]]) already exist on `Peer`/`PeerView`, already
  fully delivered by the existing `/peers` endpoint the Peers tab already uses.

## Scope, confirmed with the user across several rounds

- **A dialog, not a full page** - consistent with this app's two existing per-torrent secondary
  dialogs (seeding-limits, torrent-limits, both [[0054-seeding-limits]]/[[0072-per-torrent-limits]])
  and the drawer's own "don't add a navigation layer for secondary info" precedent
  ([[0044-torrent-detail-drawer]]).
- **The main Trackers tab stays exactly as simple as it was** - the collapsed "N trackers
  working" summary line, non-working trackers listed individually, and the DHT/PeX/LSD
  Enabled/Disabled line are all unchanged. Both richer datasets (the full per-tracker table, the
  peer-source breakdown) live only in the new dialog, opened by one small trigger - not added to
  the main view, which is a deliberate style-guide decision ("a list nobody reads" - see
  `trackers-tab.ts`'s own class Javadoc) this feature doesn't reopen.
- **Zero new backend/REST work.** The peer-source breakdown is computed entirely client-side
  from the already-existing `/peers` response - the same "count already-fetched data for a
  display value" pattern `trackers-tab.ts` already uses for its own `workingCount()`. This is
  the [[0071-thin-frontend-as-a-standing-consideration]]-aligned choice specifically *because*
  the data is on-demand/dialog-only: unlike `usesDht`/`usesLsd` (which ride the always-broadcast
  `TorrentView` because the main tab view needs them too, not just this dialog), nothing outside
  this dialog needs a peer-source count, so there's no case for a new always-computed backend
  field feeding a client that only sometimes looks at it.
- **The dialog keeps live-refreshing while open, not a static snapshot.**

## `TrackerDetailsDialog` (new component)

`frontend/src/app/torrent-detail/trackers-tab/tracker-details-dialog/` - same
`input.required`/`output` shape as `SeedingLimitsDialog`/`TorrentLimitsDialog`
(`infoHash`/`visible`/`visibleChange`). **Revised same-day, see the addendum below: it does
not also take a `trackers` input** - it fetches both halves itself.

**Both halves need a visibility-gated poll.** `pollWhileInput` (`shared/poll-while-input.ts`) is
keyed to a `Signal<string>`, restarting whenever that string changes - it has no way to gate on
a plain boolean ("only poll while this dialog is open"). Rather than generalize that shared
helper for what's so far a single component's need, `tracker-details-dialog.ts` builds its own
small `pollWhileVisible()` helper directly:

```ts
function pollWhileVisible<T>(visible: Signal<boolean>, fetch: () => Observable<T>, initialValue: T): Signal<T> {
  return toSignal(
    toObservable(visible).pipe(
      switchMap((isVisible) => (isVisible ? interval(POLL_INTERVAL_MS).pipe(startWith(0), switchMap(fetch)) : EMPTY)),
    ),
    { initialValue },
  );
}
```

Same 3000ms cadence as every other polling tab. Worth promoting into `poll-while-input.ts` as a
sibling export if a third consumer ever needs the same shape - not done speculatively here for
one file's two internal call sites.

**A new "counts as seeding" convention.** Nothing in this frontend previously defined when a
peer "counts as seeding" (checked `peers-tab.ts` and `torrent.model.ts` - `percentAvailable` is
only ever displayed as a raw percentage today). This dialog introduces one:
`percentAvailable >= 1` - a peer with the complete torrent is a seed by BitTorrent's own
definition; `>=` rather than `===` only as defensive habit against a value landing exactly on
the boundary from the wrong side, not because `percentAvailable` can exceed 1 in practice.

**Peer-source rows are fixed and always all shown**, even at zero - Tracker/DHT/PeX/LSD/"Other
(incoming)" (the last relabeling `PeerSource.UNKNOWN`, an internal engine term meaning "an
incoming connection we didn't independently discover," not a source a user configured) - same
"always show, don't hide a zero" predictability the main tab's own DHT/PeX/LSD line already has.
Each row shows a connected count and, within it, a seeding count - deliberately not framed as
"seeders"/"leechers" the way a tracker row is: DHT/PeX/LSD have no swarm-wide total the way a
tracker's own announce response does, only "of the peers we're currently connected to, how many
came from this source and how many of those are seeding" - a different, narrower question a
misleading "seeders: N" label would conflate with a tracker's real swarm-size report.

## Trigger

No existing small "Details…"-style inline link anywhere in this app (checked `services-page`,
`events-page`, `torrent-row`) - the closest precedent is `torrent-row.ts`'s
menu-item-opens-dialog pattern, which doesn't fit here (this tab has no context menu of its
own). A plain `<button>` reset styled as a link (`.tracker-details-trigger`), placed at the top
of the tab - deliberately not a `p-button`, to avoid pulling in `ButtonModule` for what is,
visually, just underlined caption text with a click handler.

## Same-day addendum (2026-09-12): opened from both Peers and Trackers, dialog made self-sufficient

Raised by the user directly: the trigger should also appear on the Peers tab, not just Trackers
- and at the top of each tab, not after the content. Two follow-on decisions, both confirmed
with the user:

- **One combined dialog, not two** (a "trackers" dialog and a separate "peer sources" dialog).
  The peer-source breakdown already treats "Tracker" as one of its own five rows, so the two
  datasets are inherently related, not separate concerns - splitting them would mean either
  duplicating tracker info across two dialogs or arbitrarily excluding "Tracker" from the
  peer-source breakdown depending on entry point. This also mirrors qBittorrent's own layout,
  the original inspiration for this feature: DHT/PeX/LSD shown *inside* its trackers view, not
  as a separate screen.
- **`TrackerDetailsDialog` became fully self-sufficient** to support this - it now fetches
  *both* trackers and peers itself (each via its own `pollWhileVisible()` call), rather than
  depending on `TrackersTab` to supply a live `trackers` input. This removes the asymmetry the
  original version above described (tracker half fed by a parent input, peer half self-fetched) -
  both halves now work identically regardless of which tab hosts the dialog. `PeersTab` hosts an
  identical `<app-tracker-details-dialog [infoHash] [visible] (visibleChange)>` with its own
  `showTrackerDetailsDialog` toggle signal, same self-contained-per-host pattern
  `TrackersTab`/`torrent-row`'s existing dialogs already use. The `.tracker-details-trigger`
  style is duplicated into `peers-tab.scss` (component styles are scoped per-component in this
  app, so there's no existing shared place to put it) - worth promoting to a shared class if a
  third tab ever needs the same trigger.

## Stability ([[0051-stability-as-a-standing-consideration]])

No new resource-behavior implications - this is read-only display logic. The new poll only runs
while the dialog is actually open (gated on `visible`), so it costs nothing when closed, unlike
`trackers-tab.ts`'s own poll (which runs for as long as the Trackers tab panel is mounted,
regardless of this dialog).

## Alternatives considered

- **A full page instead of a dialog** - rejected; see Scope above.
- **Listing every tracker as its own row on the main tab** (reverting the collapse) - rejected;
  the whole point of this feature is exposing the extra detail without undoing the style guide's
  deliberate "a list nobody reads" collapse.
- **A new backend field/endpoint for peer-source counts** - rejected in favor of client-side
  aggregation; see the thin-frontend reasoning in Scope above.
- **Framing DHT/PeX/LSD rows as "seeders"/"leechers" like a tracker row** - rejected; see the
  peer-source-rows note above on why that would misleadingly imply a swarm-wide total these
  sources don't actually have.
