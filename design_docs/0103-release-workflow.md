# 0103 - Release workflow: publish on a version tag, tested, for amd64 and arm64

Status: Accepted (2026-10-20). Takes the "natural next step" left open in
[[0087-published-image-on-ghcr]]. Applies [[0051-stability-as-a-standing-consideration]].

## Problem

Publishing was two hand-typed commands, `docker build` with two `-t` tags and
`docker push --all-tags`. Nothing tied the tag to the version the build reports
([[0084-client-identification]]): 0.9.7 was typed while the poms still said 0.9.3, so the
container reported the wrong version. Nothing ran the tests first. The image was amd64 only, and
`--all-tags` re-pushed every older version tag in the local image store, overwriting the
published one if a local copy had ever been rebuilt.

## Decision

**A GitHub Actions workflow, `.github/workflows/release.yml`, publishes when a `vX.Y.Z` tag is
pushed.** Releasing is: bump the version in the three poms, commit, `git tag vX.Y.Z`, push the tag.
Four jobs:

1. **Version check.** `scripts/pom-version.sh` reads the version from `pom.xml` and fails unless
   both module poms name the same parent version and it is a plain `x.y.z`. The tag must equal
   it, and must be newer than every other `v*.*.*` tag. GitHub refuses a second push of an
   existing tag, so a version can't be published twice.
2. **Backend tests** (`mvn -B test`, Temurin 25) and 3. **frontend tests** (`ng test
   --watch=false`, Node 22 as in the Dockerfile), in parallel.
4. **Build and push**, only if all three pass: buildx with QEMU, `linux/amd64,linux/arm64`, tags
   `:X.Y.Z` and `:latest`, labels `org.opencontainers.image.version` and `.revision` (the commit).
   It logs in with the workflow's own `GITHUB_TOKEN` (`packages: write`), so no personal token is
   stored. Layers are cached in the GitHub Actions cache.

**The Dockerfile's two build stages run on the build platform** (`FROM --platform=$BUILDPLATFORM`).
The Angular bundle and the Quarkus jars are platform-independent, so building them once natively
and copying them into each platform's runtime stage gives the same image as building under arm64
emulation, which would take many times as long. Only the runtime stage, a few `RUN` steps, runs
under QEMU.

**`scripts/release.sh` stays as the local fallback.** It uses the same `pom-version.sh`, refuses a
version already on ghcr.io (`docker manifest inspect`) or not newer than the image tagged `latest`
(read from its version label), builds with the version label, and pushes the two tags by name
rather than `--all-tags`. It builds for the local machine's platform only. The README's
development section describes both.

## Consequences

- Every published image has passed the full test suite, and reports the version it is tagged
  with.
- arm64 hosts (Raspberry Pi, many NAS boxes) can run the published image. Not yet verified: that
  `eclipse-temurin:25-jre-alpine` is published for arm64. If it isn't, the publish job fails on
  the arm64 runtime stage; the fix is a Temurin base that is, with the entrypoint's `su-exec`
  replaced by its equivalent there.
- The package on ghcr.io was created by hand pushes. If the workflow's push is refused, the
  package settings need this repository given write access under "Manage Actions access".
- Images from before this change carry no version label, so the local script's "newer than
  latest" check only starts working from the first image that does.
- Engine tests that open real sockets (LSD multicast, peer connections on loopback) now also run
  on GitHub's runners. If one proves flaky there, it blocks a release until it is fixed or
  skipped, which is the point of running them, but worth knowing.

## Alternatives considered

- **Publish on every push to `main` when the pom version changes.** No separate tag step, but a
  version bump commit publishes immediately, and there is no git tag marking what was released.
- **Read the version from the tag and set the poms from it.** One less thing to edit, but the
  poms in the repository would stop saying what the build reports.
- **Native arm64 runners instead of QEMU.** Faster for a heavy arm64 build, but with the build
  stages on the build platform the arm64 work is a few seconds of `apk add`; not worth a second
  job and a manifest merge.
- **`docker/metadata-action` for tags and labels.** Standard, but for two fixed tags and two
  labels it adds indirection over writing them out.

## Stability

No change to the running app. The pipeline runs nothing on the deployment host. A failed test,
version check or arm64 build publishes nothing, rather than publishing amd64 alone. `latest`
still moves on every release (0087's note about pinning a version tag for unattended updaters
stands).
