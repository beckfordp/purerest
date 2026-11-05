# Plan: HTTP span kinds + client spans (gluon PERF-5.10)

## Phase 1: Tracing middleware
- [ ] Task: Test + impl: `ClientTracing` Client-kind span, attributes, error status
- [ ] Task: Test + impl: `ServerTracing` Server-kind span + attributes
- [ ] Task: Full `sbt scalafmtCheck test`

## Phase 2: Release
- [ ] Task: Tag v0.14.0, `publishLocal`
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)
