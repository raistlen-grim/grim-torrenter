# 0077 — Labels (a managed list with stable ids)

**Status:** Accepted

## Decision

Torrents can carry any number of **labels**. Labels are a **managed list**, engine-wide: each
label has an immutable `id` (a UUID, assigned once at creation, never shown) and a mutable
display `name`. A torrent stores label **ids**, never names, so a rename is a one-record change
that touches no torrent - every torrent carrying the label shows the new name immediately, and
nothing on disk per torrent needs rewriting (confirmed with the user, who proposed the
id-plus-name split). Labels are assigned *after* a torrent is added (confirmed); assigning at add
time, and having the watch folder assign one from a subfolder name, are later slices - the first
would have to work for a magnet still fetching metadata ([[0070-pending-magnet-as-first-class-torrent]]).

Deliberately **not** included: colors, hierarchy, per-label behavior (default seeding limit,
bandwidth limit, save path). Those are where labels get powerful, and each is its own decision.

### Why a managed list, not labels derived from whatever torrents carry

A derived set can't be pre-created, can't exist while empty, and makes rename mean rewriting
every torrent. A managed list gives create/rename/delete as real operations, and the id makes
rename free.

### The registry

`LabelRegistry` (`grimtorrenter-engine`, new `label` package, no dependencies) owns the list,
persisted in one engine-wide file, `configDirectory/.grimtorrenter-labels` (`id=name` lines,
name split on the first `=`; hand-rolled like every other marker so the engine keeps its zero
production dependencies). Writes are atomic (temp file + move), and load is tolerant - a bad or
duplicate line is skipped rather than failing startup, a missing file is an empty list.
Insertion order is preserved (the sidebar order). Reads are lock-free against an immutable
snapshot; mutations take a `ReentrantLock` (cold path - a user editing labels) per
[[0007-concurrency-model]].

Name rules (the registry is the authority; the frontend only pre-validates for ergonomics per
[[0071-thin-frontend-as-a-standing-consideration]]): trimmed, 1-32 characters, no control
characters, unique case-insensitively. A duplicate is a conflict (409), everything else invalid
is a 400.

### Assignment

A torrent's label ids live in one per-torrent marker, `.grimtorrenter-label-ids`, in the
config-side torrent directory ([[0065-config-side-per-torrent-storage]]) - one id per line - so
it follows the same lifecycle as every other marker, including surviving a keep-files remove and
re-add. `TorrentSession` holds them as a volatile list with a setter (labels change no engine
behavior, so unlike file priorities ([[0075-file-priorities]]) there's no reason to thread them
through the telescoping factory overloads). `TorrentEngine.setTorrentLabels()` validates every id
exists and the count is within the cap, writes the marker, then applies it - a failed write
throws before the in-memory change.

**Delete cascades**: deleting a label removes its id from every loaded session (rewriting each
marker). Markers of torrents that aren't loaded (removed with files kept) may be left holding a
dead id; that's harmless - ids not in the registry are dropped on read, so it never resurfaces
and a re-created label always gets a fresh id.

### REST

- `GET /api/labels` - `[{id, name}]` in list order.
- `POST /api/labels` `{name}` - creates and returns the label.
- `PUT /api/labels/{id}` `{name}` - renames and returns it.
- `DELETE /api/labels/{id}` - deletes (cascading), 204.
- `PUT /api/torrents/{h}/labels` - body is the full array of label ids, responds with the
  updated `TorrentView` (whole array, and the real resource back, like file priorities).
- `TorrentView` gains `labelIds: string[]`.

**Live propagation:** the label list rides the existing 2s snapshot tick as its own WebSocket
message type, `"labels"`, so a second open browser sees a create/rename/delete within a couple
of seconds without a separate mutation-time hook, and a newly opened client gets it too. The
list is tiny, so re-sending it every tick costs nothing measurable. (`TorrentView` carries ids
only, so the rows themselves never change on a rename - only this list does.) The frontend
still fetches `GET /api/labels` once at startup so the first paint doesn't wait for a tick.

### Frontend (separate slice)

Sidebar "Labels" group with counts and a manage dialog (create/rename/delete), a "Labels…" row
context-menu item opening an assign dialog, label chips on the row, label filter composing with
the status filter and search. Filtering stays client-side over the already-pushed torrent list,
the same way the status filter works ([[0043-app-shell-and-filtering]]).

## Stability

- **Unbounded growth:** capped - at most 200 labels engine-wide and 20 per torrent, both
  rejected with a 400 beyond that, so neither the registry file nor a torrent's marker can grow
  without limit (an authenticated client is still a client).
- **Locking:** mutations under a `ReentrantLock`, reads against an immutable snapshot - no
  `synchronized`, nothing on a peer/piece hot path. A `TorrentSession`'s ids are a single
  volatile reference to an immutable list.
- **Crash safety:** the registry is written atomically (temp + move), so a crash mid-write
  leaves the previous file intact; torrent markers are single small writes like every other
  marker, and a torn one degrades to "ignore unknown lines" on read.
- **Hostile input:** names are length-limited and control-character-free (no newline can forge
  a second `id=name` line in the file); ids from a client are only ever accepted if they already
  exist in the registry.
- **Cleanup:** no new threads, sockets or handles.

## Deferred

- Assigning labels at add time and from the watch folder.
- Behavior attached to a label (limits, save path), colors, reordering.
- Bulk assignment (waits on multi-select on the torrent list).

## Addendum (2026-09-19): multi-label filtering and an active-filters strip

Found via real use: picking a label in the sidebar and then "All" left the list still filtered,
because the status nav and the label group are separate filters that just look like one menu -
and nothing showed that a label was still narrowing the list. Two changes, both decided with the
user:

- **An active-filters chips strip** on the toolbar row, right after the search field (only when something is active - inline so selecting a filter never pushes the list down) lists every
  sidebar-driven filter - the status filter and each selected label - as a removable chip, with a
  "Clear" that resets both (not the search text, which has its own box). "All" in the sidebar still
  only resets the status filter; the change is that what else is filtering is now always visible.
- **Several labels at once.** Clicking a sidebar label toggles it in or out of the filter (it used
  to replace the selection). `TorrentFilterService` holds a list of selected label ids plus a
  `labelMatchMode`: **Any** (the default - a torrent needs at least one selected label) or **All**
  (it needs every one), switched from an "Any / All" control that appears in the strip once two or
  more labels are selected. Status and search always AND with the label result, the usual
  same-field-OR / cross-field-AND model. Selected ids for a deleted label are ignored, as before.

Still in-memory only, like every other filter here ([[0043-app-shell-and-filtering]]). No backend
change, and none of this touches the label data model.
