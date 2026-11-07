# 0106 - Stay on Angular 21 and PrimeNG 21 for now

Status: Accepted (2026-10-27; ignore list extended and advisory handling added 2026-11-07). Revises the npm part of [[0105-dependency-updates-and-scanning]].
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
3. **Dependabot also ignores majors of the packages pinned by the Angular 21 / PrimeNG 21 line**
   (added 2026-11-07): `typescript` (`@angular/build@21` peers on `>=5.9 <6.0`), `vitest`
   (`@angular/build@21` peers on `^4.0.8`) and `@primeuix/*` (PrimeNG 21 depends on
   `@primeuix/styles` `^2`). Dependabot had opened typescript 7, vitest 5 and
   `@primeuix/themes` 3 pull requests that merged cleanly but left the tree unresolvable, the
   same failure as the Angular 22 bumps. These move as part of the Angular 22 upgrade, not on
   their own. Minor and patch updates for all three still come through.

Ignore rules only stop scheduled version updates. **Security updates don't follow them**: on
2026-11-07, with the Angular-major ignore already on `main`, Dependabot opened #19, which moved
`@angular/cli` to 22.2.6 to drop a vulnerable transitive `minimatch`. Expect more pull requests
like this, which bump a parent package to Angular 22 to fix something underneath it. Close them
instead of merging, and handle the advisory with the steps below. Only an advisory that reaches
the shipped bundle counts as the trigger under "When to revisit".

4. **A frontend advisory with no Angular 21 fix is handled in one of two ways** (added
   2026-11-07):
   - **Override**, as for `undici`, when the fixed version is a patch or minor move within what
     the dependents already accept, and the code is exercised by `ng build` / `ng test`, so the
     local build and test pass actually checks the override.
   - **Dismiss as tolerable risk** in the Dependabot alert, with a comment pointing here, when
     the fix needs a major version the dependents don't ask for, *and* the vulnerable code is
     dev-only and doesn't run during `ng build`, `ng serve` or tests. Forcing a major there
     risks a break that nothing in the build or tests would catch.

   **Dismissed so far:**
   - **Alert #77, `minimatch` 10.2.6** (GHSA-g3hv-75wg-m249, medium; fixed in 11.0.1). It's
     only reached through `@angular/cli` → `pacote` (`npm-packlist`/`ignore-walk`,
     `sigstore`/`tuf-js`, `cacache`/`glob`), the package-fetch path used by `ng add` and
     `ng update`. The three direct dependents ask for `minimatch` `^10`, so an override would be a
     major jump on code the tests never run. The advisory also needs attacker-supplied glob
     patterns and `optimizationLevel >= 2` (the default is 1). Whether these callers ever set it
     wasn't checked.

   **Cleanup:** the Angular 22 upgrade re-checks every dismissed alert. If the new tree still
   has the vulnerable version, the alert is reopened and handled again.

**When to revisit:** move to Angular 22 + Optimus UI (or whatever the MIT fork is by then) when
either of these happens first:

- Optimus UI has a few more months of steady releases tracking Angular.
- Angular 21 nears the end of its support window, roughly mid-2027 (each major gets about 18
  months).

A security advisory that only an Angular 22 release fixes, and that reaches the shipped bundle,
would also bring it forward. That upgrade gets its own design doc.

## Status checks

**2026-11-07: no trigger met, decision stands.** Next check around January–February 2027.

- **PrimeNG 22 is still commercial** (PrimeUI license; 22.1.4 released 2026-10-22). PrimeTek
  says existing MIT versions stay MIT. The installed `primeng@21.1.10` `LICENSE.md` confirms
  MIT for community versions; the commercial terms in that file apply only to `-lts` builds.
- **PrimeNG 21 patches may have stopped.** No 21.x release since `21.1.10` (2026-09-09), and
  sources disagree on whether 21.x still gets security fixes. If it doesn't, staying on 21
  depends on Angular 21's support window and on no fix being needed. That argues for planning
  the move in the first half of 2027 rather than waiting until mid-2027.
- **Optimus UI hasn't released since this doc was written.** Latest is 2.0.2 (2026-08-28,
  Angular `^22.1.4`), with a `v1-lts` 1.0.2 line for Angular 21. Four releases in July–August,
  then none for about ten weeks, so the "steady releases" trigger isn't met. The repository is
  still active (about 25 commits since September, 30 contributors, last push 2026-11-06), so it
  looks paused between releases rather than abandoned.
- **Migration cost is lower than first assumed:** Optimus UI ships a `migrate-from-primeng`
  schematic that rewrites imports. It probably won't cover the PrimeNG-specific styling hooks
  from [[0032-style-guide-and-primeng-theme]], which still need a manual pass.

## Alternatives considered

- **Angular 22 + PrimeNG 22 under the commercial license.** Rejected for the AGPL reasons above.
- **Angular 22 + Optimus UI now.** Possible, but it bets a full UI-library migration on a
  three-month-old fork, and Angular 21 isn't under any time pressure yet.
- **Leave the audit warning alone.** `undici` here is build-time only, so the risk is low. But a
  standing high-severity warning trains people to ignore audit output, and the override is cheap.
  For advisories where an override isn't cheap or can't be tested, see decision point 4: they're
  dismissed with a recorded reason rather than left as a standing warning, which keeps open
  alerts meaningful.
- **Override `minimatch` to `^11.0.1` like `undici`.** Rejected for alert #77. It's a major jump
  under three packages that ask for `^10`, and the affected code (`ng add` / `ng update`
  package fetching) isn't run by `ng build` or `ng test`, so a break would only show the next
  time someone adds or updates a package.
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
choose for themselves. The same upgrade should remove the `typescript`, `vitest` and `@primeuix/*`
major-version ignores in `.github/dependabot.yml` (keeping only the Angular and PrimeNG ones, if
the project is still on the manual-major policy), or those packages will quietly fall behind.
