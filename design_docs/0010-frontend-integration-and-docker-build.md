# 0010 — Frontend integration and Docker build

**Status:** Accepted

## Decision

The Angular production build output is copied into
`grimtorrenter-app/src/main/resources/META-INF/resources` at Docker build
time, before `mvn package` runs. Quarkus's HTTP layer serves anything under
`META-INF/resources` on the classpath as static content automatically — no
extra extension or configuration needed.

`frontend/angular.json` declares no explicit `outputPath`, so the default
applies: the `@angular/build:application` (esbuild) builder writes browser
assets to `frontend/dist/frontend/browser/`.

The Dockerfile is three stages:
1. **frontend-build** (`node`) — `npm ci` + `ng build --configuration
   production`, producing `frontend/dist/frontend/browser/`.
2. **backend-build** (`maven` + JDK) — copies the frontend build output into
   `grimtorrenter-app/src/main/resources/META-INF/resources`, then runs
   `mvn package` across the reactor, producing a Quarkus fast-jar at
   `grimtorrenter-app/target/quarkus-app/`.
3. **runtime** (JRE only) — copies `target/quarkus-app/` and runs
   `java -jar quarkus-run.jar`.

## Context / Why

This keeps the single-container packaging decided in
[[0003-docker-packaging-and-repo-layout]] simple: no extra Quarkus extension
is needed just to serve static files, and no runtime dependency on Node
exists in the final image — only the JRE and the already-built assets.

## Alternatives considered

- **`quarkus-web-bundler` extension** — manages frontend asset bundling
  from within Quarkus itself. Not used: it changes how the frontend project
  is structured/built and adds a tool dependency for something a plain
  Docker `COPY` already solves.
- **Separate nginx container for the frontend** — already rejected in
  [[0003-docker-packaging-and-repo-layout]].
