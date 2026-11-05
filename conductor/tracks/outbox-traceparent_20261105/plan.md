# Plan: `KafkaTracing.traceParent` (gluon PERF-5.4)

## Phase 1: Helper
- [ ] Task: Test: `traceParent` inside a span is the span's W3C traceparent; outside is `None`
- [ ] Task: Implement `KafkaTracing.traceParent`
- [ ] Task: README: outbox usage line
- [ ] Task: Full `sbt scalafmtCheck test`

## Phase 2: Release
- [ ] Task: Tag v0.11.0, release workflow green, purekafka 0.11.0 downloadable
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)
