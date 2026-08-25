# 0021 — Engine logging and error visibility

**Status:** Accepted

## Decision

**Bug found via manual testing**: uploading a real `.torrent` file
immediately put the session in `ERROR` state with no visible cause
anywhere - `TorrentSession.start()` already captured the failure in
`lastError`, but nothing logged it server-side, and `TorrentView.lastError`
was never rendered in the frontend. The failure was real and captured,
just invisible on both ends.

**Fixed on both sides:**
- `TorrentSession` now logs via `java.lang.System.Logger` (the JDK's
  built-in logging facade, JEP 264) at the points where it previously
  swallowed exceptions silently: initial announce failure in `start()`
  (WARNING), `fail(cause)` (WARNING), and re-announce failures in
  `reannounce()` (DEBUG, since those are expected/transient and
  shouldn't be noisy). State transitions themselves are logged at INFO
  in `setState()`.
- **`System.Logger`, not a logging framework dependency** - deliberate,
  to keep `grimtorrenter-engine` Quarkus-free per
  [[0005-module-structure]]. It's a JDK-native facade; Quarkus's default
  JBoss LogManager setup backs `java.util.logging`, which `System.Logger`
  delegates to absent another provider, so these messages should surface
  in the normal Quarkus console without any bridging dependency. Not
  independently verified by running the app - worth confirming log lines
  actually appear.
- **Frontend**: `torrent-list.html` now renders `torrent.lastError`
  (small red text under the state tag) whenever it's non-null, instead of
  the DTO field going completely unused.

This establishes the logging convention for the rest of the engine
module going forward: `System.Logger`, not SLF4J/JBoss Logging/etc. -
`grimtorrenter-app` can use Quarkus's own `io.quarkus.logging.Log` freely
since that layer already depends on Quarkus.

## Alternatives considered

- **Adding SLF4J or JBoss Logging to `grimtorrenter-engine`** - rejected;
  would violate the module's zero-Quarkus-dependency boundary for a need
  `System.Logger` already covers with no new dependency.
