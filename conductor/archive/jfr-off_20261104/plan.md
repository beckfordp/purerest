# Plan: TD-28 — JFR runtime telemetry off

## Phase 1: JMX-only runtime metrics
- [x] Task: Write failing test: `RuntimeMetricsSuite` asserts the JFR-only metrics are absent (JMX still present) acf91e7 (red: longlock, allocation, context_switch)
- [x] Task: Implement: `RuntimeMetrics.builder(...).disableAllFeatures()` acf91e7
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) `scripts/verify-perf4a.sh` PASS

## Phase 2: Release v0.8.2
- [x] Task: Push main, tag `v0.8.2`, watch release.yml green (run 41658593082: release + deploy-docs green)
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md) `scripts/verify-perf4a-release.sh 0.8.2` PASS
