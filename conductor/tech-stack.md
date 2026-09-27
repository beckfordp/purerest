# Tech Stack

## Language & Build
- **Scala** 3.9.0
- **sbt** — multi-module build: `purerestlib`, `order-service`, `inventory-service` (`modules/*`)

## Publishing
- **purerest** (sbt module `purerestlib`, so the root project can be named `purerest`) is
  publishable both locally (`sbt purerestlib/publishLocal`, org `io.github.beckfordp`, to the
  local Ivy2 cache) and to this repo's GitHub Packages Maven registry (`sbt
  purerestlib/publish`, credentials via `GITHUB_ACTOR`/`GITHUB_TOKEN`).
  `order-service`/`inventory-service` still consume it via `.dependsOn` internally, not the
  published jar. `smoke-test/` is a standalone sbt build (outside the root aggregate) that
  proves purerest works when resolved purely as a published jar — either from the local Ivy2
  cache (default) or from GitHub Packages (`-DresolveFromGitHubPackages=true`), the way a real
  external consumer would.
- **Versioning**: sbt-dynver derives `version` from git tags/commits; `versionScheme :=
  "early-semver"` on `purerestlib`. A `v1.2.3` tag publishes as `1.2.3`; untagged builds carry
  `0.0.0+<commit-count>-<sha>` versions.
- **Release pipeline**: `.github/workflows/release.yml` triggers on push of any `v*` tag. The
  `release` job runs `sbt scalafmtCheck test` as a gate, then (only on success) `sbt
  purerestlib/publish` to GitHub Packages (which also publishes a real Scaladoc `-javadoc.jar`
  automatically, via sbt's default publish artifacts — no extra config needed for that half).
  A second `deploy-docs` job (`needs: release`) then runs `sbt purerestlib/doc` and deploys the
  rendered HTML to GitHub Pages at a stable "latest" URL, overwritten on every release — see
  the README's "Consuming purerest as a dependency" section for both the dependency-resolution
  setup and the hosted docs link. The `github-pages` deployment environment has a `v*` tag
  policy (added via the GitHub API, since a fresh Pages environment defaults to branch-only
  deploys) so tag-triggered deploys aren't rejected by environment protection rules.

## Effect System
- **Cats Effect 3** — tagless-final, typeclass-based APIs throughout (`F[_]: Async`, etc.).

## HTTP
- **http4s** — server and client, for both services and purerest's resilient client.

## API Documentation
- **tapir** (1.11.25) — endpoints described once as tapir values; `purerest.docs` interprets
  that into both real `HttpRoutes[F]` and a generated OpenAPI spec + Swagger UI, so routes and
  docs can't drift apart.

## JSON
- **circe**, via `http4s-circe`.

## Persistence
- **PostgreSQL** via **Skunk** — chosen over Doobie for its pure-FP, no-JDBC fit (no dedicated
  blocking thread pool per query).
- **Flyway** migrations, run automatically on `order-service` startup (`pgjdbc` is a
  build-only dependency solely for Flyway).
- **PureConfig** for `application.conf`-based settings — every setting follows the same
  pattern: a default value plus a `${?ENV_VAR}` override line (e.g. `port`,
  `metrics-port`, `postgres`, and now `resilience`). For config types owned by a
  dependency rather than the service itself (e.g. purerest's `ResilienceConfig`),
  `ConfigReader` instances are derived locally in the consuming service — purerest
  itself has no PureConfig dependency.
- **Testcontainers** (`testcontainers-scala-postgresql`) for integration tests; a separate
  `docker-compose.yml` Postgres container for local/manual dev.

## Observability
- **Tracing**: OpenTelemetry via **otel4s** (`oteljava` backend) — W3C trace context
  propagated across service-to-service calls. Console exporter for local dev; in-memory
  testkit for automated tests. Real OTLP/collector export deferred.
- **Logging**: **log4cats** (slf4j backend) + **Logback** (needed to render MDC
  `trace_id`/`span_id` via its pattern layout) — structured logs correlated by trace id.
- **Metrics**: otel4s's `Meter[F]` — RED metrics (request rate/errors/duration) plus
  resilience signals (retry attempts, circuit-breaker state/transitions/rejections), exported
  via `opentelemetry-exporter-prometheus`; in-memory testkit for tests.

## Resilience
- **Retry**: **cats-retry** (4.0.0, Scala 3-only) supplies the backoff-policy composition
  (`limitRetries` + `exponentialBackoff`); actual retry *execution* is delegated to http4s's
  own `Retry` middleware, which already handles releasing each failed attempt's
  `Resource`-based connection/body safely — reinventing that would duplicate a solved problem.
- **Circuit breaker**: **resilience4j-circuitbreaker**'s core, non-reactive engine, wrapped as
  a pure combinator (`tryAcquirePermission`/`onResult`/`onError`, all synchronous/in-memory) —
  never exposed in purerest's public API. Count-based sliding window sized to
  `failureThreshold` at a 100% failure-rate threshold gives simple "N consecutive failures"
  semantics from a plain integer.
- Both exposed as composable `purerest.resilience` combinators, no annotations.

## Testing
- **munit** + **munit-cats-effect** (effectful test bodies return `IO[Unit]` directly).

## Load Testing
- **Gatling** (`modules/load-test`, its own `Gatling` sbt configuration — excluded from the
  root aggregate and the normal `sbt test`/`compile` loop). `OrderPlacementSimulation` drives
  sustained `POST /orders` traffic against a running stack, generating the volume needed to
  actually populate and validate purerest's RED + resilience metrics under real conditions —
  this is the primary vector for answering "is our resilience config actually effective?" (see
  `product.md`'s Iteration 2 goals).
- `inventory-service`'s induced-failure rate/delay is runtime-adjustable via `GET`/`PATCH
  /admin/induced-failure` (tapir-documented, Swagger UI at `/docs`), seeded at startup from
  `INVENTORY_INDUCED_FAILURE_RATE`/`INVENTORY_INDUCED_DELAY_MS`. `OrderPlacementSimulation`'s
  second scenario, `rampFailureRate`, drives this rate through `0.0 -> 0.6 -> 0.0` on a timed
  schedule concurrently with the order-placement traffic, reliably tripping and recovering
  order-service's circuit breaker within one continuous run.
- `scripts/verify-observability-stack.sh` runs both scenarios together against a fresh
  `--profile observability` stack and asserts, via Prometheus/Elasticsearch/Grafana, that a
  full CLOSED -> OPEN -> CLOSED breaker cycle, retries, and induced-failure logs all show up in
  the real data — not just that the containers started.

## Local Observability Stack
- Docker Compose `observability` profile: `order-service`/`inventory-service` (built as Docker
  images via sbt-native-packager) + Prometheus + Grafana (provisioned datasource + dashboard)
  + Elasticsearch/Kibana/Filebeat, with structured JSON container logging and an
  auto-provisioned Kibana Data View (`purerest-logs-*`). Plain `docker compose up -d` (no
  profile) is unaffected and still starts only Postgres.
- The Grafana dashboard (`purerest.json`) covers request rate, duration percentiles, error
  rate, retry attempts by outcome, circuit-breaker state/transitions/rejections, and
  order-service DB query duration/error rate, plus two panels built to show resilience
  *effectiveness* rather than just activity: "Circuit Breaker: State Timeline" (visual
  trip/recovery) and "Retry Success Rate" (successful-including-retried vs exhausted trend) —
  see README's "Build, run, and observe this system" for the full walkthrough.

## Formatting
- **scalafmt** — default Scala 3 style.

## Known Constraints

- **Transitive version drift once purerest is published externally.** Today all three modules
  share one `build.sbt`'s version `val`s, so drift between purerest and a consumer is
  structurally impossible. Once purerest is a real published jar (Iteration 2), that safety net
  disappears: each consumer becomes an independent second author of the same transitive
  dependency versions. `versionScheme := "early-semver"` (on purerest) and
  `ThisBuild / evictionErrorLevel := Level.Error` are in place as groundwork, but neither has
  been exercised against a real external consumer yet, and no decision has been made on further
  hardening (narrowing purerest's public API to avoid transitive diamonds, or adopting MiMa).
  Revisit when purerest is actually extracted and published.
- **Testcontainers/Docker API pin.** `modules/order-service/src/test/resources/docker-java.properties`
  (`api.version=1.44`) works around a known testcontainers-scala/docker-java incompatibility
  with newer Docker Desktop daemons. Remove once testcontainers-scala ships on Testcontainers 2.x.
- **Scaladoc jQuery injection.** `.github/workflows/release.yml`'s `deploy-docs` job `sed`-injects
  a jQuery CDN `<script>` tag into every generated `.html` file before deploying to GitHub Pages.
  Scala 3.9.0's Scaladoc removed the bundled jQuery script, but its generated `ux.js` still calls
  `$.get(...)` to intercept link clicks for AJAX-style navigation — without jQuery, every click
  silently fails (`$ is not defined`), though "open in new tab" still works since it bypasses the
  handler (known upstream regression, scala/scala3#22014). Remove once a Scala 3 release fixes
  this. Note: the separately-published `-javadoc.jar` Maven artifact is not patched by this
  workaround and still has the same broken navigation if browsed locally.
