# Plan: Review archived scripts and recover/rename or delete each based on genuine ongoing use

## Phase 1: Audit
- [x] Task: Read each of the 19 scripts in `scripts/archive/` and classify it keep/delete against the agreed criterion (verifies behavior `sbt test` doesn't cover, and that behavior still exists in the current codebase). Record the classification + one-line reasoning for each in this track's plan.md.

  **Scope widened to 21**: also moved `scripts/verify-scaladoc-coverage.sh` and `scripts/verify-scaladoc-pages-deploy.sh` (both from the just-completed `scaladoc_20260925` track) into `scripts/archive/` for the same review, rather than exempting them for being recent.

  Read every script's full body (not headers) and cross-checked against the current automated test suite (`MigrationsSuite`, `OrderServicePostgresIntegrationSuite`, `InventoryDocsSuite`, `OrderDocsSuite`, `OrderServiceTraceContinuitySuite`, `LoggingSuite`, etc.) and the already-active `verify-observability-stack.sh`.

  **Keep (6) — to be recovered to `scripts/` in Phase 2:**
  - `run-services.sh` — dev convenience (start both services, no assertions/teardown); no automated-test analog for "just let me poke at running services." README already documents it as the day-to-day tool (currently pointing at the stale archive path). No code changes needed.
  - `verify-order-reservations.sh` — full order lifecycle (POST → GET by id → GET unknown id → 404) via the real `docker compose up` + `bgRun` local-dev path (the actual documented daily workflow, not Testcontainers). Most comprehensive script covering that path; supersedes the narrower `verify-docker-compose-setup.sh`. No code changes needed.
  - `verify-order-service-postgres-persistence.sh` — restarts order-service mid-test and confirms previously-created data survives via real Postgres — impossible to replicate with an in-process automated test. No code changes needed.
  - `verify-version-drift-guardrails.sh` — grep-asserts `build.sbt`'s eviction-error guardrail, `versionScheme`, and the `otel4s-core*` `dependencyOverrides` pins are still in place (confirmed all three still exist), then runs `sbt update` + the full test suite. Protects against silently losing these guardrails in a future dependency bump. **Needs a fix**: line 57 runs `sbt -batch "purerest/test" ...`, a stale module name predating the purerestlib rename — must become `"purerestlib/test"`.
  - `verify-scaladoc-coverage.sh` — regression check that purerestlib's public API keeps Scaladoc coverage (compile + doc generation succeed, the 12 previously-audited gaps stay documented); nothing else checks this. No code changes needed.
  - `verify-scaladoc-pages-deploy.sh` — smoke test of the GitHub Pages deploy pipeline's health (Pages configured via Actions, site reachable, a real page returns expected content) — same category of value as `verify-observability-stack.sh` testing its own pipeline's health. No code changes needed.

  **Delete (15) — superseded or already covered by automated tests:**
  - `loadtest-purerest.sh` — explicit predecessor of `verify-observability-stack.sh` (that script's own header says "unlike the old two-pass version of this script"); fully superseded.
  - `verify-docker-compose-setup.sh` — strict subset of kept `verify-order-reservations.sh` (same setup, only checks the POST half).
  - `verify-inventory-service-swagger.sh` / `verify-order-service-swagger.sh` — the tapir/docs.yaml/routes claims are now covered in-process by `InventoryDocsSuite` / `OrderDocsSuite`.
  - `verify-inventory-service-tracing.sh` / `verify-order-service-trace-continuity.sh` — trace/log correlation claims covered in-process by `LoggingSuite`, `TracingSuite`/`ServerTracingSuite`, and `OrderServiceTraceContinuitySuite` (which already does a real HTTP hop to a real bound Ember server).
  - `verify-metrics-end-to-end.sh` / `verify-metrics-wiring.sh` / `verify-resilience-end-to-end.sh` / `verify-resilience-wiring.sh` — the retry/circuit-breaker/metrics business logic they exercise is identical regardless of how the JVM is launched (bgRun vs. container), and `verify-observability-stack.sh` already proves it more rigorously (a full trip-*and*-recovery cycle via real Prometheus queries, not one-way timing thresholds).
  - `verify-order-reservations-schema.sh` / `verify-order-service-migrations.sh` — Flyway migration application is covered by the automated `MigrationsSuite`, and schema survival is additionally reconfirmed by kept `verify-order-service-postgres-persistence.sh`.
  - `verify-order-service-config.sh` — narrow one-shot check of a stable, standard PureConfig env-var substitution pattern; low ongoing regression risk.
  - `verify-purerest-consumption.sh` / `verify-purerest-publish.sh` — checked the old local-Ivy2-only publish/consume path; superseded by the now-thoroughly-verified real GitHub Packages release pipeline (`release-pipeline_20260925`) and `smoke-test/build.sbt`'s `-DresolveFromGitHubPackages` mode.

- [ ] Task: Conductor - User Manual Verification 'Phase 1: Audit' (Protocol in workflow.md).

## Phase 2: Recover keepers
- [ ] Task: For each script classified "keep": move it to `scripts/`, fix any stale references (module names, ports, paths — some predate the purerestlib rename) so it runs against current code, and run it once to confirm it passes.
- [ ] Task: Add a one-line README mention (command + what it checks) for each recovered script, matching `verify-observability-stack.sh`'s existing documentation.
- [ ] Task: Fix README's existing `./scripts/archive/run-services.sh` reference (line 163) and "lightweight path" callout (lines 67-70) to point at wherever it ends up.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Recover keepers' (Protocol in workflow.md).

## Phase 3: Delete obsolete scripts + close out docs
- [ ] Task: Delete every script classified "delete" (`scripts/archive/` should end up empty).
- [ ] Task: Update `conductor/product.md`'s Iteration 2 goal 3 ("Reduce operational noise") to reflect the resolved decision.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Delete obsolete scripts + close out docs' (Protocol in workflow.md).
