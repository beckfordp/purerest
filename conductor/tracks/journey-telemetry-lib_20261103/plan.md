# Plan: Perf 4a — Cross-cutting journey telemetry

## Phase 1: purerestlib runtime + pool metrics + milestones [checkpoint: 5000026]
- [x] Task: Write failing tests: `RuntimeMetricsSuite` (JVM + IORuntime metric names via a
      `Metrics.oteljava`-equivalent test path), `PoolMetricsSuite` (used/pending counts,
      wait-time histogram, release on error), `MilestonesSuite` (log fields) c3c0bce
- [x] Task: Implement: add runtime-telemetry + otel4s-instrumentation-metrics deps,
      `Metrics.oteljava(…, ioRuntimeMetrics)`, `PoolMetrics.instrument`, `Milestones.log` c3c0bce
- [x] Task: Demo `order-service` module wraps its pool with `PoolMetrics` 6bccc33
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) `scripts/verify-perf4a.sh` 5000026

## Phase 2: purekafka event age [checkpoint: 5000026]
- [x] Task: Write failing test `EventAgeSuite` (value, clamp, attributes, boundaries) ab07cbb
- [x] Task: Implement `purekafka.consumer.EventAge` ab07cbb
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md) `scripts/verify-perf4a.sh` 5000026

## Phase 3: Release v0.8.0 [checkpoint: 90a65b8]
- [x] Task: Push main, tag `v0.8.0`, watch release.yml green (run 41622779042: release + deploy-docs green)
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md) `scripts/verify-perf4a-release.sh` 90a65b8

## Risks
- runtime-telemetry 2.27.0-alpha targets OTel API 1.61; build pins SDK 1.66 (upward
  eviction, API compatible). Hit: api-incubator 1.61 lacks classes the 1.66 SDK needs
  (NoClassDefFoundError BoundLongCounter) — pinned `opentelemetry-api-incubator` 1.66 as a
  direct dependency so services inherit it
- IORuntimeMetrics needs `IORuntime.metrics`; tests use `cats.effect.unsafe.implicits.global`
