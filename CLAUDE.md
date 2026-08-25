# GrimTorrenter

## What it is

A self-hostable BitTorrent client: a Java/Quarkus backend implementing the
BitTorrent protocol directly (not wrapping an existing client), with an
Angular web UI, shipped as a single Docker container.

## Repo layout

```
GrimTorrenter/
  grimtorrenter-engine/   plain Java, no Quarkus dependency
  grimtorrenter-app/      Quarkus backend, depends on engine
  frontend/               Angular
  design_docs/            architecture/pattern decision log, one file per decision
  Dockerfile              multi-stage: build Angular -> copy into Quarkus
                           static resources -> build Quarkus
  pom.xml                 Maven parent/reactor
```

## Architecture decisions

Every architecture or pattern decision is recorded in `design_docs/`, one
file per decision, kept up to date as decisions change. That directory is
the source of truth for rationale and alternatives considered — this file
just gives the current-state overview.

**Stability is a standing consideration for every decision, not a special
category of its own** (`design_docs/0051-stability-as-a-standing-consideration.md`):
when writing or revising a design doc, say something about resource/failure
behavior wherever the decision has any — unbounded growth (memory, file
descriptors, threads, connections, disk), a hostile-peer/tracker angle,
locking/concurrency versus `0007-concurrency-model`'s no-`synchronized`-in-the-
hot-path rule, and whether cleanup runs on every exit path, not just the happy
one. A brief "no stability implication" is fine when true — the point is
making it visible either way, not padding every doc.

## Working conventions

- **Builds and tests are run manually by the user.** Never invoke build or
  test commands (`mvn`, `ng build`, `ng test`, `ng serve`, `docker build`,
  etc.) in this project.
- **Git is handled manually by the user.** Never run `git init` or any git
  commands in this project unless explicitly asked.
- **Never look for or read compiled class files / decompiled sources for
  code not present in this project's source tree** (e.g. digging into `.m2`
  jars). If a dependency's API needs to be understood, ask for the source
  to be provided rather than inspecting jars/bytecode.
- **Every new architecture or pattern decision gets a new file in
  `design_docs/`** (or an update to the existing file if it revises a prior
  decision) — not folded silently into code or left undocumented.
