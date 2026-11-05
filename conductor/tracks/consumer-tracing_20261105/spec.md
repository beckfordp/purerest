# Spec: consumer spans (gluon PERF-5.5)

## Overview
gluon consumers will wrap each record in `KafkaTracing.traced`. Two gaps:
the span is kind INTERNAL (OTel messaging says CONSUMER), and Debezium emits a
`traceparent` header with a **null value** when the outbox column is NULL.

## Functional Requirements
1. `traced` builds its span with `SpanKind.Consumer`.
2. A null- or blank-valued header is ignored: handling runs in a new root span.
3. Release v0.12.0.

## Acceptance Criteria
- Tests: consumer span kind is Consumer; null-valued `traceparent` → root span, `fa` runs.
- `sbt scalafmtCheck test` green; 0.12.0 published.
