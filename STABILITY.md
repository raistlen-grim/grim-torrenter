# GrimTorrenter — Engine Stability

Standalone narrative of the stability requirement this project holds itself to, and every
design decision made to satisfy it, from the original choice of language up through the most
recent hardening work. Rationale in depth lives in `design_docs/` (one file per decision,
cross-referenced below via `[[name]]`) — this file exists to tell the whole story in one
place rather than leaving it scattered across a dozen individual decisions.

## The requirement

GrimTorrenter's engine is meant to eventually stand as its own product, independent of the
web UI wrapped around it - so it has to meet a bar most side-project torrent engines don't
need to: **long-running, self-hosted, and solid enough to run unattended.** Concretely, that
means:

- No crashes or silent data corruption, ever, regardless of how a remote peer behaves.
- No resource usage that grows unboundedly with uptime or with how many torrents are added
  over the process's lifetime.
- Handling *many* simultaneous torrents - each with its own peers, files, and pieces to
  verify - without spiking memory, file descriptors, or threads.
- Recoverable, predictable behavior after a restart, even with a large backlog of
  previously-downloaded torrents to restore at once.

Every decision below either lays the foundation for that bar or was made specifically to
close a gap against it.

## Foundation: choosing Java

[[0001-backend-language-and-framework]] settled on Java (on Quarkus) primarily on the
strength of the primary developer's 20+ years of Spring Boot depth, with Quarkus chosen over
Spring Boot itself for a better fit in a Docker container (fast startup, low memory,
GraalVM-native-image-first design). Go and Rust were both weighed as a better *raw* fit for a
protocol-heavy, highly concurrent networking app, and explicitly rejected in favor of keeping
existing expertise.

That tradeoff is exactly where Java's own stability case comes in. What Java brings to a
long-running network daemon independent of any framework choice:

- **A mature, decades-hardened runtime.** The JVM's garbage collectors, JIT, and memory model
  have been running production server workloads at scale for over 25 years - the kind of
  battle-testing a project this size has no way to replicate itself.
- **`java.util.concurrent`, not hand-rolled primitives.** Every concurrency mechanism this
  project relies on for stability - `Semaphore`, `ReentrantLock`, `ConcurrentHashMap`,
  `LinkedHashMap`'s access-order mode - is a well-understood, heavily-used standard library
  tool, not custom-built synchronization code that would itself be a stability risk to get
  wrong.
- **Virtual threads (JDK 21+).** The specific capability that makes "one thread per peer
  connection, hundreds of times over, written as plain blocking code" viable without the
  traditional one-OS-thread-per-connection cost ceiling - see the concurrency model below.

## Concurrency model: virtual threads, not an event loop

[[0007-concurrency-model]] is the first decision made specifically *for* stability at scale,
well before any audit forced the question. One virtual thread per `PeerConnection`, written
as ordinary blocking-style I/O - chosen over Netty (already available transitively via
Quarkus/Vert.x, more headroom at extreme peer counts) specifically because straight-line
blocking code is simpler to write, read, and unit-test correctly, and this project isn't
chasing the concurrency ceiling a competitive production client would need.

**The one stated care point, from day one**: avoid `synchronized` blocks in the
per-connection hot path, since they can pin a virtual thread to its carrier thread and defeat
the whole point of using virtual threads in the first place. Every later stability decision
that touches locking (below) either follows this rule from the start or was corrected to.

## Resource control built in before the audit

Several stability-relevant mechanisms existed prior to the dedicated audit described below -
worth naming as part of the same throughline, not just the fixes that came after:

- **A per-torrent connection cap.** `TorrentSession.MAX_CONNECTIONS = 30` bounds both
  outbound connects and inbound accepts per torrent, so total connection/thread count scales
  predictably (`torrentCount × 30`) rather than unboundedly per torrent. Its own comment
  documents a deliberate philosophy that recurs throughout the later fixes too: "a small
  overshoot past MAX_CONNECTIONS is an acceptable imprecision" - correctness and simplicity
  over a perfectly hard cap.
- **Real socket timeouts.** 10s connect, 10s handshake, 120s idle-read
  (`PeerConnection`) - a hung or hostile peer can never block a thread indefinitely.
