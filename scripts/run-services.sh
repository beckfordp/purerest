#!/usr/bin/env bash
set -euo pipefail

# Starts both inventory-service and order-service and leaves them running for
# manual/interactive use (curl, browser, Swagger UI, etc.) — unlike the
# verify-*.sh scripts, this does not run any assertions or tear anything down
# until you stop it yourself (Ctrl-C).
#
# Both services are started from a single sbt session via `bgRun` — sbt
# refuses to run two independent launcher JVMs concurrently against the same
# build directory.
#
# Prerequisite: order-service persists to PostgreSQL — run `docker compose up -d`
# first (see docker-compose.yml / README.md).
#
# Usage: ./scripts/run-services.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

INVENTORY_PORT="${INVENTORY_SERVICE_PORT:-8081}"
ORDER_PORT="${ORDER_SERVICE_PORT:-8080}"
LOG_FILE="$(mktemp -t services-run)"

echo "Starting inventory-service and order-service (single sbt session, bgRun)..."
echo "Full output is being captured to: ${LOG_FILE}"
INVENTORY_SERVICE_BASE_URL="http://localhost:${INVENTORY_PORT}" \
  sbt --no-server \
  "inventoryService/bgRun" \
  "orderService/bgRun" \
  "shell" \
  >"$LOG_FILE" 2>&1 </dev/null &
SBT_PID=$!

cleanup() {
  echo
  echo "Stopping services..."
  kill "$SBT_PID" >/dev/null 2>&1 || true
  wait "$SBT_PID" 2>/dev/null || true
  # bgRun spawns detached JVMs that may outlive the parent sbt process —
  # clean up anything still bound to either port.
  for port in "$INVENTORY_PORT" "$ORDER_PORT"; do
    local_pids="$(lsof -ti "tcp:${port}" 2>/dev/null || true)"
    if [ -n "$local_pids" ]; then
      echo "$local_pids" | xargs kill >/dev/null 2>&1 || true
    fi
  done
}
trap cleanup EXIT

wait_ready() {
  local port="$1"
  local label="$2"
  echo "Waiting for ${label} to become ready on port ${port}..."
  for _ in $(seq 1 90); do
    local status
    status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${port}/nope" || true)"
    if [ "$status" != "000" ]; then
      return 0
    fi
    sleep 1
  done
  echo "${label} did not become ready within 90s." >&2
  echo "--- captured output ---"
  cat "$LOG_FILE"
  return 1
}

wait_ready "$INVENTORY_PORT" "inventory-service"
wait_ready "$ORDER_PORT" "order-service"

echo
echo "Both services are up:"
echo "  inventory-service : http://localhost:${INVENTORY_PORT}  (Swagger UI: http://localhost:${INVENTORY_PORT}/docs/)"
echo "  order-service     : http://localhost:${ORDER_PORT}  (Swagger UI: http://localhost:${ORDER_PORT}/docs/)"
echo
echo "Combined output is being tailed below. Press Ctrl-C to stop both services."
echo

tail -n 0 -f "$LOG_FILE"
