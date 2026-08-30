# 0060 — Magnet-add failure feedback (transient pending row + MAGNET_ADD_FAILED)

**Status:** Accepted

## Decision

Debugging a user-reported "adding a magnet via the toolbar does nothing" traced to a real
network problem on their machine (outbound TCP connects to real peers timing out), not a
GrimTorrenter bug - but it surfaced a genuine, pre-existing gap along the way:
`TorrentEngine.addMagnet()` starts a background metadata fetch (BEP 9 `ut_metadata` over BEP
10, [[0028-magnet-links-and-dht]]) and returns immediately; if that fetch never succeeds (no
reachable tracker, DHT lookup fails, or every candidate peer fails to answer or is unreachable),
the whole attempt failed completely silently before this - a `WARNING` server log line and
nothing else. The frontend cleared the add field the same way whether the add would eventually
succeed or was already doomed, so a user had no way to tell the two apart.

Two pieces, closing this gap together (a transient row with no failure signal to key off would
just be a different kind of silent lie - a stuck spinner instead of stuck silence):

1. **Transient pending row** - a magnet submission now shows a spinner row the instant it's
   submitted, same as a file upload already gets via [[0029-optimistic-upload-feedback]]'s
   `pendingUploads`/`PendingUpload` mechanism, which magnets never picked up until now.
2. **Failure feedback** - a new `EventType.MAGNET_ADD_FAILED` library event is recorded at
   every point the background fetch gives up, so the failure is visible with a timestamp in
   the Events tab, and the frontend can use it to resolve (not just leave stuck) the pending
   row with an error toast.

## Backend (`grimtorrenter-engine`)

**`EventType.MAGNET_ADD_FAILED`** is a partial exception to the "every torrent-scoped type has
both `infoHash` and `torrentName`" rule the rest of `EventType`'s Javadoc documents:
`infoHash` is always set (`magnet.infoHash().hex()`, the same accessor
`TorrentEventListener` already uses), but **`torrentName` is deliberately always `null`**, even
when the magnet carried a `dn=` display name. The Events page's template only renders
`event.torrentName` as a `routerLink` to `/torrents/{infoHash}` — and unlike every other
torrent-scoped event type, this infoHash was *never* actually added as a real torrent, so that
link would dead-end. Any display name goes into the free-text `message` field instead, which
the same template already renders as plain text with no link.

**Five recording points, all in `TorrentEngine.java`**, each an addition right next to an
existing `LOG.log(WARNING, ...)` that already named the same failure — a new
`recordMagnetAddFailed(MagnetLink, String)` private helper keeps the `LibraryEvent`
construction in one place:
1. `addMagnet()`'s existing synchronous throw (no usable tracker, DHT disabled) — the one path
   that already had direct UI feedback (400 → toolbar echo), included anyway for Events-tab
   completeness.
2. `fetchMagnetMetadataViaTrackerThenAdd()`'s catch — the tracker announce itself threw.
3. `fetchMagnetMetadataViaDhtThenAdd()`'s catch — the DHT peer lookup threw.
4. `fetchMetadataFromCandidatesThenAdd()`'s per-candidate catch — metadata was fetched
   successfully but `addTorrent()` itself then failed (storage/directory error).
5. `fetchMetadataFromCandidatesThenAdd()`'s final fallback — no candidate answered, or none
   had the metadata.

All five already had `this.eventStore` in scope as instance methods — unlike
[[0059-service-status]]'s `createDhtNode`/`createPeerServer`, which were `static` and needed a
parameter threaded through, no signature changes were needed here.

**No REST/WebSocket plumbing changes at all** — `EventStore.record()` already broadcasts over
the existing `/ws/torrents` `"event"` message type ([[0055-library-events]]), which the
frontend already consumes live via `TorrentEventsService.libraryEvents()`.

## Frontend

