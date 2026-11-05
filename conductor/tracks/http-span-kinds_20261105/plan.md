# Plan: HTTP span kinds + client spans (gluon PERF-5.10)

## Phase 1: Tracing middleware
- [x] Task: Test + impl: `ClientTracing` Client-kind span, attributes, error status abd4d7f
- [x] Task: Test + impl: `ServerTracing` Server-kind span + attributes, 5xx Error abd4d7f
- [x] Task: Full `sbt scalafmtCheck test` (212 green; example trace gains the client span)

## Phase 2: Release
- [x] Task: Tag v0.14.0 (7050f77), `publishLocal`
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md):
      Redeployed in pairs (TD-31). Acceptance: run 1 seeding hit TD-26, run 2 51/53 during
      a host overload (load 48, k8s API down), run 3 53/53, ledger OK, 0 restarts. Tempo:
      checkout trace 88af97ce… order CLIENT → inventory/payment SERVER children, fulfilment
      CLIENT → order SERVER; dispatch trace a155c23f… notification CLIENT → order + user SERVER.

## Review
- No High/Medium. Low (accepted): server span names keep the raw path (ids in
  names); route templates out of scope. Client span ends on response release, so a
  caller that never drains the body extends the span — matches http4s semantics.
