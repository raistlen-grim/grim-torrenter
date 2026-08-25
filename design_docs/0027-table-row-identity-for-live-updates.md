# 0027 — p-table row identity for periodically-refreshed data

**Status:** Accepted

## Decision

Adding a `p-splitButton` dropdown to the torrent list (design_docs/0026)
surfaced a real bug: its overlay menu closed / became unclickable every
time the table refreshed, which happens every 2 seconds regardless of
user interaction (`TorrentSnapshotScheduler`, design_docs/0019).

**Root cause: `p-table`'s row identity defaults to the row object itself**
(`rowTrackBy = (index, item) => item`), which is a completely separate
mechanism from `dataKey` (that only drives selection/expansion state, not
`*ngFor` row identity). `TorrentEventsService.torrents` rebuilds every
torrent as a brand-new object on every signal recomputation - including
the periodic snapshot tick, even when nothing about that torrent actually
changed - via `{...t, ...rate}`. With no `rowTrackBy` set, Angular's
`*ngFor` sees a "new" object at every position on every tick and destroys
and recreates the entire `<tr>`, including every child component inside
it (the split button and its open overlay), rather than just pushing
updated inputs into the existing view.

**Fix: set `[rowTrackBy]` to key off `infoHash`.** A one-line
`trackByInfoHash(index, torrent) => torrent.infoHash` tells Angular the
row is "the same" across ticks regardless of object identity, so the
existing view (and every component instance inside it) survives a
refresh - only its bindings get updated in place. This is a general
pattern, not a one-off patch: **any `p-table` bound to data that refreshes
on a timer or push channel needs an explicit `rowTrackBy`** (or, more
generally, any Angular `*ngFor`/`@for` over such data needs a `track`
expression) - `dataKey` alone does not provide this, despite looking like
it should.

An earlier, narrower fix - memoizing the split button's `[model]` array
per `infoHash` in `TorrentList.removeMenuItems()` so that specific binding
didn't change reference every tick - was necessary but insufficient on its
own: it stopped that one input from looking different, but the whole row
was still being torn down independently of it. `rowTrackBy` fixed the
reported bug on its own; the row was then extracted into its own
component regardless (see below), which made the memoization moot.

## Follow-up: extracted `TorrentRow`, one computed signal per field

Raised by the user as a general hardening measure, not a fix for a still-
open bug: `rowTrackBy` stops a row's *view* from being destroyed, but
every binding inside a surviving row still gets re-evaluated on every 2s
tick, because `torrent()` itself is still a new object each time. Any
*future* stateful control added to a row (an inline rename field, for
example) could hit a subtler version of the same problem without another
one-off patch like the `removeMenuItems` memoization.

Fix: the row's content moved into its own `TorrentRow` component
(`torrent-list/torrent-row/`), taking the torrent as a signal input
(`torrent = input.required<TorrentWithRate>()`) and re-exposing every
displayed field as its own `computed()` (`name`, `state`, `progress`,
`bytesUploaded`, ...). A `computed()` only notifies its consumers when
*its own* value actually differs - default signal equality - even though
`torrent()` upstream is a new object every tick. So a row whose upload
rate ticks but whose state/progress are steady no longer touches the
bindings that depend only on state or progress. This achieves the same
goal as the "referentially-stable objects" alternative noted above,
without touching `TorrentEventsService` at all - the stability is
reconstructed locally, per field, from a value that keeps changing
identity upstream.

As a direct consequence, the `removeMenuItemsByHash` memoization Map is
gone: `TorrentRow` is already a component instance scoped to exactly one
torrent for its entire lifetime (as long as `rowTrackBy` keeps that row
alive), so its `removeMenuItems` is a plain `readonly` field built once,
with no cache/cleanup logic needed - the component instance itself is the
cache key.

**`TorrentRow`'s selector is `tr[app-torrent-row]`, an attribute selector,
not an element selector.** Angular's template compiler applies the same
HTML content-model rules as a browser parser: a custom *element* nested
inside `<tr>` (e.g. `<tr><app-torrent-row /></tr>`) gets fostered out of
the table structure at compile time, same as the browser would do for any
element `<tr>` isn't allowed to contain. Decorating the `<tr>` itself via
an attribute selector (`<tr app-torrent-row [torrent]="torrent">`) avoids
this entirely - the component's template (a plain sequence of `<td>`s)
becomes that real `<tr>`'s own children, so the table keeps genuine
`<td>`/`<th>` semantics for screen readers, which a single wrapping cell
per row (an earlier shape considered for this) would have lost - relevant
given the frontend's WCAG AA / AXE requirements.

## Alternatives considered

- **Make `TorrentEventsService.torrents` return referentially-stable
  per-torrent objects** (reuse the same object when no field actually
  changed) - superseded by the per-field computed signals in `TorrentRow`
  above, which get the same effect without touching the update pipeline.
- **One `<td>` per row wrapping the whole `TorrentRow` component's
  content** - rejected: collapses a `<tr>`'s columns into a single cell,
  losing per-column table semantics that assistive technology relies on.
