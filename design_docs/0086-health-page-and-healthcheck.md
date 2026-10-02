# 0086 - Health page and container health check

Status: Accepted (2026-10-02). Grows [[0059-service-status]]'s Services page into a Health page.
Prompted by [[0085-container-user-and-portable-compose]]: the entrypoint's "directory not
writable" warning only reached people who read container logs.

## Problem

The Services page watched three things (DHT, the peer server, LSD). The problems a new user is
most likely to hit were not visible anywhere in the UI: a mounted directory the app can't write
to, a listen port that isn't forwarded, a proxy that is down, a blocklist that failed to load,
authentication left off, or simply which build is running. And the container had no
`HEALTHCHECK`.

## Decision

### One report, grouped

`GET /api/system/health` returns `{ status, groups: [{ name, checks: [{ name, state, message }] }] }`.
Names are stable identifiers; `message` is finished text written by the backend, so a client
only maps names to labels/icons and renders ([[0071-thin-frontend-as-a-standing-consideration]]).
`status` is the worst state present.

States: `OK`, `INFO` (a fact, neither good nor bad), `WARNING` (working, worth a look), `FAILED`,
`DISABLED`.

| Group | Check | Rule |
|---|---|---|
| network | `dht`, `peerServer`, `lsd` | The engine's existing `serviceStatuses()`: RUNNING -> OK, DEGRADED -> WARNING, DISABLED (with its reason, e.g. the proxy), FAILED. |
| storage | `downloads`, `config` | Created if missing, then: is it a directory this process can write to. FAILED with the path and the fix (host ownership or PUID/PGID) if not. |
| storage | `watch` | Same check when the watch folder is on; DISABLED when it is off. |
| storage | `freeSpace` | WARNING under 1 GiB free on the downloads volume, else OK with the figure. |
| connectivity | `incoming` | OK once any inbound peer connection has reached us since start (the count is shown). INFO, with a hint about port forwarding, while none has. DISABLED when not listening. |
| connectivity | `proxy` | DISABLED with no proxy. Otherwise the result of the existing proxy test: OK, WARNING if it doesn't relay UDP, FAILED if unreachable. |
| protection | `blocklist` | DISABLED / OK with the range count / WARNING when a refresh failed but the last good list is still enforced / FAILED when enabled with nothing loaded. |
| protection | `auth` | OK when a password is required. **INFO, not a warning, when it isn't** (confirmed with the user) - off is the default and fine on a private network; the message says to turn it on before exposing the app. |
| build | `version`, `uptime`, `user` | All INFO: the build version ([[0084-client-identification]]), time since start, and the uid/gid the process runs as. |

`incoming` is evidence, not a probe. Nothing inside the container can test the port from
outside; a peer having connected proves it is open, while none yet proves nothing (a quiet swarm
looks the same). Hence INFO rather than a failure. The engine gains one counter,
`TorrentEngine.incomingConnectionsSeen()`, bumped when an inbound TCP or uTP connection names a
torrent.

### Where it is computed

A new app-layer `HealthService`. The directories are deploy-time config the engine doesn't own,
and auth is an app concern, so the report belongs in the app module; the engine contributes its
existing status calls and the one counter.

### Never waits on the network

Every check reads cheap local state except the proxy test, which can block for a couple of
connect timeouts. It runs on a background virtual thread, at most one at a time, and its result
is cached for 5 minutes (keyed by host:port, so changing the proxy re-tests). A request shows
the cached result, or "Checking..." the first time.

### Failures also go to the event log

A storage check that starts failing records one `STORAGE_UNWRITABLE` library event (new type,
engine-wide, the path and fix in the message) - once per occurrence, not per poll. The report is
also evaluated once at startup so this happens without anyone opening the page.

### The page

The Services page becomes **Health** (route `/health`; `/services` redirects). Same row design,
now under five group headings, each row showing the backend's message. The sidebar item is
renamed, and its badge counts `FAILED` checks across all groups.

Tones stay within the style guide's three ([[0032-style-guide-and-primeng-theme]]): OK is
active with the existing checkmark, FAILED is the alarm colour with a "Failed" flag, and
INFO/WARNING/DISABLED are dim - WARNING marked by a "Check" flag rather than a fourth tone, the
same call [[0059-service-status]] made for DEGRADED. As there, a WARNING does not count toward
the badge.

### Container health check

`GET /api/system/healthz` returns 200 `{"status":"UP"}` or 503 `{"status":"DOWN"}`, and the
Dockerfile's `HEALTHCHECK` calls it every 30 s. It is DOWN only when the config or downloads
directory is unusable - the two things the app cannot work without. A network service that
failed to bind, a dead proxy or a stale blocklist leave the app running and worth keeping, so
they do not make the container unhealthy. The endpoint is exempt from authentication (a probe
can't hold a session) and discloses nothing but UP/DOWN.

## Alternatives considered

- **Adding rows to the existing `/api/system/services` list** - it is a flat list of engine
  services with engine states; storage and auth aren't engine services, and grouping would have
  been a frontend invention.
- **A real outside-in port test** (asking a third-party service to connect back) - an external
  dependency and a privacy cost for one row.
- **Running the proxy test inline** - makes a status request as slow as a dead proxy.
- **Unhealthy container on any FAILED check** - with an auto-restart or autoheal policy that
  turns a closed port into a restart loop.
- **`smallrye-health`** - a new dependency for one boolean endpoint.
- **Auth-off as a WARNING** - rejected by the user; it is the default.

## Stability ([[0051-stability-as-a-standing-consideration]])

- Request cost: a handful of `stat`-class filesystem calls and volatile reads. Polled every
  15-30 s per open browser, plus the container probe every 30 s.
- Threads: at most one proxy probe in flight (an `AtomicBoolean`), bounded by the proxy test's
  own timeouts; its flag is cleared in `finally`.
- Growth: the event log gets one entry per storage failure occurrence, under the existing
  retention limit; the in-memory set of failing checks has at most four members.
- Unauthenticated surface: `/api/system/healthz` only, returning two possible bodies.
- The directory check creates a missing directory, as the engine and `/disk-usage` already do.

## Known limits

- `Files.isWritable` is always true for root, so a root-run container (`PUID=0`) never reports a
  directory as unwritable through this check; a read-only mount still surfaces as torrent I/O
  errors.
- The uid/gid row reads `/proc/self`, so it shows only the account name off Linux.
- A FAILED network service says "could not start", not why; the cause is in the server log.

## Tests

`HealthServiceTest` (plain JUnit): directory OK/created/not-a-directory, free-space threshold,
incoming states and wording, blocklist states, auth, service-state mapping, overall status,
formatting. `SystemResourceTest`: the report has the five groups with their checks in order and a
writable downloads directory; `healthz` answers UP. Not covered: the unwritable-directory path
end to end (needs a non-root, read-only directory), the proxy probe and its cache, the
`STORAGE_UNWRITABLE` event, `healthz` without a token while authentication is on, and the page
itself.
