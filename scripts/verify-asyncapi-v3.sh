#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Verifies asyncapi-v3_20261101 (gluon TD-19.1/19.2): purekafka's tests and
# formatting, then kafka-harness live — its served spec is AsyncAPI 3.0.0 and
# passes `asyncapi validate`, the rendered HTML shows send and receive
# operations keyed by topic, and /docs/asyncapi redirects to /docs/asyncapi/.
#   scripts/verify-asyncapi-v3.sh   (needs Docker and Node >= 24.11)
set -euo pipefail
cd "$(dirname "$0")/.."

URL="http://localhost:8085"
CLI_TOOL="target/asyncapi-cli-tool/cli-6.2.2_html-3.5.6"

node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>24||(a===24&&b>=11)?0:1)' \
  || { echo "FAIL: run on Node >= 24.11 (nvm use 26); found $(node -v)"; exit 1; }
mkdir -p target

cleanup() { docker compose --profile observability down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== purekafka tests and formatting, kafka-harness image =="
sbt -batch -Dsbt.supershell=false "pureKafka/scalafmtCheckAll" "pureKafka/Test/scalafmtCheck" \
  "pureKafka/test" "kafkaHarness/Docker/publishLocal" < /dev/null > target/verify-asyncapi-v3-sbt.log 2>&1 \
  || { echo "FAIL: sbt (see target/verify-asyncapi-v3-sbt.log)"; exit 1; }
echo "ok   tests, scalafmt, kafka-harness image"

echo "== kafka-harness up =="
cleanup
docker compose --profile observability up -d kafka kafka-harness > /dev/null 2>&1
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/docs/asyncapi.json" || true)" = "200" ] && break
  sleep 1
done

echo "== spec and HTML =="
KAFKA_HARNESS_URL="$URL" ./scripts/generate-kafka-asyncapi-docs.sh < /dev/null > target/verify-asyncapi-v3-docs.log 2>&1 \
  || { echo "FAIL: generate-kafka-asyncapi-docs.sh (see target/verify-asyncapi-v3-docs.log)"; exit 1; }
spec="target/asyncapi-docs-spec/asyncapi.json"
grep -q '"asyncapi":"3.0.0"' <(tr -d ' \n' < "$spec") || { echo "FAIL: spec is not 3.0.0"; exit 1; }
echo "ok   served spec is 3.0.0"
SUPPRESS_NO_CONFIG_WARNING=true "$CLI_TOOL/node_modules/.bin/asyncapi" validate "$spec" < /dev/null \
  > target/verify-asyncapi-v3-validate.log 2>&1 \
  || { echo "FAIL: asyncapi validate (see target/verify-asyncapi-v3-validate.log)"; exit 1; }
echo "ok   asyncapi validate"

html="$(curl -s "$URL/docs/asyncapi/index.html")"
# html-template anchors each operation as operation-<action>-<operation key>.
# kafka-harness-demo is Both (so suffixed keys); the outbox demo is send-only.
for anchor in operation-send-kafka-harness-demo-send operation-receive-kafka-harness-demo-receive \
  operation-send-kafka-harness-outbox-demo; do
  grep -q "$anchor" <<< "$html" || { echo "FAIL: HTML missing $anchor"; exit 1; }
done
echo "ok   HTML shows send/receive operations keyed by topic"

echo "== redirect =="
redirect="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$URL/docs/asyncapi")"
status="${redirect%% *}" location="${redirect#* }"
[ "$status" = "301" ] && [ "$location" = "$URL/docs/asyncapi/" ] \
  || { echo "FAIL: /docs/asyncapi gave $status $location"; exit 1; }
[ "$(curl -s -o /dev/null -w '%{http_code}' -L "$URL/docs/asyncapi")" = "200" ] \
  || { echo "FAIL: following the redirect did not reach the HTML"; exit 1; }
echo "ok   /docs/asyncapi -> /docs/asyncapi/ (301), HTML served"

echo "PASS: AsyncAPI 3.0 docs verified"
