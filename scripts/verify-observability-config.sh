#!/usr/bin/env bash
set -euo pipefail

# Manual verification for observability-config_20260927: confirms both services'
# config-loading changes genuinely take effect at runtime, not just that they parse:
#   - order-service's service-name now loads from application.conf (Phase 1) — overriding
#     ORDER_SERVICE_NAME should change the exported span's tracer name.
#   - inventory-service now loads all its settings via PureConfig instead of sys.env.get
#     (Phase 2) — overriding INVENTORY_SERVICE_NAME and INVENTORY_INDUCED_FAILURE_RATE
#     should both still work post-migration.
#
# Usage: ./scripts/verify-observability-config.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ORDER_TEST_NAME="verify-observability-config-order-name"
INVENTORY_TEST_NAME="verify-observability-config-inventory-name"
INVENTORY_PORT=8081
ORDER_PORT=8080
FAILED=0

cleanup() {
  echo
  echo "Cleaning up..."
  [ -n "${SBT_PID:-}" ] && { kill "$SBT_PID" >/dev/null 2>&1 || true; wait "$SBT_PID" 2>/dev/null || true; }
  for port in "$INVENTORY_PORT" "$ORDER_PORT"; do
    pids="$(lsof -ti "tcp:${port}" 2>/dev/null || true)"
    [ -n "$pids" ] && echo "$pids" | xargs kill >/dev/null 2>&1 || true
  done
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
echo "2. Starting both services with overrides (ORDER_SERVICE_NAME=${ORDER_TEST_NAME}, INVENTORY_SERVICE_NAME=${INVENTORY_TEST_NAME}, INVENTORY_INDUCED_FAILURE_RATE=1.0)..."
LOG_FILE="$(mktemp -t verify-observability-config)"
ORDER_SERVICE_NAME="$ORDER_TEST_NAME" \
  INVENTORY_SERVICE_NAME="$INVENTORY_TEST_NAME" \
  INVENTORY_INDUCED_FAILURE_RATE=1.0 \
  INVENTORY_SERVICE_BASE_URL="http://localhost:${INVENTORY_PORT}" \
  sbt --no-server "inventoryService/bgRun" "orderService/bgRun" "shell" >"$LOG_FILE" 2>&1 </dev/null &
SBT_PID=$!
for _ in $(seq 1 90); do
  order_code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${ORDER_PORT}/nope" || true)"
  inventory_code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${INVENTORY_PORT}/nope" || true)"
  [ "$order_code" != "000" ] && [ "$inventory_code" != "000" ] && break
  sleep 1
done
if [ "$order_code" = "000" ] || [ "$inventory_code" = "000" ]; then
  echo "   FAIL: services did not become ready." >&2
  cat "$LOG_FILE"
  exit 1
fi
echo "   OK: both services are up"

echo
echo "3. GET /nope on both (generates spans) then checking exported tracer names..."
curl -s -o /dev/null "http://localhost:${ORDER_PORT}/nope"
curl -s -o /dev/null "http://localhost:${INVENTORY_PORT}/nope"
sleep 1
if grep -q "\[tracer: ${ORDER_TEST_NAME}:\]" "$LOG_FILE"; then
  echo "   OK: order-service's exported span tracer name is '${ORDER_TEST_NAME}'"
else
  echo "   FAIL: expected '[tracer: ${ORDER_TEST_NAME}:]' in the combined output" >&2
  FAILED=1
fi
if grep -q "\[tracer: ${INVENTORY_TEST_NAME}:\]" "$LOG_FILE"; then
  echo "   OK: inventory-service's exported span tracer name is '${INVENTORY_TEST_NAME}'"
else
  echo "   FAIL: expected '[tracer: ${INVENTORY_TEST_NAME}:]' in the combined output" >&2
  FAILED=1
fi

echo
echo "4. POST /inventory/reserve with INVENTORY_INDUCED_FAILURE_RATE=1.0 (post-migration)..."
STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:${INVENTORY_PORT}/inventory/reserve" \
  -H "Content-Type: application/json" -d '{"item":"widget","quantity":1}')"
if [ "$STATUS" = "500" ]; then
  echo "   OK: deterministic 500 with failure-rate=1.0 — PureConfig-loaded induced-failure override works"
else
  echo "   FAIL: expected 500 with failure-rate=1.0, got ${STATUS}" >&2
  FAILED=1
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  tail -40 "$LOG_FILE" >&2
  exit 1
fi
