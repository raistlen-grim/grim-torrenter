# 0003 — Docker packaging and repo layout

**Status:** Accepted

## Decision

Single Docker container, single monorepo. The Dockerfile is a multi-stage
build: build the Angular app, copy `dist/` into Quarkus's static resources,
then build the Quarkus application. Backend and frontend live in the same
repository/history.

## Context / Why

Primary usage is self-hosted, single-user deployment. One container is
simplest to run and reverse-proxy; there's no independent-scaling need that
would justify the extra operational surface of a second container.

## Alternatives considered

- **Two containers** (Quarkus API + nginx serving Angular, via
  docker-compose) — cleaner separation, independent redeploys/scaling.
  Rejected: unnecessary complexity for a single-user app.
- **Two separate repos** for backend/frontend — independent
  versioning/history. Rejected: adds coordination overhead with no benefit
  given they're packaged into one container anyway.
