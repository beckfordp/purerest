# Plan: Perf 3a — Latency histogram buckets

## Phase 1: Shared boundaries in purerestlib [checkpoint: e09b00e]
- [x] Task: Write failing tests: `LatencyBucketsSuite` checks the exact values, ascending
      order and 5ms–10s range. `ServerMetricsSuite` and `ClientMetricsSuite` use the
      oteljava testkit to assert that recorded histograms carry these boundaries. 916f908
- [x] Task: Implement `purerest.metrics.LatencyBuckets.seconds` and wire
      `withExplicitBucketBoundaries` into ServerMetrics/ClientMetrics. Update the Scaladoc. 916f908
- [x] Task: Apply the boundaries to the demo `order-service` module's `OrderStore` DB
      histogram. c71b934
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md).
      `scripts/verify-perf3a-phase1.sh` runs the full sbt check: fmt, test, coverage. e09b00e

## Phase 2: Release v0.4.0
- [ ] Task: Push main, tag `v0.4.0` and push the tag. Watch release.yml to green.
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md).
      `scripts/verify-perf3a-phase2.sh` checks that purerestlib 0.4.0 resolves from GitHub
      Packages (a throwaway sbt `update` in the scratchpad).

## Phase 3: Adopt in the 8 services (one commit and push per repo)
- [ ] Task: For each of cart, catalog, fulfilment, inventory, order, payment and user: bump
      `purerestlibVersion` to 0.4.0, apply `LatencyBuckets.seconds` to the DB histogram, and
      run `sbt scalafmtCheck test`. Each service's existing DB timer test asserts the
      boundaries.
- [ ] Task: notification-service: version bump only.
- [ ] Task: Check `gh run list` in all 8 repos until green.
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md).
      `scripts/verify-perf3a-phase3.sh` reports CI status across the 8 repos.

## Phase 4: Deploy + live verification
- [ ] Task: `bin/k8s-local-up` (rebuild and redeploy all 8).
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md).
      `scripts/verify-perf3a-phase4.sh` port-forwards each service's metrics port (9090)
      and greps `http_server_request_duration_seconds_bucket{...le="0.25"`, plus the DB
      bucket where present. Then `bin/run-perf SmokeSimulation` exits 0.

## Wrap-up (outside the track)
- gluon PLAN.md PERF-3.0 ticked, CHANGELOG entry
- gluon-performance backlog: "Perf 3b: Observability stack in-cluster (PERF-3.1–3.4)"

## Risks
- The 0.1.0→0.4.0 jump is safe: no purerestlib/src/main changes since v0.1.0
- A cold-build AsyncAPI race in services means rerun once
- The testkit dependency may need adding to purerestlib Test scope
