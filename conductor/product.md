# Product Guide

## Vision
This repository (`purerest`) is the home of **purerest** — a pure-functional-programming
platform library
(Scala 3, Cats Effect, http4s) that gives every microservice in an estate the same
observability (tracing, structured logging, metrics) and resilience (retry, circuit breaker)
infrastructure, consistently, as composable building blocks rather than per-service
reinvention. The open question this project exists to answer is *what "right" looks like* for
that infrastructure: purerest wraps established libraries (otel4s, resilience4j, cats-retry),
but their defaults and configuration are only genuinely validated by exercising them under
real, controlled load — not assumed correct because they're wired up. Two reference services,
**order-service** and **inventory-service**, are built on top of purerest to drive its design
and prove it out end-to-end.

## Target Users
- Internal engineering (primarily the author) — a personal platform-engineering project and learning vehicle for pure FP in Scala 3.
- Any future team/service that would adopt purerest as its microservice foundation.

## Core Use Case
`order-service` receives `POST /orders`, reserving stock via a resilient call to
`inventory-service`. This call path is purerest's design driver: a trace/span propagated across
the call, structured logs correlated by trace id on both sides, RED + resilience metrics for
both the inbound and outbound call, and a client that retries transient failures and trips a
circuit breaker under sustained failure — all composed via purerest's API, no annotations on
either service.

`inventory-service`'s failure/latency can be induced, and adjusted live via a `PATCH
/admin/induced-failure` endpoint (no restart needed), to validate purerest's resilience
behavior under controlled conditions.

## Customer Journey (context)
The Core Use Case above is the technical design driver, not a commitment to build a full
storefront. Only two steps of a real order lifecycle exist: **place order** (`POST /orders` —
reserve stock, persist, return the order + reservation id) and **check status** (`GET
/orders/{id}`, including a modeled 404 for an unknown id). Reservations never expire or
release, and payment/fulfillment don't exist — real gaps if the journey were ever extended
toward a real storefront, but out of current scope.

## Components
1. **purerest** (library) — tracing/propagation (OpenTelemetry), structured contextual logging
   (log4cats), metrics (Prometheus-compatible RED + resilience signals), a resilient HTTP
   client (retry + circuit breaker), and self-documenting endpoints (tapir: real routes + an
   always-in-sync OpenAPI spec/Swagger UI from one definition).
2. **order-service** — http4s REST API backed by PostgreSQL/Skunk; orchestrates order
   placement via purerest's resilient client.
3. **inventory-service** — http4s REST API exposing stock reservation; a second, independent
   reference consumer of purerest, deliberately made slow/flaky to exercise its resilience.

## Key Features (built)
- purerest: tracing, structured logging, metrics, retry, circuit breaker — as library building blocks
- order-service: `POST /orders`, order persistence, resilient call to inventory-service
- inventory-service: stock reservation endpoint(s)
- inventory-service: runtime-adjustable induced-failure rate/delay via `GET`/`PATCH
  /admin/induced-failure` (tapir-documented, no restart needed)
- Local observability stack (Docker Compose profile): Prometheus/Grafana/Elasticsearch/Kibana/Filebeat
  wired to real request/DB/resilience metrics and correlated structured logs — see README's
  "Build, run, and observe this system"

## Iteration 2 Goals (2026-09-23)
Hands-on use of the finished stack raised the question purerest hasn't actually answered yet:
**is this resilience/observability infrastructure good, and how would we know?**

1. **Validate resilience under controlled load.** ✅ Answered — a Gatling scenario ramps
   inventory-service's induced-failure rate (0.0 -> 0.6 -> 0.0) through a single continuous run,
   and the retry/circuit-breaker Grafana panels (plus two new ones: state-timeline,
   retry-success-rate) show *effective* behavior, not just activity: the breaker reliably trips
   under load and fully recovers once the failure rate drops, with retries succeeding at a high
   rate outside the degraded window. `scripts/verify-observability-stack.sh` automates this
   confirmation end-to-end.
2. **Make purerest a real, consumable library.** ✅ Answered — a tag-triggered release
   pipeline (`.github/workflows/release.yml`) publishes `purerestlib` to GitHub Packages on
   every `v*` tag, gated on `sbt scalafmtCheck test`; `smoke-test/` proves external resolution
   via an opt-in `-DresolveFromGitHubPackages=true` flag. Every public symbol in purerestlib's
   API now has Scaladoc, published two ways: a `-javadoc.jar` Maven artifact (automatic, via
   sbt's default publish behavior) and a browsable site at
   `https://beckfordp.github.io/purerest/`, deployed by the same workflow (see README's
   "Consuming purerest as a dependency"). (A service-template generator so adopting purerest
   starts from a working example is planned as a separate `pure-service-generator` project, not
   part of this repo's scope.)
3. **Reduce operational noise.** ✅ Answered — of the 21 scripts reviewed (19 originally
   archived wholesale, plus 2 recent additions folded into the same review), only
   `run-services.sh` and `verify-observability-stack.sh` remain in `scripts/`; the other
   19 were deleted as one-shot verification artifacts from completed tracks, either
   superseded by `verify-observability-stack.sh` or already covered by the automated test
   suite (`MigrationsSuite`, `InventoryDocsSuite`, `OrderDocsSuite`,
   `OrderServiceTraceContinuitySuite`, etc.).

## Non-Goals (for now)
- Authentication/authorization
- Multi-service deployment/orchestration (Kubernetes, etc.) — local/dev focus first
- Full customer-journey buildout (catalog, payment, fulfillment) — purerest's design driver, not a commitment
- A dedicated experiment-control UI — worth revisiting once Iteration 2's runtime-adjustable
  failure rate exists, not before
- Runtime-tunable retry/circuit-breaker config (maxRetries, backoff, failureThreshold,
  resetTimeout) — a bigger change than the failure-rate endpoint (static `PureConfig` values
  baked into client construction, not read per-request), revisit once the induced-failure
  endpoint and load-test work above are done and the Swagger-UI-plus-Grafana loop's
  sufficiency can actually be judged

## Future Direction (under consideration)
- **Repo renamed to `purerest`** (2026-09-25) — the library's own sbt module is now
  `purerestlib` (Scala packages stay `purerest.*`) so the root project/GitHub repo could take
  the name `purerest`. The larger restructuring this was in service of — recasting
  `order-service`/`inventory-service` and the load-test/observability stack as supporting
  tooling for *evaluating* the library rather than a multi-service product in their own right —
  is still not decided or scheduled, reinforced by moving the service-template idea out to its
  own `pure-service-generator` project. When that fuller restructuring is picked up, treat it as
  its own structural track, not a routine backlog item.
