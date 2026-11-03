# Plan: TD-28 — JFR runtime telemetry off

## Phase 1: JMX-only runtime metrics
- [ ] Task: Write failing test: `RuntimeMetricsSuite` asserts the JFR-only metrics are absent (JMX still present)
- [ ] Task: Implement: `RuntimeMetrics.builder(...).disableAllFeatures()`
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: Release v0.8.2
- [ ] Task: Push main, tag `v0.8.2`, watch release.yml green
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)
