# 0019 — REST/WebSocket layer (grimtorrenter-app)

**Status:** Accepted, compiles and passes tests on Quarkus 3.38.2 / Java 25

## Decision

**REST API** (`TorrentResource`, `/api/torrents`): `GET` (list),
`GET /{infoHash}` (detail), `POST` multipart upload (add + auto-start),
`DELETE /{infoHash}` (remove), `POST /{infoHash}/pause`,
`POST /{infoHash}/resume`. `TorrentView` is the JSON DTO, built from
`TorrentSession`'s public getters via `TorrentView.from(session)`.

**Three narrow `ExceptionMapper`s** (`BencodeExceptionMapper`,
`MetainfoExceptionMapper`, `TorrentEngineExceptionMapper`, sharing a
`ErrorResponses.badRequest(String)` helper) map each specific engine
exception type to 400 (bad user input) instead of the JAX-RS default 500.
**Originally this was one `ExceptionMapper<RuntimeException>`** - wrong,
and caught in testing: `NotFoundException`/`BadRequestException` are
themselves `RuntimeException`s, so JAX-RS routed them to that broad
mapper too, and its fallback branch turned them into 500s - silently
breaking the built-in 404/400 handling `TorrentResource` was relying on.
Registering per-exact-type mappers instead means JAX-RS only routes to
them for those specific thrown types, leaving `NotFoundException`/
`BadRequestException` to their own built-in handling untouched.

**WebSocket push is the hybrid model discussed with the user**:
`TorrentEventListener` (a `TorrentSessionListener` registered on every
session via `TorrentEngine`) pushes immediately on `onStateChanged` only -
`onPieceCompleted` is intentionally a no-op, to avoid a large torrent's
rapid piece completions flooding the socket. `TorrentSnapshotScheduler`
(`@Scheduled(every = "2s")`) covers ongoing progress via a periodic full
snapshot of `TorrentEngine.listTorrents()`. Both funnel through
`TorrentWebSocket.broadcast(String)`.

**`TorrentWebSocket` tracks its own connection registry** (a static
`ConcurrentHashMap.newKeySet()` populated/cleared in `@OnOpen`/`@OnClose`)
rather than relying on a framework-provided cross-connection broadcast
helper - this sidesteps needing to know whether `quarkus-websockets-next`
instantiates one shared endpoint instance or one per connection, and
works correctly either way since the registry is `static`.

**CDI wiring avoids a circular dependency deliberately**: `TorrentEngine`
is built by `TorrentEngineProducer`, which injects `TorrentEventListener`
to pass as the constructor's `TorrentSessionListener`. If
`TorrentEventListener` itself needed a `TorrentEngine` reference (e.g. for
the periodic snapshot), that would be circular. Instead, the periodic
snapshot's `TorrentEngine` dependency lives on the separate
`TorrentSnapshotScheduler` bean, which has no role in constructing the
engine. `TorrentEngineLifecycle` (observes `ShutdownEvent`, calls
`torrentEngine.shutdown()`) is also its own small bean for the same
reason - keeping "how the engine gets built" and "what reacts to its
lifecycle" as separate concerns.

**Config**: `grimtorrenter.download-directory` (default `downloads`) and
`grimtorrenter.listen-port` (default `6881`) via `@ConfigProperty`,
consumed only by `TorrentEngineProducer`.

**New dependency**: `quarkus-scheduler`, for `@Scheduled` - the
Quarkus-idiomatic choice for in-app periodic tasks, consistent with using
framework facilities in this layer specifically (unlike the engine module,
which deliberately has zero Quarkus dependency per
[[0005-module-structure]]). Also added `rest-assured` (test scope) for
`TorrentResourceTest`.

## Verification history

- **Quarkus platform version**: originally pinned at 3.15.1, which
  predates Java 25 - its build-time ASM tooling couldn't read Java 25
  bytecode (`Unsupported class file major version 69`). Resolved by
  upgrading to 3.38.2 (latest stable as of 2026-08-22; 3.31 was the first
  to add Java 25 support, 3.33 the first LTS line with full support, but
  3.33.0 was no longer resolvable by the time of this build).
- **`quarkus-websockets-next` and RESTEasy Reactive multipart APIs**
  (`@WebSocket`/`@OnOpen`/`@OnClose`/`WebSocketConnection.sendTextAndAwait`,
  `@RestForm`/`FileUpload.uploadedFile()`) - compiled successfully with no
  changes needed, so the method/annotation names guessed in
  `TorrentWebSocket`/`TorrentUploadForm` were correct for this Quarkus
  version. Note this confirms *compilation*, not runtime behavior - no
  test currently opens a real WebSocket connection to exercise
  `TorrentWebSocket`'s broadcast path end-to-end.
- **`@Scheduled` running on a worker thread** (assumed safe to block on
  in `TorrentSnapshotScheduler`) - **this assumption turned out wrong in
  practice.** `TorrentWebSocket.broadcast()` originally used
  `sendTextAndAwait` (blocking). During real frontend development, `ng
  serve`'s live-reload repeatedly abandoned WebSocket connections without
  a clean close handshake (`@OnClose` never fires for those), and Quarkus
  observed `Worker thread pool exhaustion` shortly after. Root cause: a
  blocking send against a connection that never responds ties up the
  calling thread indefinitely, and `@Scheduled`'s default
  `ConcurrentExecution.PROCEED` lets the next 2s tick start a new
  invocation - and consume another worker thread - regardless of whether
  the previous one is still stuck, so stuck threads accumulate one per
  tick against the same dead connection(s) until the pool is exhausted.
  Fixed two ways: `broadcast()` now uses `sendText` (non-blocking, returns
  `Uni<Void>`) with a bounded timeout and removes any connection that
  fails or times out, so one bad connection costs a few seconds once
  rather than a thread forever; `@Scheduled` was also switched to
  `ConcurrentExecution.SKIP` as defense in depth, so overlapping ticks
  can't pile up regardless of what else might block in the future. See
  [[0031-torrent-detail-endpoints]] for the download-rate work underway
  when this surfaced (unrelated cause, just concurrent timing).
- **The `ExceptionMapper<RuntimeException>` bug** described above was
  caught by `TorrentResourceTest`'s 404/400 assertions actually failing
  (500 instead) - exactly the kind of thing that test was there to catch.

## Alternatives considered

- **Pure event-driven or pure periodic-snapshot WS push** - both
  discussed with the user and rejected in favor of the hybrid.
- **`OpenConnections`-style framework broadcast helper** instead of a
  self-managed registry - avoided due to the API uncertainty noted above;
  the self-managed `Set<WebSocketConnection>` only relies on parts of the
  API already being used elsewhere in this file (`@OnOpen`/`@OnClose`
  parameter injection), reducing the surface that could be wrong.
