#!/usr/bin/env bash
#
# Builds the container image and pushes it to ghcr.io, tagged with the version from pom.xml and
# "latest". Refuses to build when the pom's version hasn't been bumped:
#   - the three poms must agree on the version;
#   - ghcr.io must not already have an image tagged with it;
#   - it must be newer than the version of the image currently tagged "latest".
#
# Usage: scripts/release.sh            build and push
#        scripts/release.sh --no-push  build only (still runs every check)
#
# Needs a `docker login ghcr.io` for the push (and for the checks, if the package is private).
# The usual route is the GitHub Actions release workflow instead (push a vX.Y.Z tag - see
# design_docs/0103); this is the local fallback. It builds for this machine's platform only.

set -euo pipefail

IMAGE=ghcr.io/raistlen-grim/grim-torrenter
VERSION_LABEL=org.opencontainers.image.version

push=true
case "${1:-}" in
  "") ;;
  --no-push) push=false ;;
  *) echo "Usage: $0 [--no-push]" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."

fail() {
  echo "release: $*" >&2
  exit 1
}

# --- The version, from the poms ------------------------------------------------------------------

version=$(scripts/pom-version.sh) || exit 1
echo "pom.xml version: $version"

# --- Has it been published already? --------------------------------------------------------------

# `docker manifest inspect` succeeds if the tag exists. Any failure other than "not found" (no
# network, not logged in to a private package) stops the release rather than guessing.
if output=$(docker manifest inspect "$IMAGE:$version" 2>&1); then
  fail "$IMAGE:$version is already published - bump the version in the poms first"
elif ! grep -qiE 'no such manifest|manifest unknown|not found' <<<"$output"; then
  fail "couldn't check whether $IMAGE:$version exists: $output"
fi

# --- Is it newer than "latest"? --------------------------------------------------------------------

# Images built by this script carry their version as a label. One built before it doesn't, and
# then only the check above applies.
latest=$(docker buildx imagetools inspect "$IMAGE:latest" \
  --format "{{ index .Image.Config.Labels \"$VERSION_LABEL\" }}" 2>/dev/null || true)
latest=$(tr -d '[:space:]' <<<"$latest")

if [[ "$latest" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "latest on ghcr.io: $latest"
  [[ "$version" != "$latest" ]] || fail "version $version is the same as latest - bump the version in the poms first"
  newest=$(printf '%s\n%s\n' "$latest" "$version" | sort -V | tail -n 1)
  [[ "$newest" == "$version" ]] || fail "version $version is older than latest ($latest)"
else
  echo "latest on ghcr.io has no version label (built before this script) - relying on the tag check"
fi

# --- Build and push --------------------------------------------------------------------------------

docker build \
  --label "$VERSION_LABEL=$version" \
  -t "$IMAGE:$version" \
  -t "$IMAGE:latest" \
  .

if [[ "$push" == false ]]; then
  echo "Built $IMAGE:$version and :latest (not pushed)"
  exit 0
fi

# Only these two tags, not --all-tags: that would also re-push every older version tag left in the
# local image store, overwriting the published one if a local copy was ever rebuilt.
docker push "$IMAGE:$version"
docker push "$IMAGE:latest"

echo "Pushed $IMAGE:$version and :latest"
