# 0005 — Module structure

**Status:** Accepted

## Decision

Multi-module Maven project:

- `grimtorrenter-engine` — plain Java, zero Quarkus/CDI dependency.
- `grimtorrenter-app` — Quarkus application, depends on `grimtorrenter-engine`.

## Context / Why

The BitTorrent protocol engine (bencode parsing, tracker/peer wire
protocol, piece management, storage, session lifecycle) needs to be
testable with plain JUnit and no CDI container, and must never accidentally
acquire a Quarkus dependency. A build-enforced module boundary (separate
Maven module, separate `pom.xml` with no Quarkus dependency declared)
guarantees this; package-naming convention alone does not.

## Alternatives considered

- **Single module, package-level separation** (e.g.
  `com.grimtorrenter.engine.*` vs `com.grimtorrenter.web.*`) — simpler
  scaffolding, but nothing stops an accidental import of a Quarkus
  annotation into engine code except code review discipline. Rejected in
  favor of a build-enforced boundary.
