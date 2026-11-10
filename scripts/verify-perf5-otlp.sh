#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

# Phases 1-2 verification for otlp-exporter_20261104 (gluon PLAN.md PERF-5.2):
# the release workflow's own gate (scalafmtCheck + test, every module),
# coverage on the tracing classes (> 80% workflow target), then a real OTLP
# round trip: a throwaway OTel collector (debug exporter) on :14318 must
# receive `demo-span` from `Tracing.main` with service.name=manual-check.
#
# Usage: ./scripts/verify-perf5-otlp.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
LOG="target/verify-perf5-otlp.log"
RUN_LOG="target/verify-perf5-otlp-run.log"
COLLECTOR="purerest-verify-otelcol"
COLLECTOR_IMAGE="otel/opentelemetry-collector:0.139.0"
mkdir -p target

echo "== sbt scalafmtCheck Test/scalafmtCheck test (log: $LOG) =="
sbt -batch scalafmtCheck Test/scalafmtCheck test > "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: build/test failed — see $LOG" >&2
  exit 1
}
grep -E "Passed: Total" "$LOG" | sed 's/^/  /'

echo "== purerestlib coverage =="
sbt -batch coverage purerestlib/test purerestlib/coverageReport coverageOff \
  >> "$LOG" 2>&1 < /dev/null || {
  echo "FAIL: coverage run failed — see $LOG" >&2
  exit 1
}
python3 - modules/purerestlib/target/scala-3.9.0/scoverage-report/scoverage.xml <<'PY' || exit 1
import sys, xml.etree.ElementTree as ET
new = {"purerest.tracing.TracingConfig", "purerest.tracing.Tracing"}
failed, seen = False, set()
for c in ET.parse(sys.argv[1]).getroot().iter("class"):
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

echo "== OTLP round trip via $COLLECTOR_IMAGE =="
CONF="$(mktemp -d)/otelcol.yaml"
cat > "$CONF" <<'YAML'
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
exporters:
  debug:
    verbosity: detailed
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [debug]
YAML
docker rm -f "$COLLECTOR" > /dev/null 2>&1 || true
trap 'docker rm -f "$COLLECTOR" > /dev/null 2>&1 || true' EXIT
docker run -d --name "$COLLECTOR" -p 14318:4318 \
  -v "$CONF:/etc/otelcol/config.yaml:ro" "$COLLECTOR_IMAGE" > /dev/null
for _ in $(seq 1 30); do
  docker logs "$COLLECTOR" 2>&1 | grep -q "Everything is ready" && break
  sleep 1
done

OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:14318 \
  sbt -batch "purerestlib/runMain purerest.tracing.Tracing" > "$RUN_LOG" 2>&1 < /dev/null || {
  echo "FAIL: Tracing.main failed — see $RUN_LOG" >&2
  exit 1
}
sleep 2
OUT="$(docker logs "$COLLECTOR" 2>&1)"
fail=0
for want in "Name           : demo-span" "service.name: Str(manual-check)"; do
  if grep -qF "$want" <<< "$OUT"; then
    echo "  PASS collector saw '$want'"
  else
    echo "  FAIL collector did not see '$want'"
    fail=1
  fi
done
if grep -q "LoggingSpanExporter" "$RUN_LOG"; then
  echo "  FAIL span also went to the logging exporter"
  fail=1
fi
[ "$fail" -eq 0 ] || { echo "$OUT" | tail -40 >&2; exit 1; }

echo "PASS: Phases 1-2 (TracingConfig, OTLP exporter) verified"
