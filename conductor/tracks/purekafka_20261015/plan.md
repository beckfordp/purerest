# Plan — `purekafka`: Kafka resilience + observability module (ADR 0010)

## Phase 1 — Generic call resilience [checkpoint: 810e008]
- [x] Task: Write failing `RetrySuite` test: generic `Retry.middleware[F, A]` retries a failing `F[A]` up to `maxRetries` then gives up, raised-error-only (Red) `57bec6f`
- [x] Task: Implement generic `Retry.middleware[F, A]` (cats-retry `RetryPolicies`, same `RetryConfig` shape, own `purekafka.retry.attempts` metric) (Green) `57bec6f` — deviation: introduced a shared `Guard[F]` trait (construct once, `.apply[A]` many times) instead of a literal `F[A] => F[A]` function value; see git note on `57bec6f`
- [x] Task: Write failing `CircuitBreakerSuite` test: breaker opens after `failureThreshold` consecutive raised errors, rejects with `CircuitBreakerOpen` until `resetTimeout` elapses (Red) `359102b`
- [x] Task: Implement generic `CircuitBreaker.middleware[F, A]` (resilience4j-core, same `CircuitBreakerConfig` shape, state-transition/rejection/state metrics) (Green) `359102b`
- [x] Task: Write failing `ResilienceSuite` test: `Resilience.middleware[F, A]` composes Retry (outer) + CircuitBreaker (inner); a breaker-open rejection isn't itself retried (Red) `e6a5b18`
- [x] Task: Implement `Resilience.middleware[F, A]` composition (Green) `e6a5b18` — also required adding a `nonRetriable` hook to `Retry.middleware` (see git note on `e6a5b18`); first draft of the "breaker opens" test had incorrect arithmetic, corrected before this commit
- [x] Task: Conductor - User Manual Verification 'Phase 1: Generic call resilience' (Protocol in workflow.md)

## Phase 2 — Consumer stream supervision [checkpoint: 01e08f9]
- [x] Task: Write failing `StreamSupervisorSuite` test: an erroring `Stream[F, O]` restarts with exponential backoff, capped at 30s, unbounded attempts (Red) `92b95a1` — deviation: committed separately from the Green step below (Phase 1 committed Red+Green pairs together); see git note on `92b95a1`
- [x] Task: Implement the stream-supervision combinator, own restart-counter metric (Green) `a08383d` — required `Async[F]` rather than `Temporal[F]` as the bound (`Ref.unsafe` needs `Sync[F]`); see git note on `a08383d`
- [x] Task: Conductor - User Manual Verification 'Phase 2: Consumer stream supervision' (Protocol in workflow.md)

## Phase 3 — Dead-letter routing [checkpoint: a058e73]
- [x] Task: Write failing `DeadLetterSuite` test: after a capped number of per-message retries, the record is published to `<topic>-dlq` and the stream continues with the next message (Red) `3dd0217`
- [x] Task: Implement the self-contained dead-letter combinator (owns its own small producer internally), own DLQ-publish-counter metric (Green) `4a06a76` — reuses `purekafka.resilience.Retry`/`Guard` internally for the bounded-retry half rather than hand-rolling a second mechanism; split into `DeadLetter.fromPublish` (testable core logic, fake publish function) + `DeadLetter.resource` (real fs2-kafka-backed constructor) so the decision logic is tested without a real broker
- [x] Task: Conductor - User Manual Verification 'Phase 3: Dead-letter routing' (Protocol in workflow.md) `a058e73` — reduced-prompting mode: automated-test verification only (119 passed), no interactive walkthrough; see git note on `a058e73`

## Phase 4 — Cross-cutting observability
- [x] Task: Write failing tracing test: producer injects W3C trace context into Kafka message headers; consumer extracts it and links its span to the producer's (Red) `5d9cc55`
- [x] Task: Implement produce/consume trace-context propagation (Green) `5d9cc55` — deviation: committed together with the test (Phase 1's style), not split (Phase 2/3's style); see git note on `5d9cc55`
- [ ] Task: Write failing consumer-lag test: the lag gauge reports correctly for a topic/partition/consumer-group with unconsumed messages (Red)
- [ ] Task: Implement the consumer-lag gauge (Green)
- [ ] Task: Write failing structured-logging test: a Kafka log line carries topic/partition/offset/`eventType`/`correlationId` (Red)
- [ ] Task: Implement the structured-logging helper (Green)
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Cross-cutting observability' (Protocol in workflow.md)

## Phase 5 — Load test
- [ ] Task: Write a new Gatling simulation in `modules/load-test` (sibling to `OrderPlacementSimulation.scala`): sustained produce/consume traffic plus three induced failures (poison message, paused consumer, broker blip)
- [ ] Task: Run it (`sbt loadTest/Gatling/test`), confirm DLQ routing fires, the lag gauge climbs, and stream supervision restarts correctly under real load
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Load test' (Protocol in workflow.md)
