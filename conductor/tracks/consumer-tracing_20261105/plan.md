# Plan: consumer spans (gluon PERF-5.5)

## Phase 1: traced
- [x] Task: Test: span kind Consumer; null-valued traceparent header → root span, handler runs 08595b3 (null case already passed: decode failure skipped)
- [x] Task: `SpanKind.Consumer`; skip null/blank header values 08595b3
- [x] Task: Full `sbt scalafmtCheck test` (green)

## Phase 2: Release
- [x] Task: Tag v0.12.0, workflow green, jar downloadable (run 42022052887; jar has SpanKind)
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Review
- No findings. The null-header case already worked (UTF-8 decode of null bytes fails and is skipped); the test pins it.
