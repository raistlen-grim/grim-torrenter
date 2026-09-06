# 0022 — Multi-tracker fallback

**Status:** Accepted, confirmed working against a real torrent. Superseded in part by this
doc's own 2026-09-06 revision below: `MultiTrackerClient` no longer implements BEP 12 tier
fallback (tiers tried in order, stopping at the first success) - it announces to every
configured tracker concurrently on every call and aggregates whatever succeeds. The original
motivation this doc describes (a client that can't tolerate any dead tracker shouldn't fail
the whole torrent) is unchanged and still exactly what the revision preserves; what changed
is that reaching only one tracker per announce turned out to be a real peer-count bottleneck
of its own, not just a UI-labeling curiosity.

## Decision

**Found via manual testing, not code review**: a real-world torrent
uploaded through the UI immediately went to `ERROR`. Tracing it down
(see [[0021-engine-logging-and-error-visibility]] for the logging that
made this diagnosable at all, and [[0013-http-tracker-client]] for the
User-Agent red herring along the way) led to the real cause:
`TorrentEngine` picked exactly one tracker URL from the torrent file and
gave up entirely if that one tracker failed. Real-world torrents
routinely list dozens of trackers, many of them dead or blocking
requests — the specific torrent tested had ~40 trackers, only 2 of them
HTTP(S) (the rest UDP, unsupported until Phase 2), and *both* HTTP
trackers turned out to be non-functional. A desktop client (qBittorrent)
completed the same torrent successfully via a UDP tracker
(`udp://exodus.desync.com:6969/announce`) — proving the torrent itself
was fine and the gap was purely "no fallback when the one tracker we
picked doesn't work."

**`MultiTrackerClient`** (`tracker` package) implements BEP 12 tier
fallback: tiers are tried in order; within a tier, trackers are tried in
order until one succeeds; a tier only counts as failed once *every*
tracker in it has failed. `TorrentEngine.selectTrackerTiers` builds the
tier structure from the torrent's metadata and wraps it in a
`MultiTrackerClient`, replacing the old `selectTrackerUrl` (single URL).

**Corrected an inaccuracy in the original tracker-selection logic along
the way**: the old `selectTrackerUrl` preferred the classic `announce`
field over `announce-list`. That's backwards per BEP 12 — a
spec-compliant torrent file already includes `announce` redundantly as
part of `announce-list` (for old clients that don't understand
`announce-list`), so a modern client should prefer `announce-list` when
present and only fall back to bare `announce` when `announce-list` is
absent entirely. `selectTrackerTiers` fixes this.

**Deliberately not implemented** (see `MultiTrackerClient`'s Javadoc):
shuffling trackers within a tier, and promoting a working tracker to the
front of its tier for subsequent announces. Both are real BEP 12
refinements, but they're swarm-politeness/latency optimizations, not
needed to fix the actual problem (a client that can't tolerate any dead
tracker at all). Can be added later without changing the interface.

## Testing

`MultiTrackerClientTest` covers: first tracker in a tier succeeding,
falling back within a tier, falling back across tiers, all trackers
failing (propagates the last failure), and rejecting an all-empty tier
list. `TorrentEngineTest`'s tracker-selection tests were rewritten against
`selectTrackerTiers` instead of the removed `selectTrackerUrl`.

Confirmed working end-to-end against a real Ubuntu (Linux ISO) torrent
file after this fix - the earlier real-world torrent's failure was
correctly diagnosed as "needs Phase 2's UDP tracker support," not a bug
in this fallback logic.

## Alternatives considered

- **Track dead trackers and skip them on subsequent re-announces** - not
  built; `MultiTrackerClient` re-tries every tracker in tier order on
  every single announce call. For Phase 1's re-announce cadence (every
  30+ minutes per [[0017-torrent-session]]), the wasted time re-trying a
  known-dead tracker before falling through is negligible.

## Revision (2026-09-06): concurrent announce to every tracker, not tier fallback

**Root-caused via a real side-by-side comparison against qBittorrent** (see `TODO.md`'s own
matching Performance entry for the full investigation): a real public torrent's Trackers tab
showed exactly one tracker as `WORKING`, every other declared tracker (20 of them, one per
tier) permanently stuck at `UNKNOWN`/"Not yet announced" - the direct, by-design consequence
of this doc's own strict BEP 12 tier fallback: `MultiTrackerClient.announce()` returned as
soon as the first tracker succeeded, never touching the rest as long as that one kept
working. A qBittorrent trackers-tab screenshot of the *same* torrent settled the question of
whether this actually mattered: tiers 0, 2, 3, 4, 6, 7, 8, and 12 were all independently
`Working`, each with its own distinct, non-duplicate seed/peer/leech count (e.g. 397/230/174
for `tracker.renfei.net` vs. 200/309/139 for the one tracker GrimTorrenter was reaching) -
proof that real clients don't limit themselves to one tracker's slice of the swarm, and that
reaching more trackers here would add genuinely new peers, not just redundant duplicates of
the same ones.

