# Spec — AsyncAPI documentation for `modules/purekafka`

## Overview
`purekafka` (ADR 0010) gives Kafka producers/consumers resilience and
observability, but — unlike purerest's tapir-based HTTP endpoints, which
are self-documenting (`purerest.docs.Docs` turns a tapir endpoint list
into real routes + an always-in-sync OpenAPI spec + Swagger UI) — Kafka
topics/eventTypes/payloads have no equivalent. `kafka-harness` (the only
current Kafka producer/consumer in this repo) just calls
`KafkaProducer`/`KafkaConsumer` directly against a raw `String` topic
name, with no registration or schema concept at all. This track adds a
new `purekafka.docs` package giving Kafka topics the same
self-documenting status, and proves it end-to-end by retrofitting
`kafka-harness` to register its own topic/eventType/payload and serve
the result — both as a raw spec and as rendered HTML, mirroring
Swagger's `/docs` (UI) vs `/docs/docs.yaml` (spec) split.

## Functional Requirements
1. **Registration primitive** — a new type (e.g.
   `AsyncApiChannel[A](topic: String, eventType: String)(implicit
   schema: Schema[A])`) describing one topic's publish/subscribe
   contract, reusing tapir's own `Schema[A]` derivation for the payload
   shape (already in scope via tapir, same JSON-Schema-shaped output
   gluon's ADR 0003 settled on) — no new schema machinery.
2. **Document generation** — a pure function building a valid AsyncAPI
   2.x document (circe `Json`, hand-rolled — channels, publish/subscribe
   operations, payload schemas) from a `List[AsyncApiChannel[_]]`. No
   third-party AsyncAPI generation library (JVM/Scala tooling here is
   sparse); mirrors this repo's pattern of wrapping/encoding against
   established *formats*, not frameworks, where no good library fit
   exists.
3. **Serving the raw spec** — `purekafka.docs.Docs.routes(title,
   version, channels)`, mirroring `purerest.docs.Docs.routes`'s shape
   (explicit list in, `HttpRoutes[F]` out), serves the generated
   `asyncapi.yaml`/`.json` at a fixed route (e.g. `/docs/asyncapi.yaml`).
   (`purekafka` gains an `http4s`/`tapir` dependency for this package —
   accepted tradeoff per module-placement decision.)
4. **Static HTML docs** — a new script (e.g.
   `scripts/generate-kafka-asyncapi-docs.sh`) that dumps the generated
   spec to a file, then runs the official `@asyncapi/generator` +
   `@asyncapi/html-template` (Node.js/npm, invoked via `npx`) to render
   static HTML locally — the AsyncAPI-world counterpart to this repo's
   existing Scaladoc generation (`sbt purerestlib/doc`), run on-demand
   rather than wired into CI/GitHub Pages.
5. **Serving the HTML** — a second route on `kafka-harness` (e.g.
   `/docs/asyncapi`), sibling to the raw-spec route, serving the
   generated static HTML file as a static asset — so viewing the docs
   is a URL on the running service, the same experience as Swagger UI
   or the Grafana dashboard left running by
   `verify-kafka-observability.sh`. The HTML itself stays static
   (generated once by the script, not regenerated per request).
6. **Reference consumer** — `kafka-harness` registers its
   `kafka-harness-demo` topic/eventType via the new primitive (payload
   upgraded from a raw `String` to a small case class, e.g.
   `DemoEvent(message: String)`, so the generated schema has a
   non-trivial field to show), gains an HTTP server (it currently only
   exposes a metrics port), and mounts both routes above.
7. **Verification script** — `scripts/verify-kafka-asyncapi-docs.sh`
   (new, alongside the existing `verify-kafka-observability.sh`)
   automates: generate spec -> generate HTML -> assert the topic name,
   eventType, and payload field show up in both the raw spec and the
   served HTML.

## Non-Functional Requirements
- No dependency on `purerestlib` — consistent with purekafka's existing
  module-independence NFR (ADR 0010).
- Every registration/generation combinator generic over payload type —
  no hardcoded topic names inside `purekafka.docs` itself (hardcoding is
  confined to `kafka-harness`'s own registration, as the demo consumer).
- `AsyncApiChannel[A]` and `purekafka.docs.Docs.routes` stay generic and
  portable — no `kafka-harness`-specific coupling — so order-service and
  later `pure-service-generator` can register their own channels later
  without rework (see Out of Scope).
- Node.js/npm is a new, optional, script-invoked build-time tool for
  this repo (not added to `sbt test`/CI's gate) — isolated to the one
  new script.

## Acceptance Criteria
- `purekafka.docs` generates a valid AsyncAPI 2.x document from a list
  of registered channels, covering channel name, pub/sub operation, and
  JSON-Schema-shaped payload, proven against a synthetic registration
  list in a unit test.
- `kafka-harness` serves its generated spec at a documented raw-spec
  route and the generated static HTML at a sibling documented route.
- `scripts/generate-kafka-asyncapi-docs.sh` produces static HTML via
  the AsyncAPI Generator from the running/generated spec.
- `scripts/verify-kafka-asyncapi-docs.sh` runs both generation steps and
  asserts the demo topic/eventType/payload field are present in both
  the raw spec and the served HTML.
- README documents how to view the generated docs.

## Out of Scope
- Kafka Connect/Debezium JMX metrics (inherited exclusion, ADR 0010).
- Interactive publish/consume from the docs UI — pair with AKHQ/Redpanda
  Console instead (per original backlog item).
- Registering real production topics for order/inventory/payment-service
  — scope is `purekafka`'s API + `kafka-harness` as the sole reference
  consumer, same role it already plays for purekafka's resilience
  features.
- **Migrating order-service to this API, and retrofitting
  `pure-service-generator` with the same capability, are explicitly
  planned future work** — not bundled into this track, but this track's
  API must not foreclose that migration (hence the portability NFR
  above). order-service doesn't call Kafka directly today (ADR 0008's
  outbox pattern already removed its direct-publish call sites), so
  that migration will first need to decide what, if anything, there is
  to register for an outbox/CDC-published topic — a question for that
  future track, not this one.
- CI-wired/GitHub-Pages-deployed static HTML — local, script-generated
  only for now (unlike Scaladoc's deploy pipeline).
- AsyncAPI 3.x — targets 2.x for widest current tool support.
