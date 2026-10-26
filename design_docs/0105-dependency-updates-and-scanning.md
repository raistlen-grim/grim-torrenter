# 0105 - Dependency updates and vulnerability scanning with Dependabot

Status: Accepted (2026-10-26). Extends the Dependabot setup from [[0103-release-workflow]], which
covered only the workflow's pinned actions. Applies [[0051-stability-as-a-standing-consideration]].

## Problem

The repository is going public for outside users and contributors. Dependabot only watched the
GitHub Actions pins; the Maven, npm and Docker base-image dependencies, which are what actually
ship in the image, had no update or vulnerability alerts at all. A known CVE in Quarkus, an
Angular package or the Alpine/Temurin base would go unnoticed until someone looked by hand.

## Decision

**Two layers, both Dependabot:**

1. **Repository settings: dependency graph, Dependabot alerts and Dependabot security updates
   on.** Alerts flag a dependency with a published advisory; security updates open a pull request
   that bumps it to the first fixed version, whenever the advisory appears, not on a schedule.
2. **`.github/dependabot.yml`: weekly version updates** for four ecosystems:
   - `github-actions` (unchanged from 0103).
   - `maven` at `/`, the reactor pom; Dependabot follows the modules from there. Minor and patch
     bumps are grouped into one pull request.
   - `npm` at `/frontend`. Angular's packages are grouped on their own, since they release in
     lockstep and a partial bump doesn't build; other minor/patch bumps are grouped together.
   - `docker` at `/`, the Dockerfile's three base images. Major versions are ignored: the Node and
     Java majors are chosen deliberately and must match the release workflow's `node-version` and
     `java-version`, so moving them is a manual change to both places.

Pull requests are reviewed and merged by hand like any other change. There is no pull-request CI
yet, so a Dependabot pull request has had no tests run on it; it gets the same local build and
test pass before merging, and the release workflow's test jobs remain the gate before anything is
published.

## Alternatives considered

- **Renovate.** More configurable (shared presets, automerge, dependency dashboard), but it is a
  third-party app with write access to the repository, and Dependabot's grouping covers what this
  repo needs.
- **Security updates only, no scheduled version updates.** Fewer pull requests, but dependencies
  drift until a CVE forces a large jump across many versions at once, which is the riskiest time
  to upgrade.
- **Ungrouped updates.** One pull request per package; the frontend alone would produce a dozen a
  week, which tends to get them ignored.
- **A scanner in the release workflow (e.g. Trivy on the built image).** Would also catch OS
  packages in the runtime image, but only at release time, and a failing scan would block a
  release for a CVE with no fix yet. Worth revisiting alongside pull-request CI.

## Stability

No change to the running app. Dependabot opens at most five pull requests per ecosystem at a
time (its default), so a backlog doesn't grow without bound. A dependency bump is the most likely
source of a runtime regression, which is why each one is still built and tested locally before
merging rather than auto-merged.

## Open

- Pull-request CI (backend and frontend tests on `pull_request`, never `pull_request_target`)
  would let Dependabot and contributor pull requests show a test result before review.
