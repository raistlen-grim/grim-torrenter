# 0047 — A bounded, shared file-handle pool

**Status:** Accepted

## Decision

Raised by a stability/scale audit of the engine (the user's own framing: "very solid, high
stability, handle many torrents at once without spiking resource usage"). The audit's top
finding: `TorrentStorage` opened one `FileChannel` per file at construction and held every one
open for the torrent's entire lifetime, closed only when the session was fully removed - not
on pause ([[0030-pause-resume-storage-lifecycle]] made that pause/resume-safe on purpose), and
`TorrentEngine` places no cap on how many torrents can exist at once. Put together: total open
file descriptors was `torrentCount × filesPerTorrent`, completely unbounded, with the OS fd
limit (often 1024-4096 by default) the wall a user would eventually hit - well before memory
or CPU became the constraint.

**Fix: a shared, engine-wide, bounded LRU cache of open `FileChannel`s** (`FileHandlePool`,
new class in `grimtorrenter-engine`'s `storage` package) that `TorrentStorage` borrows a
channel from for the duration of a single `read()`/`write()` call, rather than owning one
outright. Whichever files are actually being touched stay open; ones nobody's used recently
get evicted automatically. This bounds total fd usage by the pool's configured size,
**regardless of how many torrents exist or whether they're running or paused** - a materially
different (and better) fix than an alternative considered and rejected: explicitly closing
storage on pause and reopening on resume, which would only have helped *paused* torrents. A
user running many torrents *actively* at once - the actual "many torrents" scenario the user
cares about - would still have blown the fd budget under that approach. The LRU pool handles
both cases uniformly, with no lifecycle hook needed at all.

### `FileHandlePool`: reference-counted LRU cache, `ReentrantLock` not `synchronized`

`acquire(path)`/`release(path)` bracket a single `TorrentStorage.read()`/`write()` call.
Internally: a `LinkedHashMap<Path, Entry>` with `accessOrder=true` (so iteration order is
always LRU-to-MRU) plus a per-entry `refCount`, guarded by one `ReentrantLock` for the whole
class. **Deliberately not `synchronized`** - [[0007-concurrency-model]]'s own stated care
point is that a monitor can pin a parked virtual thread to its carrier, defeating the whole
point of the virtual-thread-per-connection model; `ReentrantLock` doesn't have that failure
mode, so acquiring a lock around an occasional blocking file-open syscall is safe here in a
way `synchronized` around the same call would not have been.

The lock is held across the file open/close syscall itself, not just the map bookkeeping -
kept simple deliberately: file opens are rare once a torrent's working set of files has
already been touched once (most `acquire()` calls thereafter are just a map hit + refcount
bump), and this project isn't chasing the concurrency ceiling a competitive client would need
(the same framing [[0007-concurrency-model]] uses for choosing virtual threads over Netty in
the first place). Moving I/O outside the lock for maximum throughput would be a legitimate
future refinement if profiling ever shows it matters, not something worth the added complexity
up front.

**A path currently in use is never force-evicted.** If every open entry has `refCount > 0`
when the pool is at capacity and a new file needs opening, `evictOneIdleEntryIfAtCapacity()`
is a no-op and the pool briefly exceeds its nominal cap rather than closing a channel out from
under whichever thread is mid-read/write on it. This mirrors an existing precedent in the same
codebase: `TorrentSession.MAX_CONNECTIONS`' own comment already documents "a small overshoot
past MAX_CONNECTIONS is an acceptable imprecision" for exactly the same reason (correctness
and simplicity over a perfectly hard cap).

### This structurally can't reintroduce [[0030-pause-resume-storage-lifecycle]]'s bug

0030 was a real production bug: `stop()` used to close storage unconditionally, and
`TorrentStorage` had "no reopen path once closed," so a paused-then-resumed torrent hit
`ClosedChannelException` on its very next write. The fix at the time was to simply never close
storage on a pause, only on a genuine, permanent teardown.

This change goes further: **every single `read()`/`write()` call now goes through the pool's
acquire-on-demand path**, so there is no longer any such thing as "closed with no reopen path"
- a cache miss just reopens, transparently, whether that's because the file was never opened
yet or because it was evicted under LRU pressure from some other torrent. `TorrentSession.stop()`
still deliberately doesn't evict its own files (no reason to - `resumeTorrent()` will likely
touch them again soon, and evicting on every pause would just cause needless reopen churn),
but even if it did, it would no longer be a correctness bug, only a minor performance one. See
the updated comment on `TorrentSession.stop()` and 0030's own update note below.

### Configuration: `grimtorrenter.max-open-files`, deploy-time, not a live Setting

Default `256`. Deploy-time `@ConfigProperty` (`TorrentEngineProducer`), the same category as
`grimtorrenter.download-directory`/`grimtorrenter.listen-port` per
[[0041-live-settings-store]]'s own split - it's a resource/infrastructure sizing knob tied to
how the engine is constructed, not a user-facing behavioral preference that belongs in the
`/settings` page's grouped UI ([[0045-settings-page]]). 256 leaves comfortable headroom under
a typical default OS `ulimit -n` (often 1024) for everything else also competing for file
descriptors on the same process - peer connection sockets (up to 30 per torrent,
[[0007-concurrency-model]]), the DHT/PeerServer sockets, and the JVM's own baseline usage. A
user who's raised their host/container's ulimit (Docker: `--ulimit nofile=...`) and wants less
cache churn across many simultaneously-active torrents can raise this alongside it - directly
answering the concern that motivated making it configurable at all: a too-small pool doesn't
fail or hang, it just reopens files more often than ideal (a real but bounded cost - a file
open is a microsecond-scale syscall, not something that compounds into a noticeable stall even
under real thrashing), and this is the knob to fix that for someone who has the fd budget to
spare.

## Testing

- `FileHandlePoolTest` (new) - a non-positive capacity is rejected; `acquire()` returns a
  usable channel; reacquiring the same path returns the same open channel (no needless
  reopen); at capacity 1, a second distinct path evicts the first once idle, and the evicted
  path transparently reopens on its next `acquire()` (the exact property pause/resume safety
  depends on); a path still in use is never force-evicted even at capacity, and the pool
  overshoots instead; `unbounded()` never evicts.
- `TorrentStorageTest` gained `writesAndReadsCorrectlyEvenWhenThePoolIsTooSmallToKeepBothFilesOpen`
  - a capacity-1 pool against a two-file torrent, alternating writes/reads between both files
  five times to force real eviction/reopen churn on every call, not just once - proves
  correctness under real cache pressure, not just under a pool sized never to evict anything.

## Alternatives considered

- **Close storage explicitly on pause, reopen on resume** - rejected; only bounds fd usage
  for *paused* torrents, not many torrents *actively* running at once, which is the scenario
  that actually motivated this. Would also have reintroduced a lifecycle-hook-shaped version
  of exactly the bug class [[0030-pause-resume-storage-lifecycle]] fixed, if the reopen path
  were ever missed on some code path - the pool's "every access is a potential cache miss"
  model doesn't have that failure mode at all.
- **A hard cap on total torrent count** - rejected as the primary fix; it would have bounded
  file-related fd usage indirectly (fewer torrents means fewer files) but done nothing for a
  user with few torrents each holding many files, and would need its own UX (what happens at
  the cap - reject, queue, warn?) for a problem the pool already solves directly.
- **Move file I/O outside the pool's lock for maximum throughput** - not built; see
  "`FileHandlePool`: reference-counted LRU cache" above for why holding the lock across the
  occasional open/close is an acceptable, simpler starting point.
