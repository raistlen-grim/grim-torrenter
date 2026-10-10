# GrimTorrenter

A self-hosted BitTorrent client with a web UI, shipped as a single Docker container. The
BitTorrent protocol is implemented directly in Java rather than wrapping an existing client.

> **Test build.** This is an early release for testers. Expect rough edges, read
> [Known limitations](#known-limitations) before reporting a bug, and don't rely on it as your
> only copy of anything. Stored settings and state may not carry over between test builds.

## What it does

- Add torrents by `.torrent` file, magnet link, drag and drop, or a watched folder.
- Peer discovery through trackers (HTTP and UDP), DHT, peer exchange and local discovery.
- TCP and µTP transports, protocol encryption, incoming connections.
- Per-file priorities and skipping, labels with filtering, seeding limits (ratio and time).
- Global and per-torrent speed limits, with a daily schedule.
- IP blocklist and SOCKS5 proxy support.
- Live web UI: torrent list with multi-select, a details panel (files, peers, trackers,
  pieces), an event log, and a health page.
- Optional password protection for the UI and API.
- A REST API and WebSocket feed that the UI itself is built on.

## Quick start

You need Docker with the compose plugin. The image is published at
`ghcr.io/raistlen-grim/grim-torrenter`, so there is nothing to build: you only need the compose
file.

```sh
mkdir grimtorrenter && cd grimtorrenter
curl -fsSLO https://raw.githubusercontent.com/raistlen-grim/grim-torrenter/main/docker-compose.yml
curl -fsSL -o .env https://raw.githubusercontent.com/raistlen-grim/grim-torrenter/main/.env.example
docker compose up -d      # edit .env first if the defaults below don't suit
```

Open <http://localhost:8080>.

### Settings in `.env`

Every line is optional; these are the defaults.

| Variable | Default | What it is |
|---|---|---|
| `GRIMTORRENTER_TAG` | `latest` | Which published build to run. Set a version number to stay on one build. |
| `PUID` / `PGID` | `1000` / `1000` | User and group id the app runs as. Use the ids of the host user that owns the folders below (`id -u`, `id -g`). |
| `GRIMTORRENTER_HTTP_PORT` | `8080` | Where the web UI is reachable on the host. |
| `GRIMTORRENTER_LISTEN_PORT` | `6881` | BitTorrent port, TCP and UDP. |
| `GRIMTORRENTER_DOWNLOADS` | `./data/downloads` | Downloaded data. |
| `GRIMTORRENTER_CONFIG` | `./data/config` | Settings and the app's own state. Keep this to keep your torrents across upgrades. |
| `GRIMTORRENTER_WATCH` | `./data/watch` | Watch folder (off until enabled in Settings). |

### After the first start

1. **Open the Health page** (sidebar). It checks that the folders are writable, shows whether
   any incoming connection has been received, and tells you which build is running. Anything
   marked *Failed* comes with the fix.
2. **Forward the BitTorrent port** on your router to this machine, TCP and UDP. Without it you
   can still download, but fewer peers can reach you. Some networks throttle port 6881; if DHT
   stays empty, try another port. If this machine's traffic goes through a VPN, forwarding the
   port on your router does nothing: see [Known limitations](#known-limitations).
3. **Set a password before exposing it beyond your own network.** Settings → Security: set a
   password, then turn on *Require a password*. It is off by default, and while it is off anyone
   who can reach the page has full control.

### Folder permissions

The app runs as `PUID:PGID`, not root. It takes ownership of the config folder itself, but it
never changes ownership of your downloads or watch folders, because other software often shares
them. If it can't write to one, the container log and the Health page both say so. Fix it on the
host:

```sh
sudo chown -R 1000:1000 ./data/downloads
```

or set `PUID`/`PGID` to the user that already owns the folder.

### Exposing it to the internet

A password alone is not enough: over plain HTTP it can be intercepted. Put a reverse proxy that
terminates TLS (Caddy, Traefik, nginx) in front of the container. The app does not serve HTTPS
itself.

## Keyboard shortcuts

On the torrent list. A shortcut acts on the ticked rows if there are any, otherwise on the
current row (the row with keyboard focus, or the one open in the details panel).

| Key | Action |
|---|---|
| `↑` / `↓` | Move to the previous or next row. An open details panel follows. |
| `Space` | Tick or untick the current row. |
| `Shift` + `Space` | Extend the ticked range to the current row. |
| `Enter` | Pause what is running; if nothing is, resume what is paused. |
| `Delete` / `Backspace` | Open the remove dialog. |
| `I` | Open details: the torrent's own, or a summary when two or more rows are ticked. |
| `/` | Jump to the filter field. |
| `Esc` | Clear the filter if you're in it; otherwise close the details panel; otherwise clear the ticked rows. |

With the mouse: click a row to open its details, `Ctrl`/`Cmd`-click to tick it, `Shift`-click to
tick a range, right-click for the full menu.

Shortcuts are ignored while you are typing in a field, while a dialog or menu is open, and when
`Ctrl`, `Cmd` or `Alt` is held.

## Known limitations

Please don't report these as bugs.

- **IPv4 only.** No IPv6 peers, and the blocklist takes IPv4 ranges only.
- **BitTorrent v1 only.** No v2 or hybrid torrents.
- **No automatic port forwarding.** There is no UPnP or NAT-PMP, so the BitTorrent port has to
  be forwarded on your router by hand. Until it is, the Health page shows no incoming
  connections; downloads still work.
- **Behind a VPN, incoming connections usually don't work.** Peers would have to reach you
  through the VPN, and most VPNs don't forward ports, so the Health page shows no incoming
  connections however your router is set up. Downloads still work, with fewer peers and a
  slower start. A port forwarded by the VPN provider works only if it is a fixed number you can
  set as `GRIMTORRENTER_LISTEN_PORT`; a port the provider assigns and changes can't be picked up
  automatically.
- **Private trackers that whitelist clients will reject it.** It identifies itself honestly as
  GrimTorrenter, which no tracker knows yet.
- **Pieces are requested in order**, not rarest-first.
- **Adding and resuming wait for the tracker** and can take up to a minute when a tracker is
  slow. Pausing is immediate.
- **You can't choose files, labels or a location when adding**, and a torrent's data can't be
  moved afterwards. Set file priorities from the Files tab once it's added.
- **A recheck fails with an error if a file has been deleted from disk.** Restarting the
  container recreates the file and re-verifies.
- **The UI is laid out for a desktop browser.** It is not adapted to phones.
- **With a SOCKS5 proxy, DHT, µTP, local discovery and incoming connections are switched off**
  by default, so nothing bypasses the proxy. Changing that needs a restart.
- **No HTTPS.** See above.

## Reporting a problem

Open an issue at <https://github.com/raistlen-grim/grim-torrenter/issues> with:

- the version shown in the bar at the bottom of the UI (also on the Health page);
- what you did, what you expected, and what happened;
- a screenshot of the Health page if it's a setup or connection problem;
- the container log from around the time it happened:

  ```sh
  docker compose logs --since 30m grimtorrenter
  ```

  The log can contain tracker addresses and peer IP addresses. Read it through before posting.

## Upgrading

```sh
docker compose pull
docker compose up -d
```

If you set `GRIMTORRENTER_TAG` to a version number, change it to the new one first.

Your torrents and settings live in the config folder and are picked up again on start, and
downloaded data is re-verified. Between test builds this is not guaranteed: a new build may
not be able to read older state, in which case start again with an empty config folder.

## Development

Three parts: `grimtorrenter-engine` (plain Java, the protocol), `grimtorrenter-app` (Quarkus,
the REST API and WebSocket), and `frontend` (Angular). Requires JDK 25, Maven and Node 22.

```sh
mvn test                                  # all backend tests
mvn -pl grimtorrenter-app -am quarkus:dev # backend on :8080 with live reload
cd frontend && npm ci && npm start        # UI on :4200, proxying /api and /ws to :8080
```

In dev mode the BitTorrent port is 7881 rather than 6881.

To run a container built from your working tree instead of the published image, add the build
file (or set `COMPOSE_FILE` in `.env`, see `.env.example`):

```sh
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

To publish a build (needs a GitHub token with the `write:packages` scope):

```sh
docker login ghcr.io -u raistlen-grim
docker build -t ghcr.io/raistlen-grim/grim-torrenter:<version> \
             -t ghcr.io/raistlen-grim/grim-torrenter:latest .
docker push --all-tags ghcr.io/raistlen-grim/grim-torrenter
```

Design decisions are recorded one per file in [`design_docs/`](design_docs/); `PROGRESS.md` is
the running status and `TODO.md` the backlog.

## Licence

Copyright (C) 2026 James Hayne.

GrimTorrenter is free software: you can redistribute it and/or modify it under the terms of the
GNU Affero General Public License, version 3, as published by the Free Software Foundation. It is
distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. The full text is in
[`LICENSE`](LICENSE).

In practice: you may use, modify and share it, and if you run a modified version for other people
to use over a network, you must make your modified source available to them. The *Source* link at
the bottom of the UI points at the code this build came from; if you run a modified version,
point it at yours (`SOURCE_URL` in `frontend/src/app/shell/app-footer/app-footer.ts`).
