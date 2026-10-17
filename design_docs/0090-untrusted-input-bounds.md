# 0090 - Untrusted input is bounded and validated at the edge

Status: Accepted (2026-10-17). Found in the pre-release engine review
(`code_review/01-engine.md`, items A1-A3 and A5). Applies
[[0051-stability-as-a-standing-consideration]]'s hostile-peer angle to the places that had
been missed.

## Problem

Four kinds of input come from parties we don't control: `.torrent` files and magnet metadata,
peer-wire messages, and tracker replies. The fourth, DHT packets, was already bounded by its
UDP buffer. The others were trusted in ways that allowed:

- **Writing outside the download directory.** A torrent's `name` and file `path` segments went
  straight into `Path.resolve()`. A segment of `..` or an absolute path made `TorrentStorage`
  truncate and overwrite arbitrary files the container user can write: settings, config
  markers, the proxy password. "Remove + delete data" could also recursively delete outside
  the torrent. A magnet doesn't protect against this: the info hash only proves the dict is the
  one the magnet named, not that the dict is benign.
- **Overwriting verified data.** Every `Piece` message was written to disk if its block wasn't
  already marked received. Pieces verified at restore never mark their blocks, so any connected
  peer could overwrite data we were seeding.
- **Exhausting memory.** The peer-wire length prefix was trusted, allowing messages of up to
  2 GB. `Have` grew the peer's piece `BitSet` to any index (~256 MB from a 9-byte message). A
  peer's `metadata_size` was allocated up front (up to 2 GB per magnet candidate). Bencode
  recursed without limit, so `llll…` could overflow the stack; `StackOverflowError` escaped the
  read loop's catch and left the connection open with its slot held. The direct HTTP tracker
  path buffered a reply of any size; the proxied path was already capped.

## Decision

**Validate or cap every untrusted size and every untrusted path where it enters the engine.
Reject what fails; don't try to repair it.**

| Input | Rule | Where |
|---|---|---|
| Torrent `name`, file `path` segments | Each must be one plain path component: not empty, `.` or `..`, and no `/`, `\` or NUL. An empty `path` list is rejected. The whole torrent is refused (`MetainfoException`). | `MetainfoParser` |
| Resolved file paths | Must normalise to somewhere inside the storage base directory (`StorageException`). A second check, for metadata built without the parser. | `TorrentStorage.open()` |
| Incoming `Piece` | Written only if the index is in range, `begin` is block-aligned, the length is exactly that block's length, the piece isn't verified yet, **and** this connection holds a claim on the block (meaning we sent it the matching `Request`). Anything else is dropped with a DEBUG log; the connection stays open. | `TorrentSession.isExpectedBlock()` |
| Peer-wire message length | At most 2 MiB (`PeerWireCodec.MAX_MESSAGE_LENGTH`). That covers a 16 KiB block, any extension message, and a bitfield for 16M pieces. Larger or negative lengths throw `PeerWireException` before anything is read, which disconnects the peer. | `PeerWireCodec.readMessage()` |
| `Have` index | `0 <= index < MAX_PIECE_INDEX`, the largest index a maximum-size bitfield could describe. That bounds the per-connection `BitSet` at the same ~2 MiB a bitfield can reach. | `PeerWireCodec` |
| Bencode nesting | At most 64 levels deep (real data nests about 5). String lengths are checked without integer overflow. | `BencodeDecoder` |
| `metadata_size` | `0 < size <= 16 MiB`, enough for the info dict of a ~800k-piece torrent. The buffer grows as pieces arrive instead of being allocated from the claim. | `MetadataFetcher` |
| HTTP tracker reply | At most 4 MiB, with a 30 s body deadline, on the direct path too, matching the proxied path. | `HttpTrackerClient` |

### Why reject rather than sanitise path names

Other clients rewrite bad components (for example `..` to `_`). That keeps odd-but-harmless
torrents working, but it means there are two names for the same file and the rewrite rules
must be right on every platform. Legitimate torrents don't contain these components. Rejecting
gives one clear error ("Unsafe path segment in torrent: ..") through the existing
add-failure paths (REST error, watch-folder `failed/`, `MAGNET_ADD_FAILED`). A torrent added
before this change that fails the check is skipped with a warning on the next restore; no such
torrent is expected to exist.

### Why the block must be claimed, not just in range

A range check alone still lets a peer overwrite blocks of a piece we're downloading from
someone else, mixing their data in. That would make the piece fail its hash and be
re-downloaded, wasting time without corrupting anything. Requiring the claim ties every write
to a request we made. The cost is that a late reply whose claim was already released (by a
choke, or by a takeover after `IN_FLIGHT_BLOCK_TIMEOUT_MILLIS`) is thrown away and the block is
fetched again. That's rare, and it's what BEP 3 says a choke means anyway.

## Stability

This decision exists for stability; the per-input bounds are in the table. No new state, threads
or files. All the checks are O(1) per message, except `isHeldBy`, which is one map lookup.
Disconnect-on-violation reuses the read loop's existing `PeerWireException` path, so cleanup
(slot release, claim release, byte accounting) is unchanged.

## Alternatives considered

- **Pass the piece count into `PeerConnection` to bound `Have` exactly.** More precise, but it
  means a new parameter through five `connect`/`accept` overloads for a bound that only needs
  to be finite. The bitfield-derived bound costs nothing.
- **Catch `StackOverflowError` in the read loop.** It treats the symptom, and an error caught
  after a deep recursion has already used the stack. The depth limit stops it from happening.
