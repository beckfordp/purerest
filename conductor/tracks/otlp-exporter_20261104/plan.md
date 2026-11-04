# Plan: PERF-5.2 — OTLP span exporter

## Phase 1: TracingConfig
- [x] Task: Write failing tests: `TracingConfigSuite` (unset, endpoint, ratios, invalid → Left) 7fb80cd
- [x] Task: Implement `TracingConfig` + `fromEnv` 7fb80cd
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) TracingConfigSuite 5/5 (prompt-off; covered again by verify-perf5-otlp.sh)

## Phase 2: OTLP exporter
- [x] Task: Add `opentelemetry-exporter-otlp` + `opentelemetry-exporter-sender-jdk` (okhttp excluded); eviction guard green 0fd9aed
- [ ] Task: Write failing tests: `TracingSuite` sampler (ratio 0 / 1) + `service.name` resource
- [ ] Task: Implement `Tracing.resource`; `console` delegates; `main` reads env
- [ ] Task: README tracing section
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md) `scripts/verify-perf5-otlp.sh` (local collector receives `demo-span`)

## Phase 3: Release v0.9.0
- [ ] Task: Push main, tag `v0.9.0`, watch release.yml green
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md) `scripts/verify-perf5-release.sh 0.9.0`
