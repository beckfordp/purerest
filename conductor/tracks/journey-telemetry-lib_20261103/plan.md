# Plan: Perf 4a — Cross-cutting journey telemetry

## Phase 1: purerestlib runtime + pool metrics + milestones
- [ ] Task: Write failing tests: `RuntimeMetricsSuite` (JVM + IORuntime metric names via a
      `Metrics.oteljava`-equivalent test path), `PoolMetricsSuite` (used/pending counts,
      wait-time histogram, release on error), `MilestonesSuite` (log fields)
- [ ] Task: Implement: add runtime-telemetry + otel4s-instrumentation-metrics deps,
      `Metrics.oteljava(…, ioRuntimeMetrics)`, `PoolMetrics.instrument`, `Milestones.log`
- [ ] Task: Demo `order-service` module wraps its pool with `PoolMetrics`
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: purekafka event age
- [ ] Task: Write failing test `EventAgeSuite` (value, clamp, attributes, boundaries)
- [ ] Task: Implement `purekafka.consumer.EventAge`
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Release v0.8.0
- [ ] Task: Push main, tag `v0.8.0`, watch release.yml green
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Risks
- runtime-telemetry 2.27.0-alpha targets OTel API 1.61; build pins SDK 1.66 (upward
  eviction, API compatible). evictionErrorLevel may need a pin
- IORuntimeMetrics needs `IORuntime.metrics`; tests use `cats.effect.unsafe.implicits.global`
