# 0085 - Container user (PUID/PGID), portable compose file, dev-only listen port

Status: Accepted (2026-10-02). Extends [[0003-docker-packaging-and-repo-layout]]. Part of preparing
the app for other people to run.

## Problems

1. **The built image listened on the wrong port.** `application.properties` carried
   `grimtorrenter.listen-port=7881` - a workaround for one developer's network dropping 6881
   ([[0028-magnet-links-and-dht]]'s "port 6881" trail). That file is baked into the image, so
   the container listened on 7881 while the Dockerfile exposed, and `docker-compose.yml`
   mapped, 6881. Anyone using the compose file got no incoming connections and no working DHT,
   and announced an unreachable port to trackers.
2. **`docker-compose.yml` was one machine's deployment** - absolute `/srv/dev-disk-by-uuid-...`
   paths and a host port of 8087.
3. **The container ran as root**, so everything it wrote to the mounted directories was
   root-owned on the host.

## Decision

- **Listen port.** The 7881 override is now `%dev.grimtorrenter.listen-port` - dev mode only. A
  built image uses the property's default, 6881, matching what the Dockerfile and compose file
  say.
- **Compose file.** Every path, port and id is a `${VARIABLE:-default}`; defaults are
  `./data/{downloads,config,watch}`, UI on 8080, BitTorrent on 6881, PUID/PGID 1000.
  `.env.example` documents them; a real `.env` (read automatically by compose) is git-ignored,
  as is `./data/`. The listen port is one variable used for both sides of the port mapping
  *and* passed to the app, because the number the app announces has to be the number reachable
  from outside.
- **PUID / PGID.** A small entrypoint script (`docker/entrypoint.sh`) starts as root, then
  `su-exec`s to `PUID:PGID` (default 1000:1000) to run the JVM. Numeric ids only; no user is
  created in the container. `0:0` keeps the old run-as-root behaviour. If the container is
  started as a non-root user already (`--user`), the script just runs the app as that user.
- **What gets chowned: only the config directory**, recursively, on every start. It is the
  app's own, small, and may have been written by an earlier root-running image.
  **Downloads and the watch folder are never chowned** - they are commonly shared with other
  software, and silently re-owning a large shared tree is the kind of surprise that breaks
  someone else's setup. Instead the entrypoint tests whether the target user can write to each
  and prints a warning with the fix if not. The same condition is shown in the UI by the Health
  page's storage checks ([[0086-health-page-and-healthcheck]]).

## Consequences

- **Upgrading an existing root-run deployment** with the default 1000:1000 will hit that
  warning: previously downloaded files are root-owned, so torrents that need to write will go to
  `ERROR`. Either chown the host directories or set `PUID=0`/`PGID=0`.
- This developer's own deployment is preserved by a local, untracked `.env` holding the old
  paths, port 8087 and `PUID=0`/`PGID=0`.
- App-module tests no longer pick up 7881; they don't bind the port at all (DHT and incoming
  connections are off in the test profile).

## Alternatives considered

- **`USER` in the Dockerfile with a fixed uid** - no root at all, but the uid can't match an
  arbitrary host user without rebuilding, which is the whole problem being solved.
- **Recursive chown of downloads on start** - what some images do; rejected for the reason
  above, and because it is slow on a large library.
- **Telling users to pass `--user`** - still supported, but then nothing can fix up a
  root-owned config directory, and compose users expect PUID/PGID.
- **Keeping personal values in `docker-compose.override.yml`** - works, but `.env` is the
  smaller and more familiar mechanism for "same file, my values".

## Stability ([[0051-stability-as-a-standing-consideration]])

- The entrypoint `exec`s the JVM, so it stays PID 1's direct replacement and receives
  `SIGTERM` from `docker stop` as before - graceful shutdown ([[0081-background-stopped-announce]])
  is unaffected.
- Recursive chown is bounded to the config directory (settings, per-torrent markers, daily
  event files under a retention limit).
- Running unprivileged narrows what a bug in the app, or a malicious torrent's file paths,
  could touch on the host volumes.
- Non-numeric PUID/PGID fails fast with a message rather than starting as root by accident.

## Verification

Run for real on the published 0.9.0 image (2026-10-10, see [[0087-published-image-on-ghcr]]): the
image builds; the Health page reports uid 1000 and writable downloads and config directories;
incoming connections arrive on the mapped port with no extra configuration. Not yet tried: the
warning for an unwritable downloads directory, `PUID=0 PGID=0`, and the ownership of new files
checked on the host.