- **[[0042-rate-limiting]]**: a global, live-adjustable token-bucket limiter for
  upload/download bandwidth, later extended with a scheduled off-hours window
  ([[0046-rate-limit-schedule]]) - bounds *network* resource usage the same way the later
  fixes bound file descriptors and memory.
- **[[0041-live-settings-store]]**: operational settings (rate limits, DHT/incoming
  connections) that persist and take effect without a restart, with an explicit,
  acknowledged exception for the two settings that structurally require one - stability
  includes being operable without downtime, not just crash-free.
- **A real production bug, found and fixed**: [[0030-pause-resume-storage-lifecycle]]
  documents a genuine incident (pausing a torrent, then resuming it, threw
  `ClosedChannelException` on the very next write) caught by manual testing, not the
  automated suite - fixed by splitting "stop" from "permanently done" so a pause never
  releases storage a resume would need. Notable in hindsight: the fix at the time was "never
  close storage on pause"; [[0047-bounded-file-handle-pool]] later replaced the entire
  mechanism this bug lived in, and structurally cannot reintroduce it - see below.

## The stability/scale audit

Directly prompted by an explicit ask: the engine should be "very solid, have a high level of
stability," and specifically "handle many torrents at once without spiking resource usage,"
evaluated from a Java-stability standpoint. A focused audit of the actual source (not just
the design docs, which can drift from reality) checked threading model, memory patterns, file
descriptor usage, cleanup on error, timeouts, and existing test coverage against that bar.

**Verdict: the concurrency model itself was sound.** Virtual-thread-per-connection matched
its own design doc, the per-torrent connection cap and timeouts already existed, and cleanup
on error was solid - every exception path in `PeerConnection`'s read loop already routed
through a proper disconnect. The audit found **no live bugs**. What it found were two real,
unbounded-growth *gaps*, plus one doc/reality drift worth closing on principle. Each became
its own design decision:

### Gap 1: no budget on open file descriptors — [[0047-bounded-file-handle-pool]]

`TorrentStorage` opened one `FileChannel` per file at construction and held every one open
for the torrent's *entire* lifetime - closed only on full removal, never on pause. Total open
file descriptors was `torrentCount × filesPerTorrent`, completely unbounded, with the OS fd
limit (often 1024-4096 by default) the wall a real deployment would eventually hit - before
memory or CPU ever became the constraint.

**Fix**: `FileHandlePool`, a shared, engine-wide, bounded LRU cache of open channels.
`TorrentStorage.read()`/`write()` now borrow a channel from the pool for the duration of a
single call rather than owning one outright. Whichever files are actually active stay open;
idle ones get evicted automatically - bounding total fd usage by a configurable size
(`grimtorrenter.max-open-files`, default 256) **regardless of how many torrents exist or
whether they're paused or running.** Guarded by a `ReentrantLock`, not `synchronized`,
following [[0007-concurrency-model]]'s pinning rule from the outset. A path still mid-use is
never force-evicted - the same "small overshoot is acceptable" philosophy `MAX_CONNECTIONS`
already established, reapplied deliberately.

A genuine bonus, not just a side effect: because every file access is now a potential
transparent cache-miss-and-reopen rather than a one-way close, this design **structurally
cannot reintroduce [[0030-pause-resume-storage-lifecycle]]'s old bug** - there's no longer any
such state as "closed with no reopen path" for a hypothetical future code path to stumble
into.

### Gap 2: unbounded concurrent piece verification — [[0048-piece-verification-throttling]]

