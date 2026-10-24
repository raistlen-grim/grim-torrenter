# 0104 - A VPN container (gluetun) is the recommended VPN setup

Status: Accepted (2026-10-21). Revises the README guidance from
[[0089-tracker-error-reasons-and-connectivity-check]], which left "a separate VPN container" as
an alternative "not documented here until someone has tried it with this app". Applies
[[0051-stability-as-a-standing-consideration]].

## Problem

0089's README fix for a VPN on the Docker host was `network_mode: host`, so the container's
traffic goes through the host's VPN client like any other program's. It works, but the VPN then
carries the whole machine. On a typical media server that breaks things unrelated to this app:
the VPN client's firewall drops LAN and Docker-bridge traffic to the other containers, and a
media server's remote access fails because replies to connections that came in through the
router leave through the tunnel. Each needs its own allowlist rule in the VPN client.

## Evidence

Tried on 2026-10-21 on the developer's own server (openmediavault, NordVPN): host-wide NordVPN
turned off, GrimTorrenter moved from `network_mode: host` to `network_mode: "service:gluetun"`
behind `qmcgaw/gluetun:v3` (NordVPN, WireGuard). Results:

- gluetun and the GrimTorrenter container both reported the NordVPN exit address; the host
  reported the ISP address.
- The web UI, published on gluetun as `8087:8087` (the deployment keeps `QUARKUS_HTTP_PORT`), was
  reachable from the LAN, and Sonarr/Radarr's download-client tests passed against it.
- The image's built-in health check passed unchanged: it calls `127.0.0.1`, which is gluetun's
  network namespace, where the app listens.
- An Ubuntu ISO: both HTTPS trackers `WORKING`, DHT at 206 nodes, 15 peers, about 5.7 MB/s
  within a minute or two.
- Plex and the other containers on the host worked with no VPN rules at all.

Not checked: PEX and LSD individually (LSD's multicast probably doesn't cross the tunnel, which
only matters for peers on the same LAN), and a provider's forwarded port.

## Decision

**The README's "Running behind a VPN" leads with a gluetun Compose example; host networking stays
as the second option** for a VPN client already on the machine. The example:

- Publishes the web UI on gluetun (`8080:8080`), not on grimtorrenter: a container with
  `network_mode: service:...` can't have its own port mappings. Port 8080 doesn't clash with
  anything gluetun listens on inside the namespace (its control server is 8000, its optional
  proxies 8888 and 8388), so no `QUARKUS_HTTP_PORT` or health-check override is needed.
- Publishes no BitTorrent port, since a provider without port forwarding can't deliver incoming
  connections.
- Takes the WireGuard key from `.env`, not the Compose file, so it isn't committed or pasted
  around with the rest.
- Says to turn off a host VPN client, so the tunnels aren't nested and the host client's firewall
  can't block gluetun's own connection.

No code change: the app works unmodified behind gluetun. The repo's `docker-compose.yml` stays as
it is - most users have no VPN, and a second Compose file would duplicate the README example.

## Alternatives considered

- **Keep host networking as the main advice** - fewer moving parts, but it puts the whole machine
  behind the VPN; rejected for the reasons in Problem.
- **A `docker-compose.vpn.yml` in the repo** - one more file kept in step with the image for a
  setup whose provider settings differ per user anyway. The README example is enough.
- **wg-easy or a self-hosted WireGuard server** - solves remote access to the home network, not
  outbound privacy; traffic leaves on the home address. Not a substitute.

## Stability

- **Restart coupling.** GrimTorrenter lives in gluetun's network namespace. If gluetun is
  recreated on its own, GrimTorrenter is left without a network until it's restarted; the README
  says to recreate the two together. `depends_on: service_healthy` covers first start only.
- **Leak on VPN drop.** gluetun's firewall blocks traffic while the tunnel is down, so the app's
  traffic can't fall back to the real address - better than host networking, where that depends
  on the host client's kill switch being on.
- **Hostile-peer angle:** unchanged; still no incoming connections behind a VPN without port
  forwarding.
- Nothing grows or holds resources in the app; this is deployment-only.

## Open

A provider-forwarded port through gluetun (Proton VPN, PIA, AirVPN) is still TODO.md's
"Investigate gluetun support" part 2: the port is assigned and can change, and the app takes its
listen port once at startup.
