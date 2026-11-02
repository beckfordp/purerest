# Spec — `purekafka`: Kafka resilience + observability module (ADR 0010)

## Overview
Nothing in `purerest` gives Kafka producers/consumers the same resilience
`purerest.resilience` already gives the one synchronous HTTP call —
`Resilience.middleware`/`Retry.middleware`/`CircuitBreaker.middleware`
are all typed `Client[F] => Client[F]`, http4s-specific, and can't wrap
a Kafka producer call or a consumer `Stream[F, O]`. This track adds
`modules/purekafka`, a new sibling module to `purerestlib` (not folded
into it, so `catalog-service`/`cart-service` stay free of `fs2-kafka`),
giving every Kafka-touching gluon service the same resilience/
observability bar with zero per-service forking — the direct fix for
`TECHNICAL_DEBT.md`'s TD-2.2–2.4.

## Functional Requirements
1. **Generic call resilience** — `Resilience.middleware[F, A]`, a new
   `F[A] => F[A]` sibling composing a generic `Retry` (cats-retry's
   `RetryPolicies`, same `RetryConfig(maxRetries, baseDelay)` shape,
   bounded retries) around a generic `CircuitBreaker` (resilience4j-core,
   same `CircuitBreakerConfig(failureThreshold, resetTimeout)` shape).
   Failure detection is **raised-error-only** — no value-level
   `isFailure` predicate (unlike the HTTP version's 5xx-as-value case; a
   Kafka producer call always raises on failure). Each (Retry,
   CircuitBreaker) carries its own otel4s metrics, same
   instrument-naming convention as the HTTP versions
   (`purekafka.retry.attempts`,
   `purekafka.circuit_breaker.state_transitions`/`calls_rejected`/
   `state`).
2. **Consumer stream supervision** — a combinator restarting a consumer
   `Stream[F, O]` on error with exponential backoff **capped at 30
   seconds**, unbounded attempts (not `RetryConfig`'s bounded shape —
   giving up recreates TD-2's exact symptom). No circuit breaker on this
   side. Own metric: a restart counter.
3. **Dead-letter routing** — a self-contained combinator (owns its own
   small Kafka producer internally, not caller-supplied) that, after a
   capped number of per-message processing retries, publishes the
   unprocessable record's original bytes to `<topic>-dlq` and lets the
   consumer stream continue with the next message instead of being
   killed or blocked. Own metric: a DLQ-publish counter.
4. **Cross-cutting observability**, layered on top of 1–3:
   - *Tracing*: inject/extract W3C trace context through Kafka message
     headers on produce/consume (OpenTelemetry messaging semantic
     conventions — `messaging.system=kafka`,
     `messaging.destination.name`), consumer spans linked to the
     producer span.
   - *Consumer lag*: a new otel4s gauge per topic/partition/
     consumer-group — no HTTP equivalent, the standard "is this
     consumer dead or just slow" signal.
   - *Logs*: log4cats, same MDC convention as HTTP (`trace_id`/
     `span_id`) — every Kafka log line additionally carries topic,
     partition, offset, `eventType`, `correlationId`.
5. **Load test** — new Gatling simulation in `modules/load-test`
   (sibling to `OrderPlacementSimulation.scala`): sustained
   produce/consume traffic plus induced failures — a poison message
   (proves DLQ routing fires), a paused consumer (proves the lag gauge
   climbs), a broker blip (proves stream supervision restarts it) —
   asserting the resulting metrics/traces/logs land correctly.

## Non-Functional Requirements
- No dependency on `purerestlib` itself — `purekafka` declares its own
  `cats-retry`/`resilience4j-core`/`fs2-kafka` deps directly, consistent
  with ADR 0010's "reusing the same dependencies," not "depending on
  purerestlib's resilience module."
- Every combinator is generic over key/value/payload types — no
  hardcoded topic names or service-specific branching anywhere in this
  module.

## Acceptance Criteria
- `Resilience.middleware[F, A]` retries transient failures and opens
  the breaker after `failureThreshold` consecutive failures, proven
  against a synthetic failing `F[A]`.
- A crashed consumer stream restarts with backoff capped at 30s,
  unbounded attempts, proven against a synthetic erroring
  `Stream[F, O]`.
- A poison message lands on `<topic>-dlq` after the configured retry
  cap, and the stream keeps consuming subsequent messages.
- Trace context survives a produce → consume hop (consumer span links
  to the producer span); consumer lag gauge reports a non-zero value
  when a consumer is paused with unconsumed messages waiting; Kafka log
  lines carry the documented MDC fields.
- The load-test simulation runs via `sbt loadTest/Gatling/test` and
  demonstrates all of the above under sustained traffic, not just in
  unit tests.

## Out of Scope
- **Kafka Connect/Debezium connector JMX metrics** (sidecar-based) —
  gluon-root infra work, not this module; explicitly excluded per
  discussion.
- AsyncAPI documentation — tracked as a separate backlog item/track,
  not bundled into this one.
- Any producer-resilience call site inside the three outbox-writing
  services (order/inventory/payment-service) — ADR 0008's outbox
  pattern already removed their direct-publish calls;
  `Resilience.middleware[F, A]` is a general-purpose building block for
  any *future* direct-publish use, not a retrofit of code that no
  longer exists.
- `pure-service-generator`'s generated examples (producer/consumer/
  outbox-CDC) — separate backlog item in that repo, sequenced after
  this one publishes.
