# Plan: PERF-5.2 — OTLP span exporter

## Phase 1: TracingConfig
- [x] Task: Write failing tests: `TracingConfigSuite` (unset, endpoint, ratios, invalid → Left) 7fb80cd
- [x] Task: Implement `TracingConfig` + `fromEnv` 7fb80cd
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) TracingConfigSuite 5/5 (prompt-off; covered again by verify-perf5-otlp.sh)

## Phase 2: OTLP exporter
- [x] Task: Add `opentelemetry-exporter-otlp` + `opentelemetry-exporter-sender-jdk` (okhttp excluded); eviction guard green 08acd63
- [x] Task: Write failing tests: `TracingSuite` sampler (ratio 0 / 1) + `service.name` resource 70b8bc3
- [x] Task: Implement `Tracing.resource`; `console` delegates; `main` reads env 70b8bc3 (deviation: OTLP/HTTP :4318, not gRPC — JDK sender has no gRPC)
- [x] Task: README tracing section 6aebbc8
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md) `scripts/verify-perf5-otlp.sh` (local collector receives `demo-span`) PASS 3389724

## Phase 3: Release v0.9.0
- [x] Task: Push main, tag `v0.9.0`, watch release.yml green (run 41904280584: release + deploy-docs green)
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md) `scripts/verify-perf5-release.sh 0.9.0` PASS a4a3cc7

## Phase: Review Fixes
- [x] Task: Apply review suggestions f237df2 (High: `SamplingRatio` smart constructor, scala-fp §5; Low: blank ratio = unset)
- [~] Task: Release v0.10.0 (`SamplingRatio` changes the public field type)
