# 0011 — Bencode value model and decoder

**Status:** Accepted

## Decision

`BValue` is a sealed interface (`permits BString, BInteger, BList,
BDictionary`), each variant a record:

- **`BString(String raw)`** — bencode strings are raw bytes, not
  necessarily valid UTF-8 (piece hashes, peer IDs). `raw` stores those
  bytes losslessly via **ISO-8859-1**, which maps every byte value 0-255 to
  exactly one `char` and back. This gets correct `equals`/`hashCode` and a
  byte-order-consistent `compareTo` for free from `String`'s own
  implementation, instead of hand-rolling them to work around the
  well-known Java records + array-component gotcha (record-generated
  `equals`/`hashCode` on a `byte[]` field would compare by reference, not
  content). `bytes()` and `utf8()` accessors convert back out.
- **`BInteger(long value)`** — bencode integers are unbounded in the spec,
  but every real quantity in this domain (piece lengths, file sizes) fits
  comfortably in a `long`. Not using `BigInteger`: no realistic value in
  this project needs it.
- **`BList(List<BValue> values)`** — defensively copied to `List.copyOf` in
  the compact constructor for immutability.
- **`BDictionary(Map<BString, BValue> entries)`** — backed by a `TreeMap`
  (via the compact constructor), so iteration order always matches
  bencode's required canonical key ordering (sorted by raw byte value)
  regardless of what order keys were supplied or decoded in. This also
  self-heals input from encoders that don't sort keys correctly.

`BencodeDecoder.decode(byte[])` decodes exactly one value and throws
`BencodeException` if trailing bytes remain — appropriate for whole-file
decoding of a `.torrent` file, which is exactly one bencoded dictionary.

The decoder is **lenient on non-canonical but unambiguous integers** (e.g.
`i042e`, a leading zero) rather than rejecting them per strict spec — some
real-world torrent files/tools produce these, and refusing to open an
otherwise-usable torrent over it is user-hostile. Canonical output is the
encoder's responsibility (not yet built - see [[0009-phased-scope]] for
when the encoder is needed for info-hash computation).

No offset/span tracking is exposed by the decoder — see the note in
[[0009-phased-scope]]'s Phase 1 discussion: the info-hash will be computed
by re-encoding the parsed `info` dictionary once the encoder exists, not by
slicing original bytes, because canonical bencode encoding of the info
dict is already a de facto protocol requirement (every client must produce
it identically or torrents wouldn't work across clients at all).

## Alternatives considered

- **Track source byte offsets per decoded value** (for exact-byte
  info-hash slicing) — rejected as unnecessary complexity given the
  re-encoding approach is already reliable for this specific case, and
  offset-tracking would leak parsing concerns into the value model.
- **Strict canonical-form rejection on decode** (leading zeros, etc.) —
  rejected in favor of leniency on decode / strictness on encode (Postel's
  law), for real-world interoperability.
