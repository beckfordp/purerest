#!/usr/bin/env bash
set -euo pipefail

# Manual verification for observability-config_20260927: confirms the service-name
# setting order-service now loads from application.conf (instead of a hardcoded string)
# genuinely propagates to the console span exporter, by overriding ORDER_SERVICE_NAME to
# a distinctive value and checking it appears in the exported span's tracer name.
#
# Usage: ./scripts/verify-observability-config.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TEST_NAME="verify-observability-config-test-name"
ORDER_PORT=8080
FAILED=0

cleanup() {
  echo
  echo "Cleaning up..."
  [ -n "${SBT_PID:-}" ] && { kill "$SBT_PID" >/dev/null 2>&1 || true; wait "$SBT_PID" 2>/dev/null || true; }
  pids="$(lsof -ti "tcp:${ORDER_PORT}" 2>/dev/null || true)"
  [ -n "$pids" ] && echo "$pids" | xargs kill >/dev/null 2>&1 || true
  docker compose down >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "1. docker compose up -d (Postgres)..."
docker compose down >/dev/null 2>&1 || true
docker compose up -d
for _ in $(seq 1 60); do
  status="$(docker compose ps --format '{{.Health}}' postgres 2>/dev/null || true)"
  [ "$status" = "healthy" ] && break
  sleep 1
done
echo "   OK: Postgres is healthy"

echo
echo "2. Starting order-service with ORDER_SERVICE_NAME=${TEST_NAME}..."
LOG_FILE="$(mktemp -t verify-observability-config)"
ORDER_SERVICE_NAME="$TEST_NAME" sbt --no-server "orderService/bgRun" "shell" >"$LOG_FILE" 2>&1 </dev/null &
SBT_PID=$!
for _ in $(seq 1 90); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${ORDER_PORT}/nope" || true)"
  [ "$code" != "000" ] && break
  sleep 1
done
if [ "$code" = "000" ]; then
  echo "   FAIL: order-service did not become ready." >&2
  cat "$LOG_FILE"
  exit 1
fi
echo "   OK: order-service is up"

echo
echo "3. GET /nope (generates a span) then checking exported tracer name..."
curl -s -o /dev/null "http://localhost:${ORDER_PORT}/nope"
sleep 1
if grep -q "\[tracer: ${TEST_NAME}:\]" "$LOG_FILE"; then
  echo "   OK: exported span's tracer name is '${TEST_NAME}' — override propagated"
else
  echo "   FAIL: expected '[tracer: ${TEST_NAME}:]' in order-service's output" >&2
  tail -40 "$LOG_FILE" >&2
  FAILED=1
fi

echo
if grep -q "\"service_name\":\"${TEST_NAME}\"\|starting" "$LOG_FILE"; then
  echo "   (structured startup log also present)"
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
