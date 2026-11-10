#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Verifies asyncapi-node26_20261031: the plugin's unit tests and formatting,
# kafka-harness's AsyncAPI HTML rendered end to end by cli 6.2.2 +
# html-template 3.5.6 on Node >= 24.11, and a clear failure on older Node.
#   scripts/verify-asyncapi-node26.sh   (needs Docker; builds kafka-harness)
set -euo pipefail
cd "$(dirname "$0")/.."

node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>24||(a===24&&b>=11)?0:1)' \
  || { echo "FAIL: run on Node >= 24.11 (nvm use 26); found $(node -v)"; exit 1; }
mkdir -p target

echo "== Plugin tests and formatting =="
sbt -batch -Dsbt.supershell=false "sbtPureKafkaDocs/scalafmtCheckAll" "sbtPureKafkaDocs/Test/scalafmtCheck" \
  "sbtPureKafkaDocs/test" "kafkaHarness/Docker/publishLocal" < /dev/null > target/verify-asyncapi-node26-sbt.log 2>&1 \
  || { echo "FAIL: sbt (see target/verify-asyncapi-node26-sbt.log)"; exit 1; }
echo "ok   plugin tests, scalafmt, kafka-harness image"

echo "== kafka-harness AsyncAPI HTML (fresh tool cache) =="
rm -rf target/asyncapi-cli-tool
./scripts/verify-kafka-asyncapi-docs.sh < /dev/null > target/verify-asyncapi-node26-docs.log 2>&1 \
  || { echo "FAIL: verify-kafka-asyncapi-docs.sh (see target/verify-asyncapi-node26-docs.log)"; exit 1; }
[ -d target/asyncapi-cli-tool/cli-6.2.2_html-3.5.6 ] || { echo "FAIL: versioned tool cache missing"; exit 1; }
echo "ok   rendered with cli 6.2.2 + html-template 3.5.6"

echo "== Old Node is refused clearly =="
old_node="$HOME/.nvm/versions/node/v18.16.0/bin"
if [ -x "$old_node/node" ]; then
  out="$(PATH="$old_node:$PATH" ./scripts/generate-kafka-asyncapi-docs.sh 2>&1 || true)"
  grep -q "needs Node >= 24.11" <<< "$out" || { echo "FAIL: no Node-version message on $("$old_node/node" -v)"; exit 1; }
  echo "ok   Node $("$old_node/node" -v) refused with a clear message"
else
  echo "skip no Node 18 install to check against"
fi
echo "PASS: AsyncAPI HTML on Node 26 verified"
