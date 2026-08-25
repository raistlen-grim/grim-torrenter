# 0041 — Live settings store

**Status:** Accepted

## Decision

Raised ahead of rate limiting (Phase 3's next item, [[0009-phased-scope]] - now built, see
[[0042-rate-limiting]]): rate limits are
exactly the kind of thing a user wants to change without a container restart, and
Quarkus's `@ConfigProperty`/`application.properties` has no story for writing itself back
out at runtime - it's deploy-time config, not a place for the app to persist a change a
user makes from the UI. This introduces a separate, user-editable, **live** settings
mechanism alongside it, and migrates the two existing runtime-ish booleans
(`dhtEnabled`, `acceptIncomingConnections`) onto it as the first real fields - rate
limiting adds its own fields to the same `Settings` record when it's built next.

**Deploy-time config (download directory, listen port) stays in
`application.properties`/`@ConfigProperty`** - unaffected, not migrated. Those have real
structural implications if changed at runtime (existing torrents reference the download
directory they were added under; the listen port is bound once at `DhtNode`/`PeerServer`
construction) that are out of scope here.

### The interface: `SettingsStore` (`grimtorrenter-engine`, new `settings` package)

The user's explicit design constraint: **the store is the only thing that ever holds a
`Settings` instance** - every consumer, in either module, reads through
`SettingsStore.current()` rather than caching its own copy or reading the backing file
directly, so nobody can act on a stale value. `update(Settings)` writes to disk **before**
swapping the in-memory value, not after - a crash between the two can then never leave the
in-memory view claiming a change that isn't actually durable yet ("survive catastrophic
failure," the user's own framing).

`Settings` itself (record: `dhtEnabled`, `acceptIncomingConnections`, `static defaults()`)
and the `SettingsStore` interface both live in `grimtorrenter-engine` - deliberately, even
though nothing in the engine reads live settings yet, because rate limiting (the very next
task, not a speculative future one) will need `TorrentSession` to read live values from
inside the engine, and this is the seam it'll plug into. The concrete implementation
(`JsonSettingsStore`, JSON-file-backed via the `ObjectMapper` CDI bean
`quarkus-rest-jackson` already provides) lives in `grimtorrenter-app`, `@ApplicationScoped`
- CDI's own singleton guarantee is what actually enforces "the store is the only holder,"
not just convention. `grimtorrenter-engine` itself gains no new dependency (no JSON
library) - `Settings` is a plain record; Jackson serializes it via reflection from the app
side without needing any annotations on the record itself.

**Explicit, acknowledged exception to "live" for these two specific fields**: `dhtEnabled`/
`acceptIncomingConnections` still require a restart to actually take effect, even though
they save instantly. `DhtNode`/`PeerServer` are each created once, at `TorrentEngine`
construction (`TorrentEngineProducer.torrentEngine()`, read via `settingsStore.current()`
at that one point), and neither supports being started or stopped afterward - teaching
`TorrentEngine` to do that dynamically is a separate, larger engine-lifecycle change,
confirmed out of scope for this step. The user explicitly signed off on this as an
acceptable exception ("exceptions can be made for the settings [that] require a service
[re]start") rather than something to silently gloss over - it's called out in both
`Settings`' and `TorrentEngineProducer`'s own Javadoc, not hidden.

### Storage: a separate, independently-mountable config directory

New `grimtorrenter.config-directory` `@ConfigProperty` (default `config`), deliberately
**separate** from `grimtorrenter.download-directory` - matching how other self-hosted
Docker tools typically split config from data so each can be bind-mounted to its own host
path. `settings.json` lives there, created with defaults on first run if missing (same
"create if absent, don't fail startup over it" spirit as `DhtNode`'s own persisted node-id
marker, [[0028-magnet-links-and-dht]]).

The Dockerfile now documents both directories and the listen port explicitly (`EXPOSE
6881/tcp` and `EXPOSE 6881/udp`, previously not exposed at all) - the user's own
requirement that as long as the port is documented, it can be remapped to anything on the
host side.

## Testing

- `JsonSettingsStoreTest` (new, plain JUnit - no container needed to exercise pure
  read/write/default-creation logic): missing file creates and persists `Settings.defaults()`;
  an existing file loads rather than being overwritten; `update()` persists to disk *and*
  updates `current()`; a missing config directory is created automatically.
- `TestSettingsResource` (new `QuarkusTestResourceLifecycleManager`, mirroring
  `CleanDownloadsResource`'s "must run before app boot" rationale) seeds a
  `dhtEnabled=false`/`acceptIncomingConnections=false` `settings.json` before either
  `@QuarkusTest` class boots - replaces the old `application.properties`-based
  `dht-enabled=false`/`accept-incoming-connections=false` overrides, which no longer exist
  as plain config properties. Applied to both `TorrentResourceTest` (alongside its existing
  `CleanDownloadsResource`) and `DhtResourceTest` (which previously relied on
  `application.properties` alone and needed no resource at all) - keeping the whole test
  suite exactly as hermetic as before, just sourced from the new mechanism.

## Alternatives considered

- **Keep `dhtEnabled`/`acceptIncomingConnections` on `@ConfigProperty`, put only future
  rate-limit fields in the new store** - rejected; the user explicitly wanted existing
  user-configurable toggles migrated now as part of proving the framework, not just new
  fields added to it later.
- **`Settings.defaults()` = `false`/`false`** to sidestep needing a test-seeding resource
  at all - rejected; a real deployment's sensible out-of-the-box default is DHT and
  incoming connections both *on*, and picking an unsafe-for-tests default just to avoid
  writing `TestSettingsResource` would be optimizing the product default around test
  convenience, backwards priorities.
- **`SettingsStore.current()` re-reading the file on every call** instead of an in-memory
  field - rejected; the user's own requirement was an in-memory instance as the primary
  read path with disk as the durability backstop, not disk as the read path itself
  (needlessly slow for something a rate limiter would check on every send/receive once
  that's built).
