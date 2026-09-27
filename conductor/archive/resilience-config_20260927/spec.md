# Spec: Make order-service's resilience config configurable via application.conf

## Overview
order-service's `ResilienceConfig` (retry count/backoff, circuit-breaker failure
threshold/reset timeout) is currently a hardcoded `val` in `Main.scala`
(`RetryConfig(maxRetries = 3, baseDelay = 100.millis)`,
`CircuitBreakerConfig(failureThreshold = 5, resetTimeout = 30.seconds)`). This track
moves it into `application.conf`, loaded via PureConfig, matching the existing
`port`/`metrics-port`/`postgres` pattern (default value + `${?ENV_VAR}` override per
field). Iteration 3 goal 1.

## Functional Requirements
1. Add a `resilience { retry { ... }, circuit-breaker { ... } } ` block to
   `application.conf`, using HOCON duration strings (`100ms`, `30s`) for the two
   time-based fields, with the same default values as today (no behavior change unless
   explicitly overridden).
2. Add one env-var override per setting, matching the existing pattern:
   `ORDER_SERVICE_RESILIENCE_MAX_RETRIES`, `ORDER_SERVICE_RESILIENCE_BASE_DELAY`,
   `ORDER_SERVICE_RESILIENCE_FAILURE_THRESHOLD`, `ORDER_SERVICE_RESILIENCE_RESET_TIMEOUT`.
3. Extend `OrderServiceConfig` (in `OrderServiceConfig.scala`) with a `resilience:
   ResilienceConfig` field. purerest's `RetryConfig`/`CircuitBreakerConfig`/
   `ResilienceConfig` case classes don't derive `ConfigReader` themselves (purerest has
   no PureConfig dependency) — derive `ConfigReader` instances for them locally in
   `OrderServiceConfig.scala` instead (semi-auto derivation), keeping purerest itself
   free of a config-library dependency.
4. Remove the hardcoded `resilienceConfig` val from `Main.scala`; use `config.resilience`
   (from the already-loaded `OrderServiceConfig`) when constructing
   `Resilience.middleware`.

## Non-Functional Requirements
- No behavior change for anyone not overriding the new settings — defaults exactly match
  today's hardcoded values.
- purerest's own source is not modified — this is entirely an order-service-side change.
- Static, config-at-startup values (not live-adjustable without a restart) — matches
  Iteration 3's stated scope; live-adjustability remains a separate, bigger ask (parked
  under Non-Goals).

## Acceptance Criteria
- `OrderServiceConfigSuite` extended: loading a fully-specified HOCON fixture (including
  the new `resilience` block) produces the expected `ResilienceConfig`; loading the
  shipped `application.conf` produces the current default values
  (`maxRetries=3, baseDelay=100ms, failureThreshold=5, resetTimeout=30s`).
- `order-service` boots successfully with the new config block in place (existing
  integration tests continue to pass unchanged).
- `Main.scala` no longer has a hardcoded `ResilienceConfig`/`RetryConfig`/
  `CircuitBreakerConfig` literal.

## Out of Scope
- inventory-service (it never acts as a purerest HTTP client today, so it has no
  resilience config to expose).
- Live/runtime-adjustable resilience config (no restart) — a separate, bigger track if
  ever picked up.
- Any change to purerest's own `resilience` package.
