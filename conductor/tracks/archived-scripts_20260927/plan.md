# Plan: Review archived scripts and recover/rename or delete each based on genuine ongoing use

## Phase 1: Audit [checkpoint: ea4295c]
- [x] Task: Read each of the 19 scripts in `scripts/archive/` and classify it keep/delete against the agreed criterion (verifies behavior `sbt test` doesn't cover, and that behavior still exists in the current codebase). Record the classification + one-line reasoning for each in this track's plan.md. [b894f49]

  **Scope widened to 21**: also moved `scripts/verify-scaladoc-coverage.sh` and `scripts/verify-scaladoc-pages-deploy.sh` (both from the just-completed `scaladoc_20260925` track) into `scripts/archive/` for the same review, rather than exempting them for being recent.

  Read every script's full body (not headers) and cross-checked against the current automated test suite (`MigrationsSuite`, `OrderServicePostgresIntegrationSuite`, `InventoryDocsSuite`, `OrderDocsSuite`, `OrderServiceTraceContinuitySuite`, `LoggingSuite`, etc.) and the already-active `verify-observability-stack.sh`.

  **Revised by user 2026-09-27**: moved `verify-order-reservations.sh`,
  `verify-order-service-postgres-persistence.sh`, `verify-version-drift-guardrails.sh`,
  `verify-scaladoc-coverage.sh`, and `verify-scaladoc-pages-deploy.sh` from keep to
  delete, overriding the reasoning originally recorded for each above. Final result:
  1 keep, 20 delete.

  **Keep (1) — to be recovered to `scripts/` in Phase 2:**
  - `run-services.sh` — dev convenience (start both services, no assertions/teardown); no automated-test analog for "just let me poke at running services." README already documents it as the day-to-day tool (currently pointing at the stale archive path). No code changes needed.

  **Delete (20) — superseded, already covered by automated tests, or not worth keeping per user's revision:**
  - `loadtest-purerest.sh` — explicit predecessor of `verify-observability-stack.sh` (that script's own header says "unlike the old two-pass version of this script"); fully superseded.
  - `verify-docker-compose-setup.sh` — narrower version of the same docker-compose+bgRun scenario `verify-order-reservations.sh` covered (now also deleted).
  - `verify-order-reservations.sh` — user decision.
  - `verify-order-service-postgres-persistence.sh` — user decision.
  - `verify-version-drift-guardrails.sh` — user decision.
  - `verify-scaladoc-coverage.sh` — user decision.
  - `verify-scaladoc-pages-deploy.sh` — user decision.
  - `verify-inventory-service-swagger.sh` / `verify-order-service-swagger.sh` — the tapir/docs.yaml/routes claims are now covered in-process by `InventoryDocsSuite` / `OrderDocsSuite`.
  - `verify-inventory-service-tracing.sh` / `verify-order-service-trace-continuity.sh` — trace/log correlation claims covered in-process by `LoggingSuite`, `TracingSuite`/`ServerTracingSuite`, and `OrderServiceTraceContinuitySuite` (which already does a real HTTP hop to a real bound Ember server).
  - `verify-metrics-end-to-end.sh` / `verify-metrics-wiring.sh` / `verify-resilience-end-to-end.sh` / `verify-resilience-wiring.sh` — the retry/circuit-breaker/metrics business logic they exercise is identical regardless of how the JVM is launched (bgRun vs. container), and `verify-observability-stack.sh` already proves it more rigorously (a full trip-*and*-recovery cycle via real Prometheus queries, not one-way timing thresholds).
  - `verify-order-reservations-schema.sh` / `verify-order-service-migrations.sh` — Flyway migration application is covered by the automated `MigrationsSuite`.
  - `verify-order-service-config.sh` — narrow one-shot check of a stable, standard PureConfig env-var substitution pattern; low ongoing regression risk.
  - `verify-purerest-consumption.sh` / `verify-purerest-publish.sh` — checked the old local-Ivy2-only publish/consume path; superseded by the now-thoroughly-verified real GitHub Packages release pipeline (`release-pipeline_20260925`) and `smoke-test/build.sbt`'s `-DresolveFromGitHubPackages` mode.

- [x] Task: Conductor - User Manual Verification 'Phase 1: Audit' (Protocol in workflow.md). [ea4295c]

## Phase 2: Recover keepers
- [x] Task: For each script classified "keep": move it to `scripts/`, fix any stale references (module names, ports, paths — some predate the purerestlib rename) so it runs against current code, and run it once to confirm it passes. [f2b9c1f] `run-services.sh` (the sole keeper after revision) moved to `scripts/`; its `ROOT_DIR` used `../..`, correct for the old two-levels-deep archive path but wrong one level shallower at `scripts/` (resolved to the repo's *parent* directory, breaking sbt entirely) — fixed to `..`, matching `verify-observability-stack.sh`'s existing pattern. Verified by running it: both services started, both `/docs/` Swagger UIs returned 200.
- [x] Task: Add a one-line README mention (command + what it checks) for each recovered script, matching `verify-observability-stack.sh`'s existing documentation. [a93fa83] `run-services.sh` already had a full paragraph in README (it's a dev-convenience script, not a pass/fail `verify-*.sh` check) — no separate one-line mention needed beyond fixing its path, done in the next task.
- [x] Task: Fix README's existing `./scripts/archive/run-services.sh` reference (line 163) and "lightweight path" callout (lines 67-70) to point at wherever it ends up. [a93fa83] Both references now point to `./scripts/run-services.sh`; also dropped the now-resolved "cleanup decision pending" language from the callout.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Recover keepers' (Protocol in workflow.md).

## Phase 3: Delete obsolete scripts + close out docs
- [ ] Task: Delete every script classified "delete" (`scripts/archive/` should end up empty).
- [ ] Task: Update `conductor/product.md`'s Iteration 2 goal 3 ("Reduce operational noise") to reflect the resolved decision.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Delete obsolete scripts + close out docs' (Protocol in workflow.md).
