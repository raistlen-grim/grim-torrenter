# 0001 — Backend language and framework

**Status:** Accepted

## Decision

Java, on Quarkus.

## Context / Why

The primary developer has 20+ years of Spring Boot depth, which is the most
valuable input to this build. However, the app is intended to run primarily
in a Docker container, where Spring Boot's startup time and memory
footprint are a poor fit. Quarkus keeps a CDI/annotation-driven model close
enough to Spring's that the transfer is near-immediate, while being
designed for containers from day one (fast startup, low memory, first-class
GraalVM native image support).

## Alternatives considered

- **Go / Rust** — better raw fit for a protocol-heavy, highly concurrent
  networking app, but rejected: existing Java expertise is worth more to
  this build than the performance/idiomatic advantage.
- **Spring Boot + GraalVM native image** — keeps 100% of existing knowledge,
  cross the size/startup concern only at build time. Rejected for now due
  to more native-image friction (reflection config, etc.) than Quarkus or
  Micronaut, which are native-image-first.
- **Micronaut** — comparable pitch to Quarkus (compile-time DI, low
  footprint, historically strong for AWS Lambda-style cold starts). Not
  chosen because the core torrent engine is hand-rolled either way, so the
  framework's extension ecosystem mainly matters for the web/API layer and
  long-term community support — Quarkus has the larger ecosystem and
  community there.
- **Plain Java, no DI framework** — smallest possible footprint, rejected
  for losing framework productivity on the web/API layer for no real
  benefit to the engine itself.
