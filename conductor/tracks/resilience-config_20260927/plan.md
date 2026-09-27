# Plan: Make order-service's resilience config configurable via application.conf

## Phase 1: Config schema + application.conf
- [ ] Task: Write failing tests (Red) — extend `OrderServiceConfigSuite`'s "loads a fully-specified config" test to include a `resilience {...}` block + the expected `ResilienceConfig`, and extend "reads the shipped application.conf defaults" to assert the new defaults (`maxRetries=3, baseDelay=100ms, failureThreshold=5, resetTimeout=30s`). Confirm both fail since `OrderServiceConfig` has no `resilience` field yet.
- [ ] Task: Implement (Green) — add local `ConfigReader` derivations for `RetryConfig`/`CircuitBreakerConfig`/`ResilienceConfig` in `OrderServiceConfig.scala`, add the `resilience: ResilienceConfig` field, add the `resilience { retry {...}, circuit-breaker {...} }` block (HOCON duration strings, one `${?ENV_VAR}` override per field) to `application.conf`. Run the suite, confirm all tests pass.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Config schema + application.conf' (Protocol in workflow.md).

## Phase 2: Wire into Main.scala and verify overrides take effect
- [ ] Task: Remove the hardcoded `resilienceConfig` val from `Main.scala`; pass `config.resilience` into `Resilience.middleware` instead. Run `sbt test` — confirm the full suite (including `OrderServiceIntegrationSuite`/`OrderServicePostgresIntegrationSuite`) still passes unchanged (defaults match today's behavior exactly).
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Wire into Main.scala and verify overrides take effect' (Protocol in workflow.md) — the manual verification step itself will start order-service with a non-default `ORDER_SERVICE_RESILIENCE_FAILURE_THRESHOLD` override and confirm the circuit breaker trips at the new threshold instead of the default, proving the override is genuinely wired end-to-end (not just parsed by the Phase 1 unit tests).
