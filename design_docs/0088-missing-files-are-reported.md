# 0088 - Missing files are reported, not downloaded again

Status: Accepted (2026-10-10). Revises [[0065-config-side-per-torrent-storage]]'s "accepted, not
engineered around" position on a download path that has gone missing, and replaces one of
[[0082-force-recheck-and-reannounce]]'s known limits. Builds on
[[0026-resume-state-persistence]] (the restore-time verification pass).

## Problem

Which torrents exist is recorded in the config folder ([[0065-config-side-per-torrent-storage]]).
Deleting a torrent's data from the downloads folder by hand therefore removes nothing from the
app. On the next start the torrent came back, `TorrentStorage.create()` put empty files in place
of the deleted ones, every piece failed verification, and - if the torrent had been running - it
started downloading everything again without a word. 0065 saw this coming and accepted it as
"self-healing".

Found on the first published build, before any outside tester: two torrents deleted from the
command line reappeared and were several per cent into re-downloading before anyone looked. For
someone who deleted the files to get the space back, that is the opposite of what they meant.
Other clients (qBittorrent's "Missing files") stop and say so.

## Decision

**A torrent the engine already had whose files are gone comes back in `ERROR`, saying the files
are missing. Nothing is recreated and nothing is downloaded until the user forces a recheck.**

- **What counts as missing.** A file that is not on disk and whose priority is not `SKIP`
  ([[0075-file-priorities]]). No new marker is needed to know the file "used to be there":
  every file is created, at full length, when the torrent is added, so a file that is absent
  later was removed by something else. A skipped file is left out on purpose - deleting a file
  you chose not to download is reasonable - and is silently put back when its neighbours next
  need it. One wanted file missing out of many is enough.
- **Where it is checked.**
  - *At restore* (`TorrentEngine.restoreOne()`), before anything is allocated.
    `TorrentStorage` gains `open()` (work out the paths, touch nothing), `missingFiles()` and
    `allocate()`; `create()` is now `open()` + `allocate()`.
  - *On a forced recheck* of a loaded torrent. This used to fail with a raw I/O error
    ([[0082-force-recheck-and-reannounce]]); it now ends in the same missing-files state.
  - **Not** when a torrent is added over an existing directory ([[0037-reuse-existing-data-on-readd]]):
    there the user has just asked for the torrent, and absent files are simply created.
- **The state is `ERROR` plus a flag, not a new `TorrentState`.** `lastError` carries the message
  ("The files are missing from the downloads folder. Force a recheck to download again, or
  remove the torrent." - or "The file is", or "2 of 12 files are"), written by the backend, and
  `TorrentView.missingFiles` is true so a client can offer the two ways out without reading
  text ([[0071-thin-frontend-as-a-standing-consideration]]). Going through `ERROR` means the
  existing row, details panel, Error filter and `ERROR` library event all show it with no new
  concept for any client to learn. Completed pieces read as zero: nothing has been verified.
- **The ways out.**
  - *Force recheck* is the go-ahead to download again: the files are recreated, verified (so
    anything that was put back by hand is recognised and kept), and the torrent then returns to
    what it was doing - running if it was running, paused if it was paused.
  - *Remove*, as for any torrent.
  - Putting the files back and restarting also clears it, since the check is made afresh at
    every restore.
- **Resume is deliberately not a way out.** `start()` on a torrent in this state changes nothing
  on disk or on the network; it only records that the torrent should run once the files are
  back. "Resume all" must not be able to start gigabytes of re-downloading. (The web UI offers
  Resume only for `STOPPED` torrents, so in practice the button isn't there.) Pause works and is
  remembered the same way.
- **No frontend change.** The message, the `ERROR` state and the existing *Force recheck* menu
  item are enough. `missingFiles` is in the REST/WebSocket payload but the web UI does not read
  it yet.

## Consequences

- A downloads folder that is not there at all - a disk that failed to mount, a volume mapped to
  the wrong place after editing a compose file - now shows every torrent as missing its files
  instead of recreating them all, empty, in the wrong place and starting again. Fix the mount
  and restart, and they verify as normal.
- A recheck that finds a file gone no longer fails with an I/O error, and no longer needs a
  restart to recover: a second recheck recreates it.
- After a re-download the torrent's lifetime figures carry on from before, and
  `completedAtEpochMillis` keeps its first value, so no second `COMPLETED` event is recorded -
  the same known limit a recheck already has.

## Alternatives considered

- **Keep re-downloading, document it** - the cheap option; rejected by the user as inconsistent
  with other clients.
- **A new `MISSING_FILES` state** - every client, filter, sort order and state count would need
  to learn it. `ERROR` plus a flag says the same thing through shapes that already exist.
- **Flag only when every file is missing** - simpler to explain, but one deleted file out of a
  season pack would still be quietly fetched again, which is the same surprise at a smaller size.
- **A marker recording "this torrent had data"** - not needed, since files are created at add
  time; and a marker can disagree with the disk, which is the whole problem here.
- **Remove the torrent automatically when its data is gone** - destroys the record of a torrent
  whose disk was merely unmounted.
- **Let Resume mean "download again"** - one fewer concept, but it makes Resume all dangerous.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **Cost.** One `stat` per file per torrent at restore, on the thread that already reads the
  markers; nothing periodic. A recheck does the same once.
- **No new unbounded state.** Two booleans per session. A torrent in this state holds no file
  handles, no connections, no scheduler and no verification permit.
- **Stale handles.** A file deleted while its torrent is loaded may still be open in the shared
  `FileHandlePool` ([[0047-bounded-file-handle-pool]]), and on Linux such a handle goes on
  reading the old data. Both recheck paths evict the torrent's handles before deciding anything
  or recreating anything, so a recreated file is never verified through a handle to its
  deleted predecessor.
- **Failure on the way out.** If the files can't be recreated (an unwritable folder), the
  session stays in the missing-files state with that error as its message; nothing is half
  started.
- **Locking.** The new paths run under the session's existing monitor, which `start()`,
  `stop()` and `recheck()` already take; none of it is on the peer hot path
  ([[0007-concurrency-model]]).
- **Hostile-peer angle.** None: nothing a peer or tracker sends reaches this decision.

## Known limits

- **A file deleted while the torrent is running is not noticed until a recheck or a restart.**
  Until then reads and writes carry on against the deleted file's open handle, or fail with an
  I/O error and put the torrent in an ordinary `ERROR`. There is no periodic scan.
- **A crash in the instant between a torrent being recorded and its files being created**
  ([[0069-persist-before-starting]]) restores as "files missing" although they never existed. A
  recheck starts it.
- **An empty or truncated file is not "missing"** - it exists, so it is verified, fails, and is
  downloaded again as before.
- **The `ERROR` library event carries no message**, like every other `ERROR` event; the reason
  is on the torrent.

## Tests

`TorrentStorageTest`: `open()` creates nothing and reports every file until `allocate()`; only
absent files are named; `allocate()` keeps data already there. `TorrentSessionTest`: restore
with the file gone reports it, recreates nothing and announces nothing, and Resume doesn't
change that; a recheck then recreates and downloads again; a paused torrent (paused before, or
paused while waiting) is rechecked but not started; a missing skipped file is not reported and
a missing wanted one is counted; a recheck that finds a file deleted reports it and only the
next one downloads again. `TorrentEngineTest`: a restart after the data was deleted reports
missing files until rechecked. Not covered: `TorrentView.missingFiles` in the REST payload, the
recreate-fails path, and the page itself. Not yet built or run.
