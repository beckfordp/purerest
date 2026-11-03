# Spec: TD-28 — JFR runtime telemetry off

## Overview
purerest 0.8.x's `Metrics.runtimeMetrics` calls `RuntimeMetrics.builder(otel).build()`
(`opentelemetry-runtime-telemetry-java17`), which turns on JFR streaming features
(allocation, network I/O, context switches, lock contention) as well as the JMX metrics.
On gluon-local (500m CPU, 384Mi pods) that left idle services using 0.2–0.4 cores. Every
service was CPU-throttled during acceptance and four were OOMKilled at rest (gluon TD-28).

## Functional Requirements
1. `Metrics.runtimeMetrics` builds runtime telemetry with every JFR feature disabled
   (`disableAllFeatures()`) and keeps the JMX metrics: `jvm.memory.*`, `jvm.gc.duration`,
   `jvm.thread.count`, `jvm.cpu.*` (time, count, recent_utilization), `jvm.class.*`.
2. No JFR-only metric is emitted: `jvm.memory.allocation`, `jvm.network.io`,
   `jvm.network.time`, `jvm.cpu.context_switch`, `jvm.cpu.longlock`.
3. Release v0.8.2.

## Acceptance Criteria
- `RuntimeMetricsSuite` asserts the JMX metrics are present and the JFR-only ones absent.
- v0.8.2 is published (release workflow green).

## Out of Scope
- Bumping the 8 services: done in each service's open `journey-telemetry_20261103`
  track, then verified in gluon (perf-work-sequence S1).
