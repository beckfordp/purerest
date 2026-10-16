# Project Tracks

This file tracks all major tracks for the project.

- [x] **Track: `purekafka`: Kafka resilience + observability module (ADR 0010)**
  *Link: [./tracks/purekafka_20261015/](./tracks/purekafka_20261015/)*

---

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track below.

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
