# 0068 — SPA fallback routing for direct navigation/refresh

**Status:** Accepted

## Decision

Fixes a long-standing `TODO.md` item: refreshing the page (or directly navigating to a link)
while on a client-side Angular route - e.g. `/torrents/<infoHash>` - 404'd, because
[[0010-frontend-integration-and-docker-build]]'s static-resource serving only knows about real
files under `META-INF/resources`; it has no notion of Angular's own client-side router deciding
what `/torrents/<infoHash>` means. Standard SPA-hosting problem, standard fix: serve
`index.html` for any GET request nothing else already claimed, and let the frontend's router
take it from there once it loads.

## Not a JAX-RS catch-all resource

The obvious-looking approach - a `@Path("{path:.*}")` JAX-RS resource - was rejected after
tracing `AuthenticationFilter` ([[0061-authentication]]): it's a `@PreMatching`
`ContainerRequestFilter`, which runs for *every* request RESTEasy Reactive dispatches into,
before resource matching even happens - including a hypothetical catch-all resource, matched or
not. With `authEnabled` on, a plain browser page refresh (no `Authorization` header - this is a
navigation, not an authenticated XHR) would then get a **401**, not the intended fallback -
worse than the bug being fixed, and specific to exactly the scenario (a fresh page load) this
feature exists for.

**Registered directly on the Vert.x `Router`** instead (`SpaFallbackRoute`, an
`@ApplicationScoped` bean observing `Router` at startup, same `@Observes`-on-a-CDI-event idiom
`TorrentEngineLifecycle` already uses for `StartupEvent`/`ShutdownEvent`) - this runs entirely
outside RESTEasy Reactive's own dispatch pipeline, the same way Quarkus's built-in static-file
handler already does (which is why static assets were never auth-gated either). `.last()` means
the handler only ever fires for a GET request nothing else - the static handler, or a real
`/api`/`/ws` route - already claimed. `/api`/`/ws` paths are still explicitly excluded inside
the handler too, defensively: a genuinely missing API route should keep 404ing, not silently
return the app shell.

## Read once, tolerate absence

`index.html`'s bytes are read once, at `Router`-registration time (Quarkus startup), not per
request - it's a fixed build artifact, no reason to re-read the classpath on every fallback hit.

**If it's absent, the fallback route is simply never registered - not a startup failure.**
`META-INF/resources` is only ever populated at Docker build time
([[0010-frontend-integration-and-docker-build]]'s own `COPY --from=frontend-build`), *before*
`mvn package` runs inside that build stage - a plain local `mvn test`/`quarkus:dev` run (this
project's normal manual-build workflow, per this repo's own working conventions) has no such
file unless the frontend was separately built and copied in by hand. Failing every
`@QuarkusTest` in the module over a file no local run would ever have is a worse trade than
silently skipping a feature nothing local was exercising anyway.

## Stability ([[0051-stability-as-a-standing-consideration]])

- **No new unbounded growth**: `indexHtml`'s bytes are held once, for the life of the
  application - the same size class as the frontend bundle itself, not a per-request or
  per-connection allocation.
- **No hostile-peer/tracker angle**: this serves a fixed local file to the browser; no peer or
  tracker input reaches this path at all.
- **No new locking/concurrency concern**: the byte array is read once at startup and never
  mutated - safe to share across every request's handler invocation with no synchronization.

## Alternatives considered

- **A JAX-RS catch-all resource** - rejected; see the `AuthenticationFilter` interaction above.
- **Reading `index.html` per request** - rejected; it's a fixed build artifact with no reason to
  re-read the classpath on every fallback hit, and the startup-time read already needs the same
  absence-handling logic either way.
- **Extension-sniffing** (only fall back for paths with no file extension, 404 everything else)
  - rejected; the standard, widely-used SPA convention (e.g. nginx's own `try_files $uri
  /index.html`) falls back for *any* unmatched GET, accepting that a genuinely mistyped asset
  path returns the app shell instead of a 404 as a known, harmless trade-off - not worth the
  extra logic to avoid.
