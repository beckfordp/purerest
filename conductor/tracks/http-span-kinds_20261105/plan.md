# Plan: HTTP span kinds + client spans (gluon PERF-5.10)

## Phase 1: Tracing middleware
- [x] Task: Test + impl: `ClientTracing` Client-kind span, attributes, error status abd4d7f
- [x] Task: Test + impl: `ServerTracing` Server-kind span + attributes, 5xx Error abd4d7f
- [x] Task: Full `sbt scalafmtCheck test` (212 green; example trace gains the client span)

## Phase 2: Release
- [ ] Task: Tag v0.14.0, `publishLocal`
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)
