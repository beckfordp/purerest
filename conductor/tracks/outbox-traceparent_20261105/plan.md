# Plan: `KafkaTracing.traceParent` (gluon PERF-5.4)

## Phase 1: Helper
- [x] Task: Test: `traceParent` inside a span is the span's W3C traceparent; outside is `None` 881cd65
- [x] Task: Implement `KafkaTracing.traceParent` 881cd65
- [x] Task: README: outbox usage line 881cd65
- [x] Task: Full `sbt scalafmtCheck test` (green)

## Phase 2: Release
- [ ] Task: Tag v0.11.0, release workflow green, purekafka 0.11.0 downloadable
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)
