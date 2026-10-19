#!/usr/bin/env bash
#
# Prints the project version from pom.xml, after checking that both module poms name the same
# parent version and that it's a plain x.y.z release version. Exits non-zero (with the reason on
# stderr) otherwise. Shared by scripts/release.sh and .github/workflows/release.yml, so a local
# build and the pipeline read the version the same way. See design_docs/0103.

set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
  echo "pom-version: $*" >&2
  exit 1
}

# The parent pom's own <version>: the first one indented as a direct child of <project>.
version=$(sed -n 's:^  <version>\(.*\)</version>[[:space:]]*$:\1:p' pom.xml | head -n 1)
[[ -n "$version" ]] || fail "couldn't find the project <version> in pom.xml"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "pom.xml version '$version' isn't a plain x.y.z release version"

# Each module names the parent's version in its <parent> block; a missed one would build fine but
# against the wrong parent.
for module in grimtorrenter-engine grimtorrenter-app; do
  parent_version=$(sed -n '/<parent>/,/<\/parent>/s:.*<version>\(.*\)</version>.*:\1:p' "$module/pom.xml" | head -n 1)
  [[ "$parent_version" == "$version" ]] ||
    fail "$module/pom.xml's <parent> version is '$parent_version', but pom.xml says '$version' - update both"
done

echo "$version"
