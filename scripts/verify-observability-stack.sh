#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

# Verifies the observability stack end-to-end: brings up the full
# `--profile observability` compose stack, drives real traffic through it with
# the Gatling load-test module (order placement plus a live ramp of
# inventory-service's induced-failure rate — see OrderPlacementSimulation's
# rampFailureRate scenario — inducing retries, a circuit-breaker trip and
# recovery, and a WARN-level induced-failure log in one continuous run), and
# confirms Prometheus, Grafana, and Elasticsearch are all populated with real
# data from that traffic — not just that the containers started.
#
# Usage: ./scripts/verify-observability-stack.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ORDER_METRICS_PORT=9092  # Prometheus's own host port (see docker-compose.yml)
FAILED=0

cleanup() {
  echo
  echo "Cleaning up (tearing down the observability profile)..."
  docker compose --profile observability down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

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
# exercises the same query path Grafana's dashboard uses.
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

# Confirms a PromQL query returns at least one series, for checks where the
# value itself (rather than a summed count) is what matters — e.g. a Grafana
# panel's query genuinely resolving data.
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
docker compose --profile observability down -v >/dev/null 2>&1 || true
docker compose --profile observability up -d
wait_healthy postgres
wait_healthy elasticsearch
wait_healthy kibana
wait_ready 8080 "order-service"
wait_ready 8081 "inventory-service"
echo "   OK: postgres/elasticsearch/kibana healthy, order-service/inventory-service ready"

echo
echo "2. Confirming Prometheus has scraped both services..."
for _ in $(seq 1 30); do
  targets="$(curl -s "http://localhost:${ORDER_METRICS_PORT}/api/v1/targets")"
  up_count="$(echo "$targets" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(sum(1 for t in d['data']['activeTargets'] if t['health'] == 'up'))
")"
  [ "$up_count" = "2" ] && break
  sleep 1
done
if [ "$up_count" = "2" ]; then
  echo "   OK: both order-service and inventory-service targets are up"
else
  echo "   FAIL: expected 2 Prometheus targets up, got ${up_count}" >&2
  echo "$targets" >&2
  FAILED=1
fi

echo
echo "3. Confirming Grafana's datasource + dashboard are provisioned..."
datasources="$(curl -s "http://localhost:3000/api/datasources")"
if echo "$datasources" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if any(x['type']=='prometheus' for x in d) else 1)"; then
  echo "   OK: Prometheus datasource provisioned"
else
  echo "   FAIL: no Prometheus datasource found in Grafana" >&2
  FAILED=1
fi
search="$(curl -s "http://localhost:3000/api/search?query=")"
if echo "$search" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if any(x['uid']=='purerest-red-resilience' for x in d) else 1)"; then
  echo "   OK: purerest dashboard provisioned"
else
  echo "   FAIL: purerest dashboard not found in Grafana" >&2
  FAILED=1
fi

echo
echo "3b. Confirming Kibana's purerest-logs-* Data View is provisioned..."
# kibana-setup is a one-shot that runs once kibana is healthy; give it a moment
# to complete before checking.
for _ in $(seq 1 30); do
  status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:5601/api/data_views/data_view/purerest-logs")"
  [ "$status" = "200" ] && break
  sleep 1
done
if [ "$status" = "200" ]; then
  echo "   OK: purerest-logs Data View provisioned in Kibana"
else
  echo "   FAIL: expected Kibana's purerest-logs Data View to exist (got HTTP ${status})" >&2
  FAILED=1
fi

echo
echo "4. Running Gatling: order placement + a live induced-failure ramp..."
# One continuous run — OrderPlacementSimulation's rampFailureRate scenario drives
# inventory-service's induced-failure rate through 0.0 -> 0.6 -> 0.0 live, via
# PATCH /admin/induced-failure, concurrently with the order-placement load. No
# container restart needed (unlike the old two-pass version of this script).
echo "   Running Gatling (this takes ~2.5 minutes)..."
if ! sbt -batch "loadTest/Gatling/test"; then
  echo "   FAIL: Gatling simulation failed" >&2
  FAILED=1
fi
server_requests="$(prom_query_sum 'sum(http_server_request_duration_seconds_count)')"
db_queries="$(prom_query_sum 'sum(db_client_operation_duration_seconds_count)')"
echo "   http_server_request_duration_seconds_count = ${server_requests}"
echo "   db_client_operation_duration_seconds_count = ${db_queries}"
if gt_zero "$server_requests" && gt_zero "$db_queries"; then
  echo "   OK: the run produced RED + DB-query metrics"
else
  echo "   FAIL: expected nonzero request and DB-query counts" >&2
  FAILED=1
fi

echo
echo "5. Confirming a full circuit-breaker trip-and-recovery cycle (not just activity)..."
retried_total="$(prom_query_sum 'sum(purerest_retry_attempts_total{outcome="retried"})')"
rejected_total="$(prom_query_sum 'sum(purerest_circuit_breaker_calls_rejected_total)')"
echo "   purerest_retry_attempts_total{outcome=retried} = ${retried_total}"
echo "   purerest_circuit_breaker_calls_rejected_total = ${rejected_total}"
if gt_zero "$retried_total"; then
  echo "   OK: the run produced retry-attempt metrics"
