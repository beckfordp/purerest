#!/usr/bin/env bash
set -euo pipefail

# Phase 1 verification for latency-buckets_20261030 (gluon PLAN.md PERF-3.0):
# the release workflow's own gate (scalafmtCheck + test, every module), plus
# purerest.metrics coverage, which must stay above the 80% workflow target.
#
# Usage: ./scripts/verify-perf3a-phase1.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
LOG="target/verify-perf3a-phase1.log"
mkdir -p target

echo "== sbt scalafmtCheck Test/scalafmtCheck test (log: $LOG) =="
sbt -batch scalafmtCheck Test/scalafmtCheck test > "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: build/test failed — see $LOG" >&2
  exit 1
}
grep -E "Passed: Total" "$LOG" | sed 's/^/  /'

echo "== purerestlib coverage =="
sbt -batch coverage purerestlib/test purerestlib/coverageReport coverageOff >> "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: coverage run failed — see $LOG" >&2
  exit 1
}
# The workflow's >80% target is for new code: gate on the purerest.metrics
# classes this track touches. purerestlib overall sits lower (logging/tracing
# test helpers), which predates this track.
python3 - modules/purerestlib/target/scala-3.9.0/scoverage-report/scoverage.xml <<'PY' || exit 1
import sys, xml.etree.ElementTree as ET
failed = False
for c in ET.parse(sys.argv[1]).getroot().iter("class"):
    name, rate = c.get("name"), float(c.get("statement-rate"))
    if name.startswith("purerest.metrics."):
        ok = rate > 80
        failed |= not ok
        print(f"  {'PASS' if ok else 'FAIL'} {name} {rate:.2f}%")
sys.exit(1 if failed else 0)
PY

echo "PASS: Phase 1 (LatencyBuckets) verified"
