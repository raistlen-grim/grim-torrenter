# 0029 — Optimistic upload feedback and already-exists signal

**Status:** Accepted

## Decision

Raised by the user after real use: uploading a `.torrent` file gives no
feedback until the backend responds (the long-known upload-latency gap,
previously just deferred - see the `upload_latency_ux` memory note), and
worse, re-uploading a torrent that's already tracked (including one that
finished, was removed, but left its data and info-hash marker in place -
[[0024-name-based-download-directories]]/[[0026-resume-state-persistence]])
silently succeeds with no signal that nothing new happened - it reads as
"did this even work?" rather than "yes, and you already had it."

### Backend: `TorrentEngine.AddTorrentResult`

`addTorrent()` was already idempotent (returns the existing session for
an info hash already tracked) but had no way to tell a caller which case
occurred. It now returns `AddTorrentResult(TorrentSession session,
boolean alreadyExisted)` - `alreadyExisted` is set from whether
`sessions.computeIfAbsent`'s mapping function actually ran, not from a
separate `containsKey` check beforehand (which would parse the torrent
bytes twice and open a narrow check-then-act race with a concurrent add
of the same torrent). `grimtorrenter-app`'s `TorrentResource.add()` wraps
this in a new `AddTorrentResponse(TorrentView torrent, boolean
alreadyExisted)` - `alreadyExisted` doesn't belong on `TorrentView` itself
(it's a property of the add action, not of the torrent), so it gets its
own small response type rather than growing that DTO for one endpoint.

This changed `addTorrent`'s public return type, which meant updating
every call site (`TorrentResource`, and ~14 assertions in
`TorrentEngineTest` that unwrap `.session()` now) - judged worth the
mechanical churn over the alternative (parse-twice-plus-a-race in the app
layer) for the same reason `TorrentEngine` already owns other atomic
add/remove decisions itself rather than delegating them to callers.

### Frontend: optimistic "Processing" row

The upload button now adds a lightweight client-only placeholder row the
instant a file is picked, removed the moment the (possibly slow) POST
response arrives - covering exactly the latency window that existed,
since `TorrentSession.start()`'s initial tracker announce is still fully
synchronous within that one request. On success, the returned
`AddTorrentResponse.torrent` is applied immediately via a newly-public
`TorrentEventsService.upsert()` rather than waiting for the next 2s
snapshot, and an "Already added" toast fires if `alreadyExisted` was
true; on failure, the placeholder is removed and the existing error toast
fires as before.

**A discriminated union (`TableRow = pending | torrent`), not a synthetic
pending "torrent" object.** A pending upload has almost none of
`TorrentWithRate`'s shape - no info hash yet, no state, nothing for
`TorrentRow`'s actions to operate on - so forcing it through that
component would mean teaching it to tolerate a mostly-empty/fake torrent.
Instead the table's `pTemplate="body"` branches on `row.kind`: a pending
row renders a minimal two-cell placeholder directly in
`torrent-list.html`, a real row still renders `<tr app-torrent-row>`
exactly as before. `trackByRow` (renamed from `trackByInfoHash`,
[[0027-table-row-identity-for-live-updates]] still applies) discriminates
the same way for row-identity purposes.

**What this does *not* fix**: the underlying add-to-visible latency
itself (the tracker announce inside `TorrentSession.start()` is still
synchronous within the HTTP request) - only the *feedback* during that
wait. Also does not address the deeper bug identified alongside this:
re-adding a previously-removed-with-data-kept torrent still redownloads
from scratch rather than recognizing and reusing the existing data via
the same `restoreAsync()` verification already built for restart-resume.
That's tracked as a TODO in `PROGRESS.md`, explicitly deferred as a
separate, larger piece of work.

## Testing

`TorrentEngineTest` gained `addTorrentReportsAlreadyExistedOnlyOnASecondAdd`.
`TorrentResourceTest` gained `uploadingTheSameTorrentTwiceReportsAlreadyExisted`
and updated its existing JSON-path assertions for the new `{torrent,
alreadyExisted}` response shape.

## Alternatives considered

- **Check-then-act in the app layer** (`getTorrent(infoHash).isPresent()`
  before calling `addTorrent`) instead of changing `addTorrent`'s return
  type - rejected: parses the torrent bytes twice, and opens a race
  between the check and the add for two near-simultaneous uploads of the
  same torrent. Narrow and low-stakes for a single-user app, but the
  atomic version was no harder to build.
- **Add `alreadyExisted` to `TorrentView`** - rejected; it's a property of
  the add action, not of the torrent's ongoing state, so it doesn't
  belong on the DTO every other endpoint also returns.
- **Route the pending placeholder through `TorrentRow`** with an
  "unknown" pseudo-state - rejected in favor of the discriminated union;
  see above.
