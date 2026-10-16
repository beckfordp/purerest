#!/usr/bin/env bash
set -euo pipefail

# Verifies purekafka's Grafana-visible observability demo: brings up the
# `--profile observability` compose stack (the same Prometheus/Grafana
# containers scripts/verify-observability-stack.sh already uses, plus the
# new `kafka` broker and `kafka-harness` fixture app this adds), confirms
# Prometheus has scraped kafka-harness and that all five purekafka.* metric
# families it emits are present/non-zero within the first ~60s — then,
# UNLIKE verify-observability-stack.sh's CI-style pass/fail-then-cleanup,
# deliberately leaves everything running and prints the Grafana URL. This
# script's actual purpose is letting you go look at real dashboards, not
# just an automated check; tear down yourself when done
# (`docker compose --profile observability down -v`).
#
# Usage: ./scripts/verify-kafka-observability.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ORDER_METRICS_PORT=9092 # Prometheus's own host port (see docker-compose.yml)
FAILED=0

wait_healthy() {
  local service="$1"
  for _ in $(seq 1 90); do
    local status
    status="$(docker compose ps --format '{{.Health}}' "$service" 2>/dev/null || true)"
    [ "$status" = "healthy" ] && return 0
    sleep 1
  done
  echo "FAIL: ${service} did not become healthy in time." >&2
  return 1
}

wait_ready() {
  local port="$1"
  local label="$2"
  for _ in $(seq 1 90); do
    local status
    status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${port}/nope" || true)"
    [ "$status" != "000" ] && return 0
    sleep 1
  done
  echo "FAIL: ${label} did not become ready in time." >&2
  return 1
}

# Sums the values of all series (across labels) for a given Prometheus metric name,
# queried through Prometheus's own HTTP API (not a raw /metrics scrape) so this also
# exercises the same query path Grafana's dashboard uses. Same helper
# scripts/verify-observability-stack.sh already has.
prom_query_sum() {
  local expr="$1"
  curl -s -G "http://localhost:${ORDER_METRICS_PORT}/api/v1/query" --data-urlencode "query=${expr}" \
    | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(sum(float(r['value'][1]) for r in d['data']['result']))
"
}

gt_zero() {
  [ "$(echo "$1 > 0" | bc)" = "1" ]
}

has_results() {
  local expr="$1"
  curl -s -G "http://localhost:${ORDER_METRICS_PORT}/api/v1/query" --data-urlencode "query=${expr}" \
    | python3 -c "
import json, sys
d = json.load(sys.stdin)
sys.exit(0 if len(d['data']['result']) > 0 else 1)
"
}

echo "1. docker compose --profile observability up -d (fresh volumes)..."
echo "   (brings up the full HTTP+Kafka observability stack - same profile"
echo "   scripts/verify-observability-stack.sh uses, plus the new kafka/"
echo "   kafka-harness services)"
docker compose --profile observability down -v >/dev/null 2>&1 || true
docker compose --profile observability up -d
wait_healthy postgres
wait_healthy kafka
wait_ready 3000 "grafana"
echo "   OK: postgres/kafka healthy, grafana ready"

echo
echo "2. Confirming Prometheus has scraped kafka-harness..."
for _ in $(seq 1 60); do
  targets="$(curl -s "http://localhost:${ORDER_METRICS_PORT}/api/v1/targets")"
  up="$(echo "$targets" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(any(t['labels'].get('job') == 'kafka-harness' and t['health'] == 'up' for t in d['data']['activeTargets']))
")"
  [ "$up" = "True" ] && break
  sleep 1
done
if [ "$up" = "True" ]; then
  echo "   OK: kafka-harness Prometheus target is up"
else
  echo "   FAIL: kafka-harness Prometheus target never came up" >&2
  echo "$targets" >&2
  FAILED=1
fi

echo
echo "3. Confirming Grafana's purekafka dashboard is provisioned..."
search="$(curl -s "http://localhost:3000/api/search?query=")"
if echo "$search" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if any(x['uid']=='purekafka-resilience-observability' for x in d) else 1)"; then
  echo "   OK: purekafka dashboard provisioned"
else
  echo "   FAIL: purekafka dashboard not found in Grafana" >&2
  FAILED=1
fi

echo
echo "4. Waiting ~60s for kafka-harness's self-driving traffic to produce"
echo "   enough activity for all five purekafka.* metric families..."
sleep 60

echo
echo "5. Confirming all five purekafka.* metric families are present/non-zero..."
retry_attempts="$(prom_query_sum 'sum(purekafka_retry_attempts_total)')"
echo "   purekafka_retry_attempts_total = ${retry_attempts}"
if gt_zero "$retry_attempts"; then
  echo "   OK: Resilience.middleware is recording retry-attempt metrics"
else
  echo "   FAIL: expected nonzero purekafka_retry_attempts_total" >&2
  FAILED=1
fi

if has_results 'purekafka_circuit_breaker_state'; then
  echo "   OK: CircuitBreaker is recording its live state gauge"
else
  echo "   FAIL: expected purekafka_circuit_breaker_state to have at least one series" >&2
  FAILED=1
fi

dlq_published="$(prom_query_sum 'sum(purekafka_dlq_published_total)')"
echo "   purekafka_dlq_published_total = ${dlq_published}"
if gt_zero "$dlq_published"; then
  echo "   OK: DeadLetter has routed at least one poison message"
else
  echo "   FAIL: expected nonzero purekafka_dlq_published_total" >&2
  FAILED=1
fi

stream_restarts="$(prom_query_sum 'sum(purekafka_stream_restarts_total)')"
echo "   purekafka_stream_restarts_total = ${stream_restarts}"
if gt_zero "$stream_restarts"; then
  echo "   OK: StreamSupervisor has restarted the consumer at least once"
else
  echo "   FAIL: expected nonzero purekafka_stream_restarts_total (the first blip fires ~45s after kafka-harness starts - if this just failed, try again in another 30-60s)" >&2
  FAILED=1
fi

if has_results 'purekafka_consumer_lag'; then
  echo "   OK: ConsumerLag is recording its gauge"
else
  echo "   FAIL: expected purekafka_consumer_lag to have at least one series" >&2
  FAILED=1
fi

echo
echo "Grafana:      http://localhost:3000/d/purekafka-resilience-observability"
echo "Prometheus:   http://localhost:${ORDER_METRICS_PORT}"
echo
echo "Everything is left running - open Grafana and watch the dashboard"
echo "populate live. Tear down yourself when done:"
echo "  docker compose --profile observability down -v"
echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
