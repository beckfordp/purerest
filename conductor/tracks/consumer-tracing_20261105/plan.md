# Plan: consumer spans (gluon PERF-5.5)

## Phase 1: traced
- [ ] Task: Test: span kind Consumer; null-valued traceparent header → root span, handler runs
- [ ] Task: `SpanKind.Consumer`; skip null/blank header values
- [ ] Task: Full `sbt scalafmtCheck test`

## Phase 2: Release
- [ ] Task: Tag v0.12.0, workflow green, jar downloadable
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)