Verifying a piece (both on a restart's re-hash and on normal download completion) reads the
**whole piece** into one `byte[]` before SHA-1-hashing it - multi-MB, potentially. Nothing
bounded how many of these could happen *at once across the whole engine*: every restoring
torrent re-verifies its entire piece set on its own unthrottled virtual thread, so a process
restart with many torrents on disk fires off that many concurrent full-piece-buffer-plus-hash
bursts simultaneously. Not a leak - a transient but genuine GC/memory spike under load, the
exact "many torrents without spiking resource usage" failure mode named in the original
requirement.

**Fix**: a shared, engine-wide `Semaphore`, acquired around every read-then-verify pair.
Threaded through via the same sibling-overload pattern already established for
`RateLimiters`/`FileHandlePool`, so zero pre-existing callers or tests needed to change.
Defaults to the available processor count (configurable via
`grimtorrenter.max-concurrent-piece-verifications`) - a principled choice, not a guess:
verifying more pieces in parallel than there are CPU cores buys no extra SHA-1 throughput,
only more simultaneous buffers in memory. `acquireUninterruptibly()`, matching this
codebase's existing cooperative (not interruption-based) cancellation style, and held across
*both* the read and the hash, not just the read - releasing early would still leave the
CPU-bound half of the work uncounted.

### Proving both under real load — [[0049-many-torrents-load-test]]

Both fixes were unit-proven in isolation before this - `FileHandlePoolTest` and a dedicated
throttling test - but nothing exercised them *together*, under the actual scenario that
motivated them: many torrents restoring at once, sharing one pool and one limiter, exactly as
production wires them.

**`ManyTorrentsRestoreLoadTest`** restores 40 real `TorrentSession`s concurrently against a
deliberately undersized shared pool (5 file slots) and verification limiter (4 permits) -
sized so both mechanisms are forced to actually evict/throttle, not just coast with headroom
to spare. It proves three things: every torrent still verifies every piece correctly despite
constant real file-handle churn (the most important assertion - a shared-pool bug could
plausibly corrupt data under contention); peak concurrent verification never exceeds the
configured limit, measured *exactly* via a `Semaphore` subclass that instruments the real
production call sites with zero changes to production code; and peak open-file count stays
within budget, measured by best-effort sampling (acknowledged as such - `FileHandlePool`'s
own precise correctness proof stays with its unit tests).

### Closing the last doc/reality gap — [[0050-piece-manager-reentrant-lock]]

`PieceManager`'s bookkeeping (`stateOf`, `markBlockReceived`, `verify`, piece/block
selection) was still `synchronized` - on the per-connection hot path
[[0007-concurrency-model]]'s original care point was written about, even though the audit
found no actual pinning risk here (nothing blocking ever ran while the monitor was held, just
`BitSet` math). Swapped to the same `ReentrantLock` pattern used throughout the fixes above -
mechanical, mostly a documentation-debt fix rather than a live bug fix, but it means the
codebase now has **zero `synchronized` blocks left to explain away** against its own stated
concurrency rule. Reentrancy (`selectNextPiece()` calling back into `stateOf()` on the same
thread) was the one thing that had to be preserved correctly - `ReentrantLock` supports it by
design, the same way `synchronized` did, and existing test coverage already exercises that
exact path (a broken reentrant lock would simply hang those tests).

A small bonus fell out of touching every method body anyway: `verify()`'s SHA-1 hash
computation was moved to *before* acquiring the lock, since it only reads immutable state -
less time holding the lock during exactly the path [[0048-piece-verification-throttling]]
already cares about keeping efficient.

## Stability as a standing consideration, not a one-time audit

[[0051-stability-as-a-standing-consideration]] closes this document out by making the
practice permanent rather than a one-off exercise: every future `design_docs/` entry is now
expected to say something about resource/failure behavior wherever the decision has any -
unbounded growth, a hostile-peer/tracker angle, locking versus
[[0007-concurrency-model]]'s no-`synchronized`-in-the-hot-path rule, cleanup on every exit
path - even if the honest answer is "no stability implication here." `CLAUDE.md` points to it
so it applies to every decision going forward, not only engine-internals work like the audit
above.

## Where this leaves things

Every finding from the audit - unbounded file descriptors, unbounded verification bursts, no
load test to prove either, and the last `synchronized` doc/reality gap - is closed. What's
proven is bounded under the specific adversarial load `ManyTorrentsRestoreLoadTest`
constructs (40 torrents against deliberately small shared budgets); it isn't a claim of
unlimited scale, and there's no continuous/automated stress-testing infrastructure watching
for regressions here beyond that one test. If the engine is later pushed to noticeably larger
torrent counts or peer counts than anything exercised so far, that's the next place to look -
not because a specific gap is already known, but because "provably bounded at N" isn't the
same claim as "provably bounded at 10N."
