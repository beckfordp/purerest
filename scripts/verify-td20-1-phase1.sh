#!/usr/bin/env bash
set -euo pipefail

# Phase 1 verification for rest-docs_20261101 (gluon TECHNICAL_DEBT.md TD-20.1):
# the release workflow's gate (scalafmt + test, every module), the
# DocsCoverageSuite run on its own, and purerest.docs coverage above the 80%
# workflow target.
#
# Usage: ./scripts/verify-td20-1-phase1.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
LOG="target/verify-td20-1-phase1.log"
mkdir -p target

echo "== sbt scalafmtCheckAll scalafmtSbtCheck test (log: $LOG) =="
sbt -batch scalafmtCheckAll scalafmtSbtCheck test > "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: build/test failed — see $LOG" >&2
  exit 1
}
grep -E "Passed: Total" "$LOG" | sed 's/^/  /'

echo "== DocsCoverageSuite =="
sbt -batch "purerestlib/testOnly purerest.docs.DocsCoverageSuite" >> "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: DocsCoverageSuite failed — see $LOG" >&2
  exit 1
}
grep -E "Passed: Total" "$LOG" | tail -1 | sed 's/^/  /'

echo "== purerest.docs coverage =="
sbt -batch coverage purerestlib/test purerestlib/coverageReport coverageOff >> "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: coverage run failed — see $LOG" >&2
  exit 1
}
python3 - modules/purerestlib/target/scala-3.9.0/scoverage-report/scoverage.xml <<'PY' || exit 1
import sys, xml.etree.ElementTree as ET
failed = False
for c in ET.parse(sys.argv[1]).getroot().iter("class"):
    name, rate = c.get("name"), float(c.get("statement-rate"))
    if name.startswith("purerest.docs."):
        ok = rate > 80
        failed |= not ok
        print(f"  {'PASS' if ok else 'FAIL'} {name} {rate:.2f}%")
sys.exit(1 if failed else 0)
PY

echo "PASS: Phase 1 (Docs.undocumented) verified"
