# Project Tracks

This file tracks all major tracks for the project.

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track below.

- New `modules/purekafka` module (sibling to `purerestlib`, not folded
  into it — gluon services that don't touch Kafka shouldn't pull in
  `fs2-kafka` transitively). Scope: generic `F[A] => F[A]` call
  resilience for a direct Kafka publish (new sibling to
  `purerest.resilience.Resilience.middleware`, reusing the same
  `cats-retry`/resilience4j-core deps); consumer stream supervision
  (restart-with-backoff on error, unbounded attempts, no circuit
  breaker — `RetryConfig`'s bounded shape doesn't fit a background
  consumer); dead-letter routing after a capped number of per-message
  retries; and observability extending the same tracing/metrics/logs
  `purerestlib` already gives HTTP across the Kafka boundary (W3C trace
  context via message headers, otel4s consumer-lag gauge, log4cats
  logs carrying topic/partition/offset/eventType/correlationId).
  Decided in gluon's
  [ADR 0010](https://github.com/beckfordp/gluon/blob/main/docs/adr/0010-purekafka-module-for-kafka-resilience-observability.md)
  (Proposed) — full context/alternatives/consequences there, including
  its interaction with gluon's
  [ADR 0008](https://github.com/beckfordp/gluon/blob/main/docs/adr/0008-outbox-pattern-via-debezium-cdc.md)
  (outbox/Debezium CDC) on how tracing/metrics reconcile once a service
  publishes via CDC instead of a direct producer call.

---
