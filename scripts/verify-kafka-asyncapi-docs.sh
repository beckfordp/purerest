#!/usr/bin/env bash
set -euo pipefail

# Verifies kafka-harness's AsyncAPI documentation end-to-end (ADR 0010's
# AsyncAPI follow-up, Phase 5): brings up the `--profile observability`
# compose stack (kafka + kafka-harness, same as
# scripts/verify-kafka-observability.sh), runs
# scripts/generate-kafka-asyncapi-docs.sh to fetch the running spec and
# render static HTML, then confirms the demo topic/eventType/payload field
# actually show up in both the raw spec and the served HTML — not just
# that the containers started or the generator exited 0.
#
# CI-style: tears the stack down on exit, like
# scripts/verify-observability-stack.sh (not left running, unlike
# verify-kafka-observability.sh's deliberately-interactive leave-it-up
# style — this script's job is proving it works, not giving you something
# to look at).
#
# Usage: ./scripts/verify-kafka-asyncapi-docs.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

KAFKA_HARNESS_URL="http://localhost:8085"
FAILED=0

cleanup() {
  echo
  echo "Cleaning up (tearing down the observability profile)..."
  docker compose --profile observability down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  local url="$1"
  local label="$2"
  for _ in $(seq 1 90); do
    local status
    status="$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: ${label} did not become ready in time." >&2
  return 1
}

echo "1. docker compose --profile observability up -d kafka kafka-harness (fresh)..."
docker compose --profile observability down -v >/dev/null 2>&1 || true
docker compose --profile observability up -d kafka kafka-harness
wait_ready "${KAFKA_HARNESS_URL}/docs/asyncapi.json" "kafka-harness"

echo "2. Running scripts/generate-kafka-asyncapi-docs.sh..."
KAFKA_HARNESS_URL="$KAFKA_HARNESS_URL" ./scripts/generate-kafka-asyncapi-docs.sh

echo "3. Checking the raw spec for the demo topic/eventType/payload field..."
SPEC_FILE="target/asyncapi-docs-spec/asyncapi.json"
for needle in "kafka-harness-demo" "DemoEvent" "message"; do
  if ! grep -q "$needle" "$SPEC_FILE"; then
    echo "FAIL: raw spec missing expected content: ${needle}" >&2
    FAILED=1
  else
    echo "   OK: raw spec contains \"${needle}\""
  fi
done

echo "4. Checking the served static HTML for the same content..."
# generate-kafka-asyncapi-docs.sh writes to target/asyncapi-docs (the same
# path bind-mounted into the kafka-harness container at /app/asyncapi-docs
# — see docker-compose.yml), so the served HTML and the just-generated
# files on the host are the exact same bytes; checking either proves the
# same thing, but hitting the actual served route is the more honest
# end-to-end proof (confirms the bind mount and the static-file route both
# actually work, not just the generator).
html_status="$(curl -s -o /tmp/kafka-harness-asyncapi.html -w '%{http_code}' "${KAFKA_HARNESS_URL}/docs/asyncapi/index.html")"
if [ "$html_status" != "200" ]; then
  echo "FAIL: expected HTTP 200 serving the generated HTML, got ${html_status}" >&2
  FAILED=1
else
  for needle in "kafka-harness-demo" "DemoEvent"; do
    if ! grep -q "$needle" /tmp/kafka-harness-asyncapi.html; then
      echo "FAIL: served HTML missing expected content: ${needle}" >&2
      FAILED=1
    else
      echo "   OK: served HTML contains \"${needle}\""
    fi
  done
fi
rm -f /tmp/kafka-harness-asyncapi.html

if [ "$FAILED" -eq 0 ]; then
  echo
  echo "All checks passed."
  exit 0
else
  echo
  echo "One or more checks failed — see FAIL lines above." >&2
  exit 1
fi
