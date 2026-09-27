# Spec: Audit services for other observability config worth exposing

## Overview
Extends Iteration 3's config-externalization work to `inventory-service` (which
currently reads all settings via ad-hoc `sys.env.get(...)` parsing in `Main.scala`, not
PureConfig at all — unlike order-service) and to both services' hardcoded service-name
strings, closing the gap between the two services' configuration approaches and removing
a duplicated magic string.

## Functional Requirements
1. Add `pureconfig-core` to inventory-service's `libraryDependencies` in `build.sbt`
   (currently only order-service has it).
2. Create `modules/inventory-service/src/main/resources/application.conf` with `port`,
   `metrics-port`, `induced-failure { rate, delay }`, and `service-name`, each following
   the established default + `${?ENV_VAR}` override pattern. Defaults match today's
   hardcoded fallbacks exactly (`port=8081`, `metrics-port=9091`, `rate=0.0`,
   `delay=0ms`). **Existing env var names are kept as-is**
   (`INVENTORY_SERVICE_PORT`, `INVENTORY_SERVICE_METRICS_PORT`,
   `INVENTORY_INDUCED_FAILURE_RATE`, `INVENTORY_INDUCED_DELAY_MS`) — only the loading
   mechanism changes, not the interface.
3. Create an `InventoryServiceConfig` case class (`derives ConfigReader`, `load[F]`
   method) analogous to `OrderServiceConfig`, replacing all `sys.env.get(...)` calls in
   `inventory-service/Main.scala`.
4. Add a `service-name` key to **both** services' `application.conf` (defaults
   `"order-service"` / `"inventory-service"`, each with an env-var override for
   consistency with every other setting in the file). Use `config.serviceName` in each
   `Main.scala` wherever the hardcoded string is currently passed to `Tracing.console`,
   `Metrics.oteljava`, and `Logging.create` (3 call sites per file today).
5. `InducedFailureConfig`'s existing runtime adjustability (`PATCH
   /admin/induced-failure`) is unaffected — the PureConfig-loaded rate/delay become only
   the startup default seeded into the `Ref`, exactly as today.

## Non-Functional Requirements
- No behavior change for anyone not overriding a setting — defaults match current
  values exactly.
- order-service's already-externalized resilience config (prior track) is untouched
  beyond adding `service-name`.
- No existing env var is renamed.

## Acceptance Criteria
- A new `InventoryServiceConfigSuite` (mirroring `OrderServiceConfigSuite`'s style):
  full-fixture load, missing-required-field failure, and shipped-`application.conf`-
  defaults load, all pass.
- `inventory-service/Main.scala` contains no `sys.env.get(...)` calls.
- Both `Main.scala` files use `config.serviceName`, not a hardcoded string literal, at
  every `Tracing`/`Metrics`/`Logging` call site.
- `sbt test` stays green across all modules.
- A manual live check confirms an env-var override still works post-migration (e.g.
  `INVENTORY_INDUCED_FAILURE_RATE` measurably changes real behavior), same spirit as the
  resilience-config track's live verification.

## Out of Scope
- Any change to `InducedFailureConfig`'s runtime PATCH-adjustability itself.
- order-service's resilience config (already done in a prior track).
- purerest's own source.
- Renaming any existing env var.
