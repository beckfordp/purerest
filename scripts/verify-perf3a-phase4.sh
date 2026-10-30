#!/usr/bin/env bash
set -euo pipefail

# Phase 4 verification for latency-buckets_20261030 (gluon PLAN.md PERF-3.0):
# every service deployed in gluon-local exposes the LatencyBuckets boundaries
# on /metrics — le="0.25" on the HTTP server histogram, and on the DB
# histogram where the service has one. The smoke run goes first: it must
# still pass, and its traffic gives every histogram points to export.
#
# Usage: ./scripts/verify-perf3a-phase4.sh   (after gluon bin/k8s-local-up)

GLUON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
NAMESPACE="gluon-local"
LOCAL_PORT=19090
FAILED=0
PF_PID=""

cleanup() { [ -n "$PF_PID" ] && kill "$PF_PID" >/dev/null 2>&1 || true; }
trap cleanup EXIT

check() { # <service> <metric-prefix> <body>
  if grep -q "^$2_bucket{.*le=\"0.25\"" <<< "$3"; then
    echo "  PASS $1 $2 has le=\"0.25\""
  else
    echo "  FAIL $1 $2 missing le=\"0.25\""
    FAILED=1
  fi
}

echo "== Smoke run (bin/run-perf SmokeSimulation) =="
"$GLUON_DIR/bin/run-perf" SmokeSimulation

for svc in cart catalog fulfilment inventory order payment user notification; do
  echo "== $svc-service =="
  kubectl -n "$NAMESPACE" port-forward "svc/$svc-service" "$LOCAL_PORT:9090" >/dev/null 2>&1 &
  PF_PID=$!
  body=""
  for _ in $(seq 1 20); do
    body="$(curl -fs "http://localhost:$LOCAL_PORT/metrics" || true)"
    [ -n "$body" ] && break
    sleep 0.5
  done
  if [ -z "$body" ]; then
    echo "  FAIL $svc-service /metrics unreachable"
    FAILED=1
  else
    check "$svc" http_server_request_duration_seconds "$body"
    if [ "$svc" != notification ]; then
      check "$svc" db_client_operation_duration_seconds "$body"
    fi
  fi
  kill "$PF_PID" >/dev/null 2>&1 || true
  wait "$PF_PID" 2>/dev/null || true
  PF_PID=""
done

[ "$FAILED" = 0 ] || { echo "FAIL: bucket boundaries missing" >&2; exit 1; }
echo "PASS: Phase 4 (live LatencyBuckets) verified"