**`PendingUpload` (`torrent-list.ts`) gains an optional `infoHash`** — unset for a file-upload
entry (that path still resolves synchronously in `uploadFile()`'s own subscribe, unaffected).
For a magnet, it's set **only when derivable client-side**: `parseMagnetParams()` already only
extracts `infoHash` for the 40-hex-char `xt=` form (a pre-existing, documented limitation -
`parseMagnetParams`'s own comment notes duplicate-detection already accepts the same gap for
base32 magnets). A base32-form magnet's pending row is deliberately skipped entirely rather
than shown with nothing that could ever clear it — not worse than the prior "field just
clears" behavior, and avoids inventing a row that can never resolve.

`submitMagnet()`/`submitMultipleMagnets()` push a `PendingUpload` entry **before** firing the
request (same timing `uploadFile()` already uses), via a new shared `magnetDisplayName(uri)`
helper (the same displayName-or-truncated-hash-or-"Magnet link" fallback `echo()` already
computed inline, pulled out to avoid a third copy). Critically, **a 200 response no longer
removes the pending row** — that response only means the background fetch *started*, not that
it succeeded, so the entry survives until its real outcome is known. Only a synchronous error
response (the one case genuinely final at request time) still removes it immediately in
`submitMagnet`'s own `error:` callback, matching how a failed upload already behaves.

**A new `pendingInfoHashEffect`** (a class-field `effect()`, functionally in the same spot the
constructor's other cross-cutting wiring lives) resolves every `infoHash`-bearing pending entry
against two live streams already available on `TorrentEventsService`:
- `events.torrents()` — a torrent with that `infoHash` now exists → success, remove it.
- `events.libraryEvents()` — a `MAGNET_ADD_FAILED` event with that `infoHash` has arrived →
  failure, remove it and show an error toast using the event's own `message`.

No extra bookkeeping needed to avoid duplicate toasts: once an entry is removed, the same
event/torrent reappearing on a later effect run simply has nothing left in `pendingUploads()`
to match against, so re-processing older events is a harmless no-op.

No changes needed to `rows()` or the `'pending'` `TableRow` template rendering — it already
renders any `pendingUploads()` entry as a spinner + name row, regardless of source.

## Stability ([[0051-stability-as-a-standing-consideration]])

No unbounded growth: the five new `eventStore.record()` calls each fire at most once per
failed magnet-add attempt — a user-initiated action, not something a remote peer/tracker can
trigger repeatedly on its own — through the same already-retention-bounded `EventStore`
([[0055-library-events]]'s `eventLogRetentionDays`, never unlimited) every other event type
already goes through. No new locking. No new cleanup path needed — the frontend effect only
ever removes `pendingUploads()` entries, never accumulates state beyond that signal's existing
lifetime.

## Tests

`TorrentEngineMagnetTest` gained two new cases, both deterministic (no real internet needed,
reusing the class's existing `startFakeTrackerServer`/local-HTTP-server fixture):
- `addMagnetThrowsSynchronouslyWhenNoUsableTrackerAndDhtDisabled` (existing test, extended) now
  also asserts a `MAGNET_ADD_FAILED` event was recorded alongside the throw.
- `addMagnetRecordsMagnetAddFailedWhenNoPeerHasTheMetadata` (new) points the fake tracker's one
  returned peer at a port bound-then-immediately-closed (so the connect fails fast with
  "connection refused" rather than a slow timeout), deterministically exercising
  `fetchMetadataFromCandidatesThenAdd()`'s final-fallback recording point.

**Known gap**: the other two async recording points (tracker announce itself throwing, DHT
lookup throwing) aren't covered — forcing those deterministically would need a fake tracker
server that returns a malformed/erroring response, or a fake `DhtNode`, neither of which exist
yet in this test suite. Same shape as the bind-failure gap [[0059-service-status]] already
accepted.

## Alternatives considered

- **Deriving pending-row failure from the log alone** — not possible; server logs aren't
  visible to the frontend at all. The whole point of this change is giving the frontend a
  signal it can actually observe.
- **Setting `torrentName` from the magnet's `dn=` on `MAGNET_ADD_FAILED`** — rejected; see
  "Backend" above, would produce a dead `routerLink` on the Events page.
- **Extending base32-magnet support to derive a client-side `infoHash`** — out of scope here;
  the existing duplicate-detection gap for the same reason was left as-is, and this change
  just inherits (not worsens) that same limitation rather than fixing it as a side effect.
