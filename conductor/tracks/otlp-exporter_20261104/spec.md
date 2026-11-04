# Spec: PERF-5.2 — OTLP span exporter

## Overview
ADR 0021 (gluon): purerestlib exports spans over OTLP to a configurable endpoint with a
configurable sampling ratio, for Tempo (PERF-5.3). Today `Tracing.console` only logs spans
(`LoggingSpanExporter`); that stays the default when no endpoint is set.

## Functional Requirements
1. `TracingConfig(otlpEndpoint: Option[String], samplingRatio: Double)`, default
   `(None, 1.0)`. `TracingConfig.fromEnv` parses `OTEL_EXPORTER_OTLP_ENDPOINT` and
   `OTEL_TRACES_SAMPLER_ARG` from a `Map[String, String]`; a ratio that isn't a number in
   [0, 1] is an error (`Either`), not silently defaulted.
2. `Tracing.resource[F](serviceName, config)`:
   - endpoint set → OTLP/HTTP exporter (JDK HttpClient sender, no okhttp; it has no gRPC) + `BatchSpanProcessor`; endpoint is a base URL, `/v1/traces` appended
   - no endpoint → `LoggingSpanExporter` + `SimpleSpanProcessor` (today's behaviour)
   - sampler `parentBased(traceIdRatioBased(ratio))`
   - SDK resource carries `service.name = serviceName`
   - W3C trace-context propagator, as now
3. `Tracing.console(name)` kept, = `resource(name, TracingConfig.default)`.
4. Release v0.9.0 (new API; early-semver minor).

## Acceptance Criteria
- `TracingConfigSuite`: unset, endpoint set, ratios 0/0.5/1, non-numeric / out-of-range → Left.
- `TracingSuite`: ratio 0 records no spans; ratio 1 records; `service.name` on spans.
- Manual: a local OTel collector receives `demo-span` with `service.name` via OTLP.
- Eviction guard green; v0.9.0 published (release workflow green).

## Out of Scope
- Bumping services to `Tracing.resource` + chart env: gluon S13 (with Tempo).
- Generator template: S13 or S56.
