# GrimTorrenter — TODO

Running list of ideas/requests to come back to later. Not commitments, not scoped, not
scheduled - just a place to jot something down before it's forgotten. Add items freely;
nothing here gets acted on until it's explicitly picked up.

- Notification service (emails, or something else yet to be defined)
- Run a user-configured script automatically when a torrent completes
- Migrate off `@primeng/themes` (deprecated upstream, per its own `npm ci` warning) to
  `@primeuix/themes`, the maintained replacement. Not urgent - no functionality is broken yet,
  just a maintenance item before the old package stops receiving updates entirely.
- **DHT service status doesn't distinguish "healthy" from "bootstrapped but sparse."**
  `TorrentEngine`'s DHT service status ([[0059-service-status]]) reports `RUNNING` as soon as
  `dhtNode != null` (construction succeeded), with no distinction from "enabled, but the
  routing table has stayed tiny ever since" - a real, now twice-observed state (design_docs/
  0028's "port 6881" debugging trail, and again 2026-08-30: 21 DHT nodes known vs. qBittorrent's
  379 on the same network). Might be worth a `DEGRADED` state (or a node-count threshold) once
  there's a concrete reason to build it, rather than speculatively now.
  - **Root cause confirmed for one real case (2026-08-30)**: a dev machine's DHT node stayed at
    0-ish known nodes indefinitely (over a minute post-startup) while running via `mvn quarkus:dev`
    in IntelliJ, blocking every trackerless magnet's metadata fetch (`0 peer(s) tried` - DHT
    lookup had nothing to try). Diagnosed by sending a raw KRPC `ping` directly to two of the
    three hardcoded bootstrap hosts (`Bootstrap.DEFAULT_HOSTS`) on UDP port 6881 - both timed
    out with no response, while plain outbound UDP to unrelated hosts/ports (NTP, DNS) worked
    fine, isolating the block to port 6881 specifically (`DhtNode` reuses `ourListenPort`,
    default 6881, for its UDP socket too). **Confirmed fixed** by setting
    `grimtorrenter.listen-port=7881` in `application.properties` and restarting - DHT reached 16
    nodes and the same magnet found 9 peers. Router/ISP traffic-shaping specifically targeting
    port 6881 (BitTorrent's well-known default) is a plausible, fairly common explanation.
    Worth eventually surfacing as a hint somewhere a user would see it (a Services page tooltip,
    or a note next to the DHT status) when node count stays near zero for a while - "try a
    non-default listen port" is a cheap, high-value troubleshooting step that's currently
    undiscoverable without exactly this kind of manual investigation.
  - **The 21-vs-379 node-count gap itself is now addressed** (2026-08-30): periodic bucket
    refresh plus a real ping-then-evict replacement policy, see
    design_docs/0028's own 2026-08-30 addendum. The `DEGRADED`-state idea above is still open
    (a low count is no longer expected to persist indefinitely, but the status endpoint still
    doesn't distinguish "still filling in" from "genuinely stuck").
- **No LSD (Local Service Discovery, BEP 14)** - noticed via the same 2026-08-30 comparison
  (qBittorrent reports DHT/PEX/LSD all active; GrimTorrenter has no LSD implementation at
  all). Only ever finds same-LAN peers, so it's a minor contributor to peer-count gaps at
  best, not a priority on its own - noted for completeness alongside the DHT item above.
