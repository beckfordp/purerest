# Spec: `KafkaTracing.traceParent` (gluon PERF-5.4)

## Overview
gluon services publish through a transactional outbox (ADR 0008): the row's
`trace_parent` column becomes a Kafka header via Debezium. Services need the
active span's W3C `traceparent` as a plain value to write into that column —
`injectHeaders` only produces fs2-kafka `Headers`.

## Functional Requirements
1. `KafkaTracing.traceParent(tracer): F[Option[String]]` — the active span's
   W3C `traceparent` (via `tracer.propagate`), `None` when no span is active.
2. Release v0.11.0 (all modules) so services can depend on it.

## Acceptance Criteria
- Inside a span: `Some` value matching `00-<32 hex>-<16 hex>-<2 hex>` whose trace
  id is the span's; outside: `None`.
- `sbt scalafmtCheck test` green; v0.11.0 purekafka jar downloadable from
  GitHub Packages with `KafkaTracing`.

## Out of Scope
- Writing the column (services), Debezium header alias (gluon), consumers (PERF-5.5).
