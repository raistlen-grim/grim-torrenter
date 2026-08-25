# 0012 — Metainfo parsing

**Status:** Accepted

## Decision

`TorrentMetadata` is a sealed interface (`permits SingleFileTorrent,
MultiFileTorrent`), matching the info dictionary's actual two shapes per
the spec rather than one record with an optional/synthetic file list.
Callers pattern-match explicitly rather than relying on an "empty list
means single-file" convention. Common fields (`name`, `pieceLength`,
`pieces`, `infoHash`, `announce`, `announceList`) are declared on the
interface; `totalLength()` is a default method computed differently per
variant.

**`InfoHash(String hex)`** — introduced because `byte[]` info hashes would
hit the same records-plus-array-equals gotcha noted in
[[0011-bencode-value-model]], and info-hash identity is used pervasively
(tracker requests, peer handshakes, session lookup keys) — worth fixing
once here rather than letting it recur across the `tracker`/`peer`/`engine`
layers later.

**`PieceHashes(byte[] concatenated)`** — kept as raw `byte[]` (manual
`equals`/`hashCode` override), unlike `InfoHash`/`BString`. This one is a
binary buffer sliced by piece index (`hashAt(int)`), not a single value
compared for identity as a whole, so the String-backing trick doesn't fit
as naturally here.

**Info-hash computation** re-encodes the parsed `info` `BDictionary` via
`BencodeEncoder` and SHA-1s the result, rather than slicing original
source bytes — see [[0011-bencode-value-model]] for why that's reliable
(canonical info-dict encoding is already a protocol-wide requirement).

`MetainfoParser.parse(byte[])` throws `MetainfoException` for any missing
or wrong-typed required field. `announce` is nullable (not `Optional`,
since it's a stored field, not a return value) — a torrent with no
`announce`/`announce-list` parses successfully; whether that torrent is
*usable* (e.g. Phase 1 has no DHT) is a concern for the `engine`/`tracker`
layers, not the parser.

## Alternatives considered

- **One `TorrentMetadata` record, always-populated file list** — rejected,
  hides the spec's real distinction and pushes an artificial single-vs-multi
  convention onto every consumer.
- **`byte[]` for `InfoHash`** — rejected for the same reason `BString`
  isn't a raw `byte[]`; would silently break equality/map-key use the first
  time two info hashes needed comparing.