else
  echo "   FAIL: expected nonzero purerest_retry_attempts_total{outcome=retried}" >&2
  FAILED=1
fi
if gt_zero "$rejected_total"; then
  echo "   OK: the circuit breaker rejected calls while open"
else
  echo "   FAIL: expected nonzero purerest_circuit_breaker_calls_rejected_total" >&2
  FAILED=1
fi
# Unaggregated (keeps from_state/to_state labels) so this checks for an actual
# trip *and* a recovery, not just "some transition happened somewhere."
transitions="$(curl -s -G "http://localhost:${ORDER_METRICS_PORT}/api/v1/query" \
  --data-urlencode 'query=purerest_circuit_breaker_state_transitions_total')"
has_transition_to() {
  local state="$1"
  echo "$transitions" | python3 -c "
import json, sys
d = json.load(sys.stdin)
sys.exit(0 if any(r['metric'].get('to_state') == '$state' for r in d['data']['result']) else 1)
"
}
if has_transition_to "OPEN"; then
  echo "   OK: a CLOSED -> OPEN transition occurred (the breaker tripped)"
else
  echo "   FAIL: expected a circuit-breaker transition to OPEN" >&2
  FAILED=1
fi
if has_transition_to "CLOSED"; then
  echo "   OK: a transition back to CLOSED occurred (the breaker recovered)"
else
  echo "   FAIL: expected a circuit-breaker transition back to CLOSED" >&2
  FAILED=1
fi
if has_results 'purerest_circuit_breaker_state{state="CLOSED"} == 1'; then
  echo "   OK: the circuit breaker is CLOSED at the end of the run (fully recovered)"
else
  echo "   FAIL: expected the live circuit-breaker state to be CLOSED at the end of the run" >&2
  FAILED=1
fi

echo
echo "6. Confirming Elasticsearch indexed structured logs from both services,"
echo "   including an induced-failure WARN log from the run..."
# Filebeat ships on a short poll interval, but give it a moment to catch up on the
# run's tail before searching.
sleep 10
es_count="$(curl -s "http://localhost:9200/purerest-logs-*/_count" | python3 -c "import json,sys; print(json.load(sys.stdin)['count'])")"
echo "   purerest-logs-* document count = ${es_count}"
if gt_zero "$es_count"; then
  echo "   OK: Elasticsearch has indexed log documents"
else
  echo "   FAIL: expected Elasticsearch to have indexed at least one log document" >&2
  FAILED=1
fi

trace_id_hits="$(curl -s "http://localhost:9200/purerest-logs-*/_search" -H "Content-Type: application/json" -d '{
  "query": { "exists": { "field": "trace_id" } },
  "size": 0
}' | python3 -c "import json,sys; print(json.load(sys.stdin)['hits']['total']['value'])")"
echo "   docs with a trace_id field = ${trace_id_hits}"
if gt_zero "$trace_id_hits"; then
  echo "   OK: structured trace_id field is indexed and searchable"
else
  echo "   FAIL: expected at least one log document with a trace_id field" >&2
  FAILED=1
fi

induced_failure_hits="$(curl -s "http://localhost:9200/purerest-logs-*/_search" -H "Content-Type: application/json" -d '{
  "query": { "match_phrase": { "message": "Induced failure triggered" } },
  "size": 0
}' | python3 -c "import json,sys; print(json.load(sys.stdin)['hits']['total']['value'])")"
echo "   docs with induced-failure WARN message = ${induced_failure_hits}"
if gt_zero "$induced_failure_hits"; then
  echo "   OK: the induced-failure WARN log from the run is indexed"
else
  echo "   FAIL: expected at least one indexed 'Induced failure triggered' log" >&2
  FAILED=1
fi

echo
echo "7. Confirming the two new Grafana panels' PromQL queries resolve data..."
if has_results 'purerest_circuit_breaker_state'; then
  echo "   OK: Circuit Breaker: State Timeline panel's query returns data"
else
  echo "   FAIL: expected purerest_circuit_breaker_state to have at least one series" >&2
  FAILED=1
fi
if has_results 'sum(rate(purerest_retry_attempts_total{outcome="succeeded"}[1m])) / (sum(rate(purerest_retry_attempts_total{outcome="succeeded"}[1m])) + sum(rate(purerest_retry_attempts_total{outcome="exhausted"}[1m])))'; then
  echo "   OK: Retry Success Rate panel's query returns data"
else
  echo "   FAIL: expected the Retry Success Rate query to return data" >&2
  FAILED=1
fi

echo
echo "Grafana:      http://localhost:3000/d/purerest-red-resilience"
echo "Kibana:       http://localhost:5601"
echo "Prometheus:   http://localhost:${ORDER_METRICS_PORT}"
echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
