# 0100 - App clean-up: shared beans, one mapper class, the REST contract written down

Status: Accepted (2026-10-18). Implements the C items of the pre-release app review
(`code_review/02-app.md`). Applies [[0051-stability-as-a-standing-consideration]] and
[[0071-thin-frontend-as-a-standing-consideration]].

No REST, WebSocket, config-property or on-disk changes. Same responses, same files.

## 1. `AppDirectories`: one declaration per directory property

`grimtorrenter.download-directory`, `config-directory` and `watch-directory` were declared,
with their default strings, in six classes. If one default had changed and another hadn't, the
app would have quietly read two different directories.

`AppDirectories` (an `@ApplicationScoped` bean) now declares each one once. It exposes
`downloads()`, `config()` and `watch()`. `TorrentEngineProducer`, `HealthService`,
`SystemResource`, `JsonSettingsStore`, `JsonAuthStore` and `JsonLinesEventStore` inject it. Plain
JUnit tests build one with `AppDirectories.of(...)`.

**Why not `@ConfigMapping`**, which the review suggested? A mapping over the `grimtorrenter`
prefix would have to cover every `grimtorrenter.*` property: listen port, file and
verification limits, allowed origins, bootstrap password. Those are each read in exactly one
place, so they have no duplication to remove. Moving them would also change how every
hand-built test sets config. Only the directories were duplicated, so only they moved.

## 2. Free space computed once

`SystemResource.diskUsage()` and `HealthService`'s free-space check each created the downloads
directory and read its file store. `AppDirectories.downloadsUsableSpace()` now does that once.
The Health page and `/api/system/disk-usage` can no longer report different figures.

## 3. `WebSocketBroadcaster` instead of static state on the endpoint

`TorrentWebSocket` held the open connections in a static map. Three other beans
(`TorrentEventListener`, `TorrentSnapshotScheduler`, `JsonLinesEventStore`) called its static
`broadcast()`. That was hidden coupling, and every test that touched any of them shared the
same global set.

`WebSocketBroadcaster` is now an `@ApplicationScoped` bean. It owns the connection-to-token map,
`broadcast()`, the send timeout and drop-on-failure behaviour ([[0094-session-revocation-error-shape-and-api-consistency]]),
and closing connections whose token fails. `TorrentWebSocket` only checks the handshake
(Origin, then token) and registers or unregisters the connection. The three broadcasters inject
the bean.

[[0019-rest-and-websocket-layer]] made the map static so it wouldn't matter whether
quarkus-websockets-next creates one endpoint instance or one per connection. An application-scoped
bean gives the same guarantee, because there is exactly one, without the static.

## 4. One exception-mapper class

The four engine exception mappers (`Bencode`, `Metainfo`, `MagnetLink`, `TorrentEngine`) were
four near-identical classes. They are now one class, `EngineExceptionMappers`, with one
`@ServerExceptionMapper` method per type.

Each method still matches only its exact exception type. That keeps the protection 0019 found
it needed: a broad `RuntimeException` mapper had swallowed JAX-RS's own
`NotFoundException`/`BadRequestException`. `WebApplicationExceptionMapper` stays separate,
because its pass-through-or-wrap logic is different.

## 5. One read-or-seed for the JSON stores

`JsonSettingsStore` and `JsonAuthStore` had the same "create the directory, read the file or
write and return the default" code. It now lives once in `AtomicJsonFile.readOrSeed()`, beside
the atomic `write()` from [[0093-cross-site-protection-and-failure-isolation]]. The behaviour is
unchanged: a file that exists but can't be read still fails startup, on purpose (0093).

The review's suggested `JsonFileStore<T>` base class wasn't needed. With loading and saving
shared, what's left in each store is its own: the settings store's live `update()`, and the auth
store's bootstrap seed, hash checks and owner-only permissions.

## 6. The DTO policy, decided

Some endpoints wrap engine records in app-side views (`TorrentView`, `DhtStatusView`,
`ServiceStatusView`, ...). Others return engine types as they are. Each choice was deliberate
([[0045-settings-page]] and the per-feature docs), but the contract had no single place to read.

**The rule from here on:**
- **Engine records that go straight onto the wire are public API.** Renaming or removing a
  component of one is a breaking REST change, and the frontend's `models/*.ts` must change with
  it. Today that set is:
  - `Settings` (`/api/settings`);
  - `SeedingLimitOverride` and `TorrentLimitOverride` (per-torrent limits);
  - `Blocklist.Status`, `TorrentEngine.ProxyStatus` and `Socks5.TestResult`;
  - `Label` (`/api/labels`, and the `labels` WebSocket message);
  - `LibraryEvent` (`/api/events`, and the `event` WebSocket message).
- **New endpoints default to an app-side view.** Return an engine record directly only when it
  is a plain value with nothing internal in it, and add it to the list above.
- Wrapping today's set would change no JSON and only add copies, so it isn't done.
  Generated API types (frontend TECH_DEBT) would replace the list with something checked.

## 7. Comments

These stale or historical comments were fixed:
- `application.properties`'s "Placeholder" header;
- `TorrentEngineProducer`'s "currently just the two DHT marker files" (the config directory holds
  every per-torrent marker);
- `SystemResource.diskUsage()`'s bug-history note.

The other stale comments the review listed (`TorrentWebSocket`'s "Phase 1" and "not yet
confirmed", `SessionTokenStore`'s PROGRESS.md flake) had already gone in the A and B work. The
module-wide comment pass stays in TECH_DEBT, as for the engine ([[0099-engine-shared-helpers-and-hot-path-counters]]).

`SessionTokenStore`'s second job, login throttling, was split out to `LoginThrottle` in the B
work (0094).

## Stability

- **No new threads, locks or retained state.**
  - The broadcaster holds the same connection map, moved from a static field to a singleton
    bean. Its lifetime is the same, because the application scope lives as long as the static
    did.
  - `AppDirectories` is three strings.
- **Cleanup on every exit path is unchanged.**
  - The broadcaster keeps 0094's drop-and-close on a failed or timed-out send.
  - `readOrSeed` writes through the same atomic `write()`, which deletes its temp file in
    `finally`.
- **Failure behaviour is unchanged**: a corrupt settings.json or auth.json still refuses to start
  (0093); the event store still never throws into the engine.
- **Hostile input**: no new surface. The handshake checks are the same code, in the same order.
