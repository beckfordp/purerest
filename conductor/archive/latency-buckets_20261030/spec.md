# Spec: Perf 3a — Latency histogram buckets (PERF-3.0)

## Overview
The `http.server.request.duration`, `http.client.request.duration` and per-service
`db.client.operation.duration` histograms record **seconds** but set no boundaries. They
get the OTel SDK defaults (0, 5, 10, 25 … 10000), which are sized for ms, so almost every
observation lands in the 0–5s bucket and p50/p95/p99 are meaningless. This track adds one
shared set of second-scale boundaries in purerestlib, releases it, and adopts it in all 8
services. It's the first half of gluon PLAN.md Performance 3. The obs stack
(PERF-3.1–3.4) is a separate gluon-performance track.

## Functional Requirements
1. purerestlib exposes `purerest.metrics.LatencyBuckets.seconds: BucketBoundaries`. The
   values run 5ms–10s and are dense from 250ms to 2.5s: 0.005, 0.01, 0.025, 0.05, 0.075,
   0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6, 0.75, 1, 1.25, 1.5, 2, 2.5, 3, 4, 5, 7.5, 10.
2. `ServerMetrics` and `ClientMetrics` histograms use it via `withExplicitBucketBoundaries`.
3. Release tag `v0.4.0` (sbt-dynver, release.yml) publishes purerestlib 0.4.0 to GitHub
   Packages.
4. All 8 services bump `purerestlibVersion` from 0.1.0 to 0.4.0. purekafka stays at 0.3.0
   (no histograms).
5. The 7 services that own a `db.client.operation.duration` histogram (cart, catalog,
   fulfilment, inventory, order, payment, user) apply `LatencyBuckets.seconds`.
   purerest's demo `order-service` module does the same.
6. Redeploy to gluon-local. Each service's `/metrics` shows `le="0.25"` etc. on HTTP (and
   DB where present) buckets.

## Non-Functional Requirements
- No other behaviour change. Instrument names and units stay the same.
- Per-service CI green (scalafmt, tests, coverage gate).
- Cardinality: 24 buckets × existing label sets. Acceptable locally; noted.

## Acceptance Criteria
- A purerestlib unit test (otel4s oteljava testkit) asserts the exact boundaries on server
  and client histograms.
- v0.4.0 is published; all 8 service CIs are green on the bump commits.
- `scripts/verify-*.sh` curls every service's metrics port in gluon-local and finds the
  new `le` values. A smoke `bin/run-perf SmokeSimulation` still passes.

## Out of Scope
- Prometheus/Grafana/ES/Kibana/Filebeat, `bin/obs` (PERF-3.1–3.4, next track)
- New metrics (Perf 4), Kafka event-age histogram (PERF-4.2)
