# 0051 — Stability as a standing consideration for every decision

**Status:** Accepted

## Decision

Through [[0047-bounded-file-handle-pool]], [[0048-piece-verification-throttling]],
[[0049-many-torrents-load-test]], and [[0050-piece-manager-reentrant-lock]] — the engine
stability/scale audit described end-to-end in `STABILITY.md` — stability stopped being an
implicit property the engine was hoped to have and became an explicit bar: no crashes, no
resource usage that grows unboundedly with uptime or torrent count, and predictable behavior
handling many torrents at once. That bar shouldn't only apply retroactively, the next time
someone runs an audit. From this point on, **stability is a standing consideration for every
architecture or pattern decision recorded in `design_docs/`, not a special category of
decision of its own.**

Concretely, when writing a new design doc (or revising one), the rationale should say something
about resource/failure behavior wherever the decision has any — even briefly, even to note "no
stability implication, this is UI-only." Worth asking explicitly:

- Does this decision add anything unbounded — memory, file descriptors, threads, connections,
  disk — that scales with uptime, torrent count, or peer count?
- Can a hostile or simply misbehaving remote peer/tracker use this path to cause a crash, a
  hang, or a resource leak?
- If this touches locking or concurrency, does it follow [[0007-concurrency-model]]'s
  no-`synchronized`-in-the-hot-path rule?
- Is there a cleanup/error path, and does it run on every exit (including exceptions), not just
  the happy path?

Most decisions will have a short or trivial answer — that's fine. The point isn't to force
every doc to reproduce the audit's depth; it's to make "did we think about stability here"
answerable by reading the doc, the same way [[0007-concurrency-model]]'s pinning rule is already
supposed to be self-evidently honored or explicitly addressed when it isn't (see
[[0050-piece-manager-reentrant-lock]] for exactly that: a doc/reality gap closed specifically
*because* it was visible against a standing rule, not because anyone found a live bug).

This is recorded here, as its own design doc, rather than silently folded into `CLAUDE.md`
prose, because it's itself a pattern decision about how decisions get made — per `CLAUDE.md`'s
own convention, that gets a file. `CLAUDE.md` points here rather than restating the rationale.

## Testing

N/A — process/convention decision, nothing to test. Its only real "test" is whether future
`design_docs/` entries actually carry a stability note; that's enforced by habit and review, not
tooling.

## Alternatives considered

- **Leave it as an implicit expectation**, the way it effectively was before the audit —
  rejected: that's exactly how the file-handle and verification-throttling gaps went
  unnoticed for as long as they did. An explicit, named standing consideration is cheap and
  makes the omission visible the next time it happens, rather than waiting for another
  dedicated audit to find it.
- **A checklist/template enforced at doc-creation time** — considered, rejected as
  disproportionate for a project this size; a stated expectation plus the existing habit of
  cross-referencing related docs (which already surfaces `0007`/`0047`/etc. naturally when
  relevant) is enough without adding process overhead.
