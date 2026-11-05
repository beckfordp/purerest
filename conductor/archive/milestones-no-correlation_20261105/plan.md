# Plan: Milestones without correlation_id (gluon PERF-5.6)

## Phase 1: Milestones
- [x] Task: Test: line carries milestone, order_id, extra only 1323bf3
- [x] Task: Drop the `correlationId` parameter 1323bf3
- [x] Task: Full `sbt scalafmtCheck test` (205 tests, green)

## Phase 2: Release
- [x] Task: Tag v0.13.0 — released locally (`publishLocal`). The Actions release
      failed 5x in setup-sbt's ampel provenance download (carabiner-dev → policylabs
      redirect timing out), not our code; user chose local releases.
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md):
      5 services built and tested against 0.13.0; acceptance 53/53.

## Review
- No findings. Breaking signature, so minor bump; no other change since v0.12.0.
