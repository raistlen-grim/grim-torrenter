# 0002 — Frontend framework

**Status:** Accepted

## Decision

Angular, using PrimeNG for UI components.

## Context / Why

Matches existing frontend background. PrimeNG supplies tables, progress
bars, and dialogs largely for free — a good fit for a torrent list/detail
dashboard.

## Alternatives considered

- **React** — larger ecosystem of drag-drop/chart libraries, more common
  pairing with Quarkus in official guides/examples. Not chosen: no existing
  familiarity advantage to offset the ecosystem difference.
- **Server-rendered (Qute templates + htmx)** — no separate SPA build, no
  Node toolchain, single artifact. Rejected: too limited for a live-updating
  dashboard (progress bars, peer counts, speed graphs).
