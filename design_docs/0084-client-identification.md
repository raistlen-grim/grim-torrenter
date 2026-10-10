# 0084 - Client identification

Status: Accepted (2026-10-02). Revises the peer id note in [[0018-torrent-engine]] and the
User-Agent note in [[0013-http-tracker-client]]. Prompted by preparing the app for other people
to test.

## Problem

The client's name and version were written out by hand in three places that had already begun
to disagree with each other and with the build:

- the peer id prefix, `-GT0100-`, in `PeerId`;
- `User-Agent: GrimTorrenter/0.1.0` in `HttpTrackerClient` and again in `MiniHttp`, with a
  version-less `GrimTorrenter` in `Blocklist`;
- none of them tied to the Maven version (`0.1.0-SNAPSHOT`).

Two further gaps: the BEP 10 extended handshake carried no client name, so other clients show
our peers as unknown (or as a raw `-GT0100-`); and nothing in the UI or the REST API said which
build was running, so a tester's bug report couldn't either.

## Decision

- **One source: `ClientIdentity`** (`com.grimtorrenter.engine`). The version is the Maven
  project version, written into `client-identity.properties` by resource filtering in the engine
  module's build - still no runtime dependency. If the resource is missing or unfiltered (an IDE
  run that skipped Maven's filtering) the version is `0.0.0-dev` rather than a startup failure.
- **Everything derives from it:**
  - peer id prefix `-GM` + one character each for major, minor, patch + `0-` (0-9, then A-Z for
    10-35, capped at Z) - `-GM0100-` for 0.1.0 (the code was `GT` until 2026-10-10, see below);
  - `User-Agent: GrimTorrenter/<major.minor.patch>` for tracker announces, proxied fetches and
    the blocklist download (which previously sent no version);
  - `v: "GrimTorrenter <major.minor.patch>"` in the extended handshake - new on the wire.
- **On the wire the qualifier is dropped** (`0.1.0`, not `0.1.0-SNAPSHOT`); the REST API and UI
  report the full version, since that is what distinguishes one test build from another.
- **`GET /api/system/version`** returns `{ name, version }`. The UI footer shows `v<version>`,
  fetched once per page load. Like the rest of `/api`, it requires a login when authentication
  is on, so the version is not disclosed to an unauthenticated caller.

## The client code: `GM`, changed from `GT` (2026-10-10)

`GT` was picked when the engine was first written, without checking it. Checked on 2026-10-10:

- **Not in any published table**: BEP 20, the theory.org specification wiki, and the tables
  libtorrent, Transmission, BiglyBT and webtorrent's `bittorrent-peerid` carry for naming peers.
  The `G` codes those list are `GS` (GSTorrent), `GR` (GetRight) and `G3` (G3 Torrent).
- **In use all the same**: the Go library `anacrolix/torrent` sends `-GT0003-` by default
  (`version.DefaultBep20Prefix`), and so does every application built on it that doesn't
  override it. It registers nowhere, which is why the tables miss it.

A clash breaks nothing - the code is advisory - but this client could not have been told apart
from that library's by peer id alone: a tracker whitelist or ban on `-GT` would cover both, and
so would any per-client statistic.

**Changed to `GM`** (`ClientIdentity.PEER_ID_CLIENT_CODE`), chosen by the user. `GM` is in none
of the tables above, nor in the Haskell `bittorrent` package's, and web searches for it as a
peer id prefix found nothing. That is weaker than it sounds: the same checks would have passed
`GT`. An unregistered use somewhere can't be ruled out, only not found.

The one published build before the change, image `0.9.0` as first pushed, identifies as
`-GT0900-`.

## Not changed

- The peer id is still generated once per process start, one identity for every torrent
  ([[0018-torrent-engine]]).
- No DHT `v` field - optional, and no consumer here.
- No decoding of *other* peers' client names for the Peers view - still the low-value backlog
  item in TODO.md.
- A private tracker that whitelists clients will reject this one whatever it sends. That is a
  documentation matter (known limitations), not something identification can fix.

## Alternatives considered

- **A hand-maintained constant** in one class - simpler, but it is exactly the copy that drifted.
- **Reading the version from the jar manifest** (`Package.getImplementationVersion()`) - absent
  when running from classes (tests, dev mode), which is most of the time during development.
- **Sending the full version including `-SNAPSHOT` on the wire** - no value to a tracker or peer,
  and some trackers parse the User-Agent.

## Stability ([[0051-stability-as-a-standing-consideration]])

No resource or failure behaviour of note: the version is read once at class load; a missing
resource degrades to a placeholder. The extended handshake grows by about 25 bytes. Privacy:
identifying the client and its version to trackers and peers is what every client does and what
trackers expect; it is slightly more than before only in that peers now get the name in `v`.

## Tests

`ClientIdentityTest` (prefix encoding incl. two-digit and oversized components, always 8
characters, qualifier dropped, and that filtering really ran for the build); `PeerIdTest` now
checks against `ClientIdentity`; `PeerConnectionTest`'s two exact-bytes handshake cases include
the `v` entry; `SystemResourceTest` covers the new endpoint. The footer is not covered.
