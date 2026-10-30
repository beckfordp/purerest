#!/usr/bin/env bash
set -euo pipefail

# Phase 3 verification for latency-buckets_20261030: every gluon service
# builds against purerestlib 0.4.0 and its CI is green on the latest main
# commit. Waits for in-progress runs.
#
# Usage: ./scripts/verify-perf3a-phase3.sh

SERVICES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../services" && pwd)"
FAILED=0

for svc in cart catalog fulfilment inventory order payment user notification; do
  dir="$SERVICES_DIR/$svc-service"
  if ! grep -q 'val purerestlibVersion = "0.4.0"' "$dir/build.sbt"; then
    echo "FAIL $svc-service: purerestlibVersion is not 0.4.0"
    FAILED=1
    continue
  fi
  head="$(git -C "$dir" rev-parse HEAD)"
  run="$(cd "$dir" && gh run list -b main -L 5 --json databaseId,headSha \
    --jq "[.[] | select(.headSha == \"$head\")][0].databaseId")"
  if [ -z "$run" ] || [ "$run" = null ]; then
    echo "FAIL $svc-service: no CI run for ${head:0:7}"
    FAILED=1
    continue
  fi
  if (cd "$dir" && gh run watch "$run" --exit-status > /dev/null 2>&1); then
    echo "PASS $svc-service: CI green on ${head:0:7} (run $run)"
  else
    echo "FAIL $svc-service: CI failed on ${head:0:7} (run $run)"
    FAILED=1
  fi
done

[ "$FAILED" = 0 ] && echo "PASS: Phase 3 (services on purerestlib 0.4.0) verified"
exit "$FAILED"
