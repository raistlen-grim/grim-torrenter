# 0004 — Build tool

**Status:** Accepted

## Decision

Maven.

## Context / Why

Closest to Quarkus's default/most-documented path, and closest to existing
Spring Boot muscle memory (`pom.xml`, standard lifecycle phases).

## Alternatives considered

- **Gradle** — faster incremental builds, more flexible for orchestrating
  the Angular build within the same build graph. Rejected: more setup, less
  common in Quarkus docs/examples, no existing familiarity advantage.