**The fix**: `MultiTrackerClient.announce()` now flattens every tier into one list at
construction time (tier is still recorded per-tracker via `TrackedTrackerClient`, for
`TrackerStatus`/UI display - it just no longer confers any priority/fallback behavior inside
this class) and, on every call, announces to all of them concurrently
(`Executors.newVirtualThreadPerTaskExecutor()` + `invokeAll()`, the same virtual-thread-per-
task pattern `TorrentEngine.raceOneRound()` already established for magnet peer racing - see
[[0007-concurrency-model]], [[0028-magnet-links-and-dht]]'s own addendum). Bounded by the
slowest single tracker's own timeout (each `TrackerClient` implementation already has one -
`HttpTrackerClient`'s connect timeout, `UdpTrackerClient`'s retry/timeout policy), not the
sum of every tracker's - a latency improvement over the old sequential-tiers behavior, not
just a peer-count one. Peers from every tracker that succeeds are unioned into one
deduplicated set (`PeerAddress` is a record, so two trackers reporting the same peer collapse
to one entry automatically); the returned interval is the *minimum* among the successes, so
the shared reannounce cycle stays at least as responsive as the most demanding tracker asks
for. Only throws if every tracker fails, same "last failure wins" reporting as before.

**Deliberately kept the shared single-announce-cycle model** rather than giving each tracker
its own independently-timed schedule (confirmed with the user, choosing between the two):
a tracker with a longer stated interval than the group's minimum now gets polled somewhat
more often than it asked for - a soft politeness cost, not a correctness one - in exchange
for a much smaller, more contained change. `TorrentSession`'s own interaction with
`trackerClient` (a synchronous `announce()` call at `start()`/`reannounce()`/`stop()`) is
completely unaffected; only this class's internals changed. Per-tracker independent
scheduling (the same architectural shape `discoverPeersViaDht()` already uses for DHT, see
[[0036-dht-backstop-for-tracker-bearing-torrents]]'s own 2026-09-06 revision) was considered
and explicitly deferred as a larger, riskier follow-up, not ruled out.

### Stability ([[0051-stability-as-a-standing-consideration]])

- **No new unbounded growth**: the flattened tracker list is fixed at construction time (same
  trackers as before, just no longer nested by tier for iteration purposes); the per-call
  `ExecutorService` is a fresh, short-lived, try-with-resources-scoped one, not a
  long-lived pool that could accumulate state.
- **No new concurrency pattern** beyond what `[[0007-concurrency-model]]`/
  `TorrentEngine.raceOneRound()` already established (virtual-thread-per-task, awaited
  synchronously via `invokeAll()`) - this is a smaller-scale reuse of that same shape, not a
  new one.
- **Hostile-tracker angle**: querying every configured tracker concurrently, every cycle,
  means a torrent with many declared trackers now generates more real network requests per
  announce than the old short-circuiting behavior did (up to N concurrent requests instead
  of 1, where N is the tracker count) - accepted as the direct, intended cost of actually
  reaching every tracker; each individual `TrackerClient`'s own existing timeout still bounds
  a single misbehaving/slow tracker's impact on the overall call.
- **Cleanup unaffected**: the per-call executor is closed (try-with-resources) once
  `invokeAll()` returns, regardless of how many individual tasks succeeded or failed.

### Testing

`MultiTrackerClientTest` was substantially rewritten for the new semantics:
`announcesToEveryTrackerConcurrentlyRegardlessOfTierOrSuccess` (every tracker across every
tier gets called, not just the first reachable one), `aggregatesDistinctPeersFromEveryTrackerThatSucceeds`,
`deduplicatesTheSamePeerReportedByMultipleTrackers`, `usesTheMinimumIntervalAmongSuccessfulTrackers`,
`ignoresFailingTrackersAndUsesWhateverSucceeded` (a failing tracker doesn't block using what
the others returned - this doc's original motivation, still covered), `throwsWhenEveryTrackerFails`
(renamed from `throwsLastFailureWhenAllTrackersFail`, same behavior), and
`statusesAggregatesEveryWrappedTrackerAcrossTiers` (updated: both trackers now show `WORKING`,
not one `WORKING`/one perpetually `UNKNOWN`). The old tier-fallback-order tests
(`usesFirstTrackerInFirstTierWhenItSucceeds`, `fallsBackWithinATierWhenFirstTrackerFails`,
`fallsBackToNextTierWhenEntireFirstTierFails`) were removed - they tested behavior this
revision deliberately removes. No other test file depended on `MultiTrackerClient`'s
internals beyond its public `TrackerClient` contract (confirmed by grep before starting), so
nothing elsewhere needed changes.
