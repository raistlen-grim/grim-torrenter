# 0106 - Stay on Angular 21 and PrimeNG 21 for now

Status: Accepted (2026-10-27). Revises the npm part of [[0105-dependency-updates-and-scanning]].
Applies [[0051-stability-as-a-standing-consideration]].

## Problem

Dependabot opened single-package pull requests that moved `@angular/build`,
`@angular/compiler-cli` and `@angular/platform-browser` to Angular 22, while every other Angular
package stayed on 21. They merged cleanly but `npm install` then failed with `ERESOLVE`: Angular
packages only resolve within one major. 0105's `angular` group didn't stop this. The
`@angular/build` bump came as an ancestor of an `undici` security update, and security updates
don't follow version-update groups.

The vulnerability behind it is real but narrow. `@angular/build` 21 pins `undici` to exactly
`7.29.1`, which has published advisories (an EventSource memory DoS, an SRI bypass, a
content-encoding DoS). `npm audit fix --force` offers only `@angular/build@22`.

Moving to Angular 22 isn't a version bump on its own:

- **PrimeNG has to move too.** `primeng@21` peers on Angular `^21`; `primeng@22` needs `^22.1`.
- **PrimeNG 22 is no longer MIT.** From v22, PrimeNG is under PrimeTek's commercial PrimeUI
  license (with a small-business exemption) and depends on `@primeui/license-manager`. v21 and
  older stay MIT and still get patch releases (`21.1.10`, 2026-09-09). This project is AGPL, so
  forks and contributors would have to qualify under PrimeTek's terms as well, which doesn't fit
  an open-source project.
- **The MIT fork is young.** OpenNG's `@openng/optimus-ui` (MIT, forked from the last MIT
  PrimeNG, supports Angular `^22.1.4`) was first published 2026-07-21 and is at 2.0.2 (last
  release 2026-08-28). It's a different package name, so every import changes, along with the
  PrimeNG-specific styling hooks the UI relies on (see 0032).
- TypeScript has to go from 5.9 to 6.0 (`@angular/build@22` needs `>=6.0 <6.1`).

## Decision

**Stay on Angular 21 and PrimeNG 21** (`primeng` `^21.1.10`), and close the advisory without a
major upgrade:

1. **An npm `overrides` entry forces `undici` to `^7.30.1`** in `frontend/package.json`. This is
   a patch-range move within 7.x, applied over `@angular/build`'s exact pin.
2. **Dependabot ignores major versions** of `@angular/*`, `@angular-devkit/*`,
   `@schematics/angular` and `primeng`. A major is done by hand with `ng update`, which also runs
   the framework's code migrations, rather than by merging single-package bumps.

**When to revisit:** move to Angular 22 + Optimus UI (or whatever the MIT fork is by then) when
either of these happens first:

- Optimus UI has a few more months of steady releases tracking Angular.
- Angular 21 nears the end of its support window, roughly mid-2027 (each major gets about 18
  months).

A security advisory that only an Angular 22 release fixes, and that reaches the shipped bundle,
would also bring it forward. That upgrade gets its own design doc.

## Alternatives considered

- **Angular 22 + PrimeNG 22 under the commercial license.** Rejected for the AGPL reasons above.
- **Angular 22 + Optimus UI now.** Possible, but it bets a full UI-library migration on a
  three-month-old fork, and Angular 21 isn't under any time pressure yet.
- **Leave the audit warning alone.** `undici` here is build-time only, so the risk is low. But a
  standing high-severity warning trains people to ignore audit output, and the override is cheap.
- **`npm audit fix --force`.** Installs `@angular/build@22` alone, which is the broken mixed tree
  this started from.

## Stability

No change to the shipped app. `@angular/build` and `jsdom` (the two users of `undici`) are dev
dependencies that run during `ng build`, `ng serve` and tests, not in the container image, which
serves only the built static files. The override does replace a version Angular pinned
exactly, so a local build and test pass confirms it before merging, and a build or test failure
traced to `undici` means dropping the override.

**Cleanup:** remove the `undici` override when moving to Angular 22, whose `@angular/build`
depends on a fixed `undici`. Left in place, it would keep overriding versions newer releases
choose for themselves.
