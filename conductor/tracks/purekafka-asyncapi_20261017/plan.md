# Plan — AsyncAPI documentation for `modules/purekafka`

## Phase 1 — Registration primitive [checkpoint: be7eec0]
- [x] Task: Write failing `AsyncApiChannelSuite` test: `AsyncApiChannel[A](topic, eventType)` constructs from an implicit tapir `Schema[A]`, exposing topic/eventType/schema (Red) `4a3b023`
- [x] Task: Implement `AsyncApiChannel[A]` in `purekafka.docs` — generic, no kafka-harness-specific coupling (Green) `4a3b023` — committed Red+Green together; deviation: dropped a custom companion `apply` that collided with the case class's auto-generated one, see git note on `4a3b023`
- [x] Task: Conductor - User Manual Verification 'Phase 1: Registration primitive' (Protocol in workflow.md) `be7eec0` — reduced-prompting mode: automated-test verification only (126 passed), no interactive walkthrough; see git note on `be7eec0`

## Phase 2 — Document generation [checkpoint: c422c20]
- [x] Task: Write failing `AsyncApiDocSuite` test: generating from a single-channel list produces a valid AsyncAPI 2.x document (circe `Json`) with the correct channel name, a `subscribe`/`publish` operation, and a JSON-Schema-shaped payload matching the registered `Schema[A]` (Red) `fece777`
- [x] Task: Implement the hand-rolled AsyncAPI 2.x document builder (Green) `fece777` — deviation: not hand-rolled; `tapir-apispec-docs` transitively ships `asyncapi-model`, a real typed AsyncAPI 2.6.0 model from the same library family, used instead via `asyncapi-circe`'s `Encoder[AsyncAPI]`. See git note on `fece777`.
- [x] Task: Write failing test: multiple registered channels each appear as distinct entries (Red) `fece777` — committed together with the single-channel test/implementation above, not split
- [x] Task: Extend the builder to fold over `List[AsyncApiChannel[_]]` (Green) `fece777`
- [x] Task: Conductor - User Manual Verification 'Phase 2: Document generation' (Protocol in workflow.md) `c422c20` — reduced-prompting mode: automated-test verification only (128 passed across all 4 modules, `scalafmtCheck` clean), no interactive walkthrough; see git note on `c422c20`

## Phase 3 — Serving the raw spec
- [x] Task: Write failing `DocsSuite` test: `purekafka.docs.Docs.routes(title, version, channels)` serves the generated spec as YAML/JSON at a fixed route, 200 + correct content-type — signature takes an explicit channel list, like `purerest.docs.Docs.routes` takes an explicit endpoint list (Red) `a6366ae` — confirmed Red via a real compile error (`Docs` didn't exist yet), not just a failing assertion
- [x] Task: Implement `purekafka.docs.Docs.routes[F]` (Green) `a6366ae` — committed Red+Green together; deviation: serves JSON at `/docs/asyncapi.json`, not YAML — spec.md's own wording allowed either ("asyncapi.yaml/.json"), and JSON avoids adding a new YAML-encoding dependency this phase didn't otherwise need; see git note on `a6366ae`
- [x] Task: Conductor - User Manual Verification 'Phase 3: Serving the raw spec' (Protocol in workflow.md) — reduced-prompting mode: automated-test verification only (130 passed across all 4 modules, `scalafmtCheck` clean), no interactive walkthrough

## Phase 4 — Reference consumer (`kafka-harness` retrofit)
- [ ] Task: Introduce `DemoEvent(message: String)` (circe codec + tapir `Schema`) replacing `kafka-harness`'s raw `String` payload
- [ ] Task: Register `kafka-harness-demo`'s topic/eventType/`DemoEvent` schema via `AsyncApiChannel`
- [ ] Task: Add an HTTP server to `kafka-harness` (currently metrics-only) mounting `purekafka.docs.Docs`'s raw-spec route (e.g. `/docs/asyncapi.yaml`)
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Reference consumer' (Protocol in workflow.md)

## Phase 5 — Static HTML generation, serving, verification + docs
- [ ] Task: Write `scripts/generate-kafka-asyncapi-docs.sh`: fetch the running spec, run `npx @asyncapi/generator` + `@asyncapi/html-template` to render static HTML to a known output path
- [ ] Task: Add a second `kafka-harness` route (e.g. `/docs/asyncapi`) serving that generated HTML file as a static asset — sibling to the raw-spec route, same split as Swagger's `/docs` vs `/docs/docs.yaml`
- [ ] Task: Write `scripts/verify-kafka-asyncapi-docs.sh`: run the generation script end-to-end and assert the demo topic/eventType/`message` field appear in both the raw spec and the served HTML
- [ ] Task: Update README with a "View Kafka AsyncAPI docs" section, and note order-service/`pure-service-generator` migration as planned future work
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Static HTML generation, serving, verification + docs' (Protocol in workflow.md)
