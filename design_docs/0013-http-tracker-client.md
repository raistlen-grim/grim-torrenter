# 0013 — HTTP tracker client

**Status:** Accepted

## Decision

`TrackerClient` is a one-method interface (`announce(TrackerRequest) ->
TrackerResponse`); `HttpTrackerClient` implements BEP 3 over
`java.net.http.HttpClient`.

- **Compact peer format only** (`compact=1` on every request). If a
  tracker responds with a non-compact (dictionary-list) `peers` field,
  `HttpTrackerClient` throws `TrackerException` rather than parsing it.
  Compact is effectively universal among real trackers today.
- **Custom byte-level percent-encoding** for `info_hash`/`peer_id`
  instead of `java.net.URLEncoder` — those fields are raw 20-byte binary,
  not text, and `URLEncoder` is string/charset-oriented (and encodes space
  as `+`, not `%20`). Getting this wrong means the client can't talk to
  any tracker at all, so a small dedicated encoder removes the ambiguity.
- **`PeerId(String hex)`** mirrors `InfoHash`'s shape (see
  [[0012-metainfo-parsing]]) for the same reason — correct value equality
  without the records-plus-`byte[]` pitfall. Not factored into a shared
  base type with `InfoHash`: exactly two occurrences of this pattern exist
  in the codebase, and the two are semantically distinct identities (a
  torrent's identity vs a client's) that should stay type-distinct.
- **`TrackerException` is unchecked**, consistent with `BencodeException`
  and `MetainfoException` — all three represent "malformed/unexpected
  external input that will routinely happen with real-world data," and the
  codebase treats that category consistently as unchecked exceptions
  callers opt into catching rather than are forced to.
- A `failure reason` field in the tracker's response is treated as a hard
  failure (thrown exception), not a data field on `TrackerResponse` — there
  is no usable interval/peer data alongside it.
- **Sends a `User-Agent: GrimTorrenter/0.1.0` header** on every request —
  added after real-world testing against a live public tracker
  (`tracker.tfile.co`) returned HTTP 403. Java's `HttpClient` sends its own
  default User-Agent absent an override, and that's an obvious signature
  of non-client (script/library) traffic that trackers commonly filter
  out. Every real BitTorrent client identifies itself this way on
  announce; this isn't spoofing, it's the protocol convention we were
  simply missing. See [[0021-engine-logging-and-error-visibility]] for how
  this was diagnosed (state transitions and announce failures were
  previously swallowed with no logging at all).
- **Error messages include the full request URL (with query string)**,
  not just the base announce URL — needed to actually reproduce a failing
  request outside the app (e.g. via curl) for diagnosis. The User-Agent
  fix above turned out not to be the actual cause of the 403 from
  `tracker.tfile.co`; curling the bare announce URL (no parameters) got
  the same generic WAF-style rejection regardless of User-Agent, which
  isn't representative of the real, fully-parameterized request our
  client sends - this fix makes that request reproducible.

## Testing

`HttpTrackerClientTest` runs a real local server via the JDK's built-in
`com.sun.net.httpserver.HttpServer` rather than adding a mocking library —
avoids a new dependency for one test class and exercises the actual
`HttpClient` request/response round trip, including verifying the
percent-encoded `info_hash`/`peer_id` decode back to the original bytes.

## Alternatives considered

- **Support non-compact peer lists as a fallback** — rejected for now
  (YAGNI); can be added if a real tracker that ignores `compact=1` is
  actually encountered.
- **Shared base type for `InfoHash`/`PeerId`** — rejected as premature
  abstraction for two occurrences; see [[0012-metainfo-parsing]].
