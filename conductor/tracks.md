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
  `cats-retry`/resilience4j-core deps — now mostly a general-purpose
  building block, since gluon's outbox pattern (ADR 0008, done) removed
  every current direct-publish call site this would otherwise wrap);
  consumer stream supervision (restart-with-backoff on error, unbounded
  attempts, no circuit breaker — `RetryConfig`'s bounded shape doesn't
  fit a background consumer); dead-letter routing after a capped number
  of per-message retries; observability extending the same
  tracing/metrics/logs `purerestlib` already gives HTTP across the
  Kafka boundary (W3C trace context via message headers, otel4s
  consumer-lag gauge, log4cats logs carrying
  topic/partition/offset/eventType/correlationId); and a new
  `modules/load-test` simulation (sibling to the existing
  `OrderPlacementSimulation.scala`) proving the observability signals
  — lag gauge, DLQ rate, restart-with-backoff, trace continuity — are
  real under sustained load, not just asserted in a unit test.
  **Reusability is explicitly a first-class goal**: every Kafka-touching
  gluon service should get this with zero per-service forking, same bar
  `purerestlib` already holds for HTTP. Decided in gluon's
  [ADR 0010](https://github.com/beckfordp/gluon/blob/main/docs/adr/0010-purekafka-module-for-kafka-resilience-observability.md)
  (Proposed) — full context/alternatives/consequences there, including
  its interaction with gluon's
  [ADR 0008](https://github.com/beckfordp/gluon/blob/main/docs/adr/0008-outbox-pattern-via-debezium-cdc.md)
  (outbox/Debezium CDC, done) on how tracing/metrics reconcile once a
  service publishes via CDC instead of a direct producer call. Sequenced
  before, not after, gluon's schema-registry rework (Rework 5) — not a
  hard dependency either way, see `gluon/PLAN.md`'s Rework 3 entry for
  the resequencing note.
- AsyncAPI documentation for `modules/purekafka` — generate an AsyncAPI
  document from a service's own Kafka producer/consumer registrations
  (topics, `eventType`s, payload schemas) and serve it the way tapir
  already serves OpenAPI/Swagger at `/docs`, giving Kafka topics the
  same self-documenting, explorable status REST endpoints already have.
  Payload schemas reuse whatever JSON-Schema-shaped derivation gluon's
  schema-format decision settles on (gluon's
  [ADR 0003](https://github.com/beckfordp/gluon/blob/main/docs/adr/0003-kafka-schema-registry.md),
  JSON Schema — plausibly reusing tapir's own `Schema[T]` machinery,
  already JSON-Schema-shaped). Scoped to documentation, not
  interaction — pair with a Kafka UI tool (AKHQ, Redpanda Console) for
  actually watching/publishing live messages, not something this module
  needs to build. Separate backlog item from the core module above
  since it's a distinct concern (docs generation, not resilience) that
  can land independently; see gluon's ADR 0010 for the full writeup.

---
