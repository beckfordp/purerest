#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

# Phases 1-2 verification for journey-telemetry-lib_20261103 (gluon PLAN.md
# PERF-4.1-4.3, 4.11): the release workflow's own gate (scalafmtCheck + test,
# every module), plus coverage on the classes this track adds or changes,
# which must stay above the 80% workflow target.
#
# Usage: ./scripts/verify-perf4a.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
LOG="target/verify-perf4a.log"
mkdir -p target

echo "== sbt scalafmtCheck Test/scalafmtCheck test (log: $LOG) =="
sbt -batch scalafmtCheck Test/scalafmtCheck test > "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: build/test failed — see $LOG" >&2
  exit 1
}
grep -E "Passed: Total" "$LOG" | sed 's/^/  /'

echo "== purerestlib + purekafka coverage =="
sbt -batch coverage purerestlib/test purerestlib/coverageReport \
  pureKafka/test pureKafka/coverageReport coverageOff >> "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: coverage run failed — see $LOG" >&2
  exit 1
}
python3 - modules/purerestlib/target/scala-3.9.0/scoverage-report/scoverage.xml \
  modules/purekafka/target/scala-3.9.0/scoverage-report/scoverage.xml <<'PY' || exit 1
import sys, xml.etree.ElementTree as ET
new = {"purerest.metrics.PoolMetrics", "purerest.metrics.Metrics",
       "purerest.logging.Milestones", "purekafka.consumer.EventAge"}
failed, seen = False, set()
for path in sys.argv[1:]:
    for c in ET.parse(path).getroot().iter("class"):
        name, rate = c.get("name"), float(c.get("statement-rate"))
        if name in new:
            seen.add(name)
            ok = rate > 80
            failed |= not ok
            print(f"  {'PASS' if ok else 'FAIL'} {name} {rate:.2f}%")
missing = new - seen
if missing:
    print(f"  FAIL no coverage data for {sorted(missing)}")
sys.exit(1 if failed or missing else 0)
PY

echo "PASS: Phases 1-2 (runtime, pool, milestone, event-age) verified"
