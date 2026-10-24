# 0089 - Tracker errors say why; Health warns when no tracker answers

Status: Accepted (2026-10-15). Extends [[0086-health-page-and-healthcheck]]'s connectivity group
and the tracker clients ([[0022-multi-tracker-fallback]]). Item 4's README advice revised by
[[0104-vpn-container-as-the-recommended-vpn-setup]] (2026-10-21): a gluetun container is now the
recommended setup, host networking the second option.

## Problem

Found on 2026-10-15 with a VPN connected on the Docker host. Traffic from Docker's bridge network
was not getting through the VPN, so the container could reach nothing: every tracker for every
torrent failed and nothing downloaded. The app gave no lead:

- The Health page read `OK`. Its incoming row even said "Downloads still work".
- HTTP tracker errors read only "Tracker request to … failed". Whether that was a timeout, a reset
  connection, a refused one or a TLS failure was in the exception's cause, never shown.

Diagnosing it took a series of commands run by hand on the server. A tester would have had a
torrent sitting at 0 peers and a page saying everything was fine.

## Decision

1. **Tracker errors carry the reason.** A new `TrackerErrors.reason()` walks the exception's
   cause chain and names the common network failures in plain words: timed out connecting,
   timed out waiting for a reply, connection reset, connection refused, could not resolve the
   host name, no route, network unreachable, TLS handshake failed. Anything else falls back to
   the first message in the chain with any URL query removed (an announce query carries the
   info hash and peer id, which 0036's 2026-09-20 addendum already kept out of messages), then
   to the exception's class name. Used by the HTTP tracker client's direct path and the UDP
   tracker client's I/O failure; the proxied paths already included the cause's message, and
   the UDP "did not respond after N attempts" and "could not resolve" messages already said why.
2. **A `trackers` check in Health's connectivity group**, between incoming and proxy. It looks at
   the trackers of the torrents that are running (downloading or seeding) right now, each
   tracker counted once however many torrents share it, taking the most informative state when
   they disagree:
   - `OK`, "N of M answering", as soon as any one answers - proof the network lets tracker
     traffic out.
   - `WARNING` when every one of two or more trackers is failing: "None of the N trackers in use
     is answering. If this lasts, something on this machine's network is probably blocking them -
     a firewall, or a VPN (see the README)", plus the latest error, which item 1 makes useful.
   - `INFO` while it can't tell: some not yet announced, or only one tracker in use (a single
     dead tracker looks exactly like a blocked network).
   - `DISABLED` when no running torrent uses a tracker.
   The backend writes the message; the frontend only gains a label and an icon for the new name
   ([[0071-thin-frontend-as-a-standing-consideration]]).
3. **The incoming row stops promising.** "Downloads still work" becomes "Downloads can still work
   over outgoing connections - see Trackers below", since outgoing is exactly what failed here.
4. **README: "Running behind a VPN".** How to tell the container is cut off (one command on the
   host, one in the container), and the fix that worked: host networking, with the UI port set by
   `QUARKUS_HTTP_PORT` and the health check pointed at it, because the image's built-in health
   check calls 8080 and port mappings don't apply on the host's network.

## Why a warning, not a failure

Every tracker of every running torrent can genuinely be dead - an old torrent kept alive by DHT,
say - and the app is then working as well as it can. A failure would put an alarm and a sidebar
badge on that. A warning, with the latest error and the hint, is right in both cases.

## Alternatives considered

- **Probe the internet directly** (fetch a known URL) - would catch the problem with no torrents
  running, but means the app phoning a fixed address on a schedule, and a VPN that blocks only
  torrent traffic would still pass. Rejected; trackers are already being contacted.
- **Count DHT too** (warn only if DHT also finds nothing) - DHT's node count includes contacts
  restored from disk, which made it look healthy in exactly this incident. Left out until there
  is a reliable "DHT has heard from anyone recently" signal.
- **Fix it in the app** - nothing to fix: the app can't reach past the host's firewall, and the
  best remedy is a deployment change, which is what the README now explains.
- **A separate VPN container (gluetun and the like)** - the more common self-hosted pattern, and
  it also handles forwarded ports from providers that offer them; mentioned in TODO.md, not
  documented here until someone has tried it with this app.

## Stability ([[0051-stability-as-a-standing-consideration]])

- The check reads tracker status already held in memory, only when the Health page or API asks;
  one small map per request, sized by the number of distinct trackers. Nothing new runs in the
  background and nothing is retained.
- `TrackerErrors.reason()` is a bounded walk of a cause chain and caps the fallback message at
  120 characters, so a hostile tracker or proxy can't inflate what is stored and shown.
- No hostile-peer angle beyond that: tracker text reaches only the message, as before.

## Tests

`TrackerErrorsTest`: each recognised failure, a cause found down the chain, the fallback with a
URL query removed, and the class-name fallback. `HealthServiceTest`: warning only when every
tracker of two or more is failing, with the latest error; a shared tracker counted once and
working if any torrent says so; information while some are unannounced or only one is in use;
disabled with no trackers. `SystemResourceTest`: the connectivity group lists incoming, trackers,
proxy. Not covered: the check against a live engine, and the page. The build and all unit tests
pass (2026-10-16); not yet deployed.
