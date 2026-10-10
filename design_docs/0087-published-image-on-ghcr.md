# 0087 - Published image on GitHub Container Registry

Status: Accepted (2026-10-10). Extends [[0003-docker-packaging-and-repo-layout]] and
[[0085-container-user-and-portable-compose]]. Part of preparing the app for other people to run.

## Problem

Running the app anywhere meant cloning the repository and building the image there: a few
minutes of Node and Maven on every machine and every upgrade, and a full source tree on a box
that only needs to run a container.

## Decision

- **The image is published to `ghcr.io/raistlen-grim/grim-torrenter`.** GitHub's registry,
  because the source, the issue tracker and the image then sit under one name and one account.
- **`docker-compose.yml` pulls; it no longer builds.** It names the image and has no `build:`
  section, so the compose file plus an optional `.env` is a complete deployment. The tag is
  `${GRIMTORRENTER_TAG:-latest}`.
- **Building from source is a second file, `docker-compose.build.yml`**, which only adds
  `build: .`. It is used explicitly (`-f docker-compose.yml -f docker-compose.build.yml`) or
  through `COMPOSE_FILE` in `.env`. A locally built image gets the published image's name.
- **Tags.** Each published build is pushed under its version number and as `latest`. `latest`
  is the default so a tester's upgrade is `docker compose pull`; a version number pins a build.
  The version tag should be the version the build itself reports
  ([[0084-client-identification]]), so a bug report's version names an image.
- **The image carries an `org.opencontainers.image.source` label** naming the repository, which
  is what makes GitHub show the package on the repository's page and link back from it.
- **Publishing is done by hand for now** (`docker build`, `docker push`; the commands are in the
  README's development section). One architecture: whatever the publishing machine is.

## Consequences

- A developer's own deployment that used `docker compose up -d --build` now needs
  `COMPOSE_FILE` set in its `.env` to keep building locally.
- A new package on ghcr.io starts private. It has to be made public once, in the package's
  settings on GitHub, before anyone else can pull it without logging in.
- The README's quick start fetches `docker-compose.yml` and `.env.example` from the `main`
  branch, so the repository has to be public and those two files on `main` have to work with
  the image tagged `latest`.
- The image is a distribution of the program under its licence (AGPL-3.0). The *Source* link in
  the UI footer already points at the repository; a published image should be built from a
  commit that is pushed there.

## Alternatives considered

- **Docker Hub** - the default registry and the shortest image name, but a second account and
  a second place to keep in step with the repository.
- **`image:` and `build:` together in one compose file** - one file for both uses, but compose
  then builds when the image isn't present locally rather than pulling it, which is the wrong
  default for someone who only has the compose file.
- **`docker-compose.override.yml` for the build** - compose loads it automatically, so anyone
  who cloned the repository would build without asking to.
- **A CI workflow that builds and pushes on a version tag** (and builds for both amd64 and
  arm64) - the natural next step; left until a hand-published image has been through a clean
  first run.

## Stability ([[0051-stability-as-a-standing-consideration]])

No change to the running app. Two operational points: `latest` moves, and stored state is not
yet guaranteed to carry over between test builds (the README says so), so an unattended updater
following `latest` can land on a build that cannot read its config folder - pin a version tag
where that matters. And an image for one architecture fails to start on another with an "exec
format error" rather than degrading.

## Verification

Not yet pushed or pulled. To confirm: the image pushes; the package is public; on a machine
with only `docker-compose.yml`, `docker compose up -d` pulls and starts it; the two-file form
still builds from source.
