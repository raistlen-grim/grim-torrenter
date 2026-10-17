# 0097 - Pause and resume return the torrent

Status: Accepted (2026-10-17). Resolves [[0033-per-entry-action-feedback]]'s "Future work" (the
row's state lagging its spinner) and the matching PROGRESS.md known gap. Applies
[[0071-thin-frontend-as-a-standing-consideration]]. Same shape as recheck in
[[0082-forced-recheck-and-reannounce]].

## Problem

`POST /api/torrents/{hash}/pause` and `/resume` answered 204. The row's spinner cleared on that
response, but its state (tag, progress, actions) only changed on the next WebSocket snapshot,
up to 2 s later. For that window the row looked as if the click had done nothing.

## Decision

- **Both endpoints return the `TorrentView` as it is afterwards** (200, JSON), the same as
  recheck already does. Pause is synchronous (`stop()` sets STOPPED and announces STOPPED in the
  background), so the response says STOPPED. Resume answers STARTING; see the revision below.
- **The frontend applies the response at once** (`TorrentActionsService.applying()` →
  `TorrentEventsService.upsert()`), so the row changes state in the same frame its spinner
  clears, wherever the action came from: the row, the detail panel, bulk actions or keyboard.
- 404 for an unknown torrent or a pending magnet is unchanged ([[0094-session-revocation-error-shape-and-api-consistency]]).

This is the 0071 preference: a synchronous response carrying the real resource, not an
optimistic client-side guess. 0033's suggested optimistic local update was rejected for that
reason: a resume can end in ERROR, and the client would have to guess and then reconcile.

API change: a client that ignored the 204 body is unaffected. One that checked for exactly 204
needs to accept 200.

## Stability

No new state. The response is built from the session after the engine call returns. A torrent
removed concurrently in that gap gets a 404 from `requireSession`, the same as any other
per-torrent endpoint.

## 2026-10-17 revision

Resume no longer waits for the announce ([[0098-background-start-and-starting-state]]), so its
response is the torrent in STARTING, not the outcome. The outcome arrives through the normal
snapshots, the same way recheck's progress does.
