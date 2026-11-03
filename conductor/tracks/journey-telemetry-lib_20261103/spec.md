# Spec: Perf 4a — Cross-cutting journey telemetry (PERF-4.1–4.3, 4.11 helper)

## Overview
gluon PLAN.md Performance 4 adds telemetry along the order journey. The pieces every
service would otherwise duplicate live here: JVM + cats-effect runtime metrics, a
Kafka event-age histogram, DB connection-pool metrics and a milestone log helper.
Release v0.8.0. Per-service adoption and business metrics are one track per service repo.

## Functional Requirements
1. **PERF-4.1** `Metrics.oteljava` registers OTel JVM runtime telemetry
   (`opentelemetry-runtime-telemetry-java17`: heap, GC, threads, CPU, classes) on the
   SDK it builds, closed with the Resource. New optional `ioRuntimeMetrics` parameter
   (cats-effect `IORuntime.metrics`) also registers otel4s
   `IORuntimeMetrics` (compute pool, worker threads, CPU starvation). Default `None`,
   so existing callers compile unchanged.
2. **PERF-4.2** purekafka `EventAge`: `EventAge.create[F](meter)` builds histogram
   `purekafka.consumer.event.age` (unit `s`), boundaries 10ms–10min.
   `record(topic, groupId, recordTimestampMillis)` records now − timestamp, clamped ≥ 0,
   attributes `messaging.destination.name`, `messaging.consumer.group.name`.
3. **PERF-4.3** skunk's pool exposes no metrics, so purerestlib `PoolMetrics.instrument`
   wraps any pool `Resource[F, A]`: histogram `db.client.connection.wait_time` (s,
   LatencyBuckets), up/down counters `db.client.connection.count{state=used}` and
   `db.client.connection.pending_requests`, all tagged `db.client.connection.pool.name`.
   Generic in `A`, no skunk dependency.
4. **PERF-4.11** purerestlib `Milestones.log(logger)(milestone, orderId, correlationId,
   extra)`: an info line with fields `milestone`, `order_id`, `correlation_id`
   (+ extra), message `milestone <name>`. Order ids stay off metric labels.
5. purerest's demo `order-service` module adopts `PoolMetrics` (proves the shape).
6. Tag `v0.8.0` publishes purerestlib + purekafka 0.8.0.

## Non-Functional Requirements
- Backward compatible: no existing signature breaks.
- Coverage > 80% on new classes; scalafmt clean.
- Cardinality: no unbounded labels.

## Acceptance Criteria
- Testkit-based suites: runtime metrics appear (`jvm.memory.used`, `cats.effect.runtime.*`);
  event-age records the expected value and attributes; pool metrics count used/pending
  and record wait time, and release on finish and on error; milestone log carries fields.
- v0.8.0 resolves from GitHub Packages.

## Out of Scope
- Per-service adoption, business metrics, spans (service tracks), dashboard (PERF-4.12)
- OTLP export / Tempo (Perf 5)
