#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

# Generates static AsyncAPI HTML docs for kafka-harness's registered topic —
# the AsyncAPI-world counterpart to this repo's Scaladoc generation (`sbt
# purerestlib/doc`), run on-demand rather than wired into CI/GitHub Pages
# (ADR 0010's AsyncAPI follow-up, Phase 5).
#
# Fetches the running spec from kafka-harness's own /docs/asyncapi.json
# route (added in Phase 4), saves it to disk, then runs the official
# @asyncapi/cli + @asyncapi/html-template (Node.js >= 24.11, npm) to
# render static HTML. kafka-harness must already be running — bring up
# the observability profile first:
#   docker compose --profile observability up -d kafka kafka-harness
#
# Usage: ./scripts/generate-kafka-asyncapi-docs.sh
# Env:   KAFKA_HARNESS_URL (default http://localhost:8085)
#        OUTPUT_DIR (default target/asyncapi-docs)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

KAFKA_HARNESS_URL="${KAFKA_HARNESS_URL:-http://localhost:8085}"
OUTPUT_DIR="${OUTPUT_DIR:-target/asyncapi-docs}"
SPEC_FILE="target/asyncapi-docs-spec/asyncapi.json"

# @asyncapi/generator 3.x (bundled by the cli below) needs Node >= 24.11.
node_version="$(node -v 2>/dev/null || echo none)"
if ! node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>24||(a===24&&b>=11)?0:1)' 2>/dev/null; then
  echo "FAIL: AsyncAPI HTML needs Node >= 24.11 (found ${node_version}); try 'nvm use 26'" >&2
  exit 1
fi

mkdir -p "$(dirname "$SPEC_FILE")"

echo "1. Fetching the running spec from ${KAFKA_HARNESS_URL}/docs/asyncapi.json..."
http_status="$(curl -s -o "$SPEC_FILE" -w '%{http_code}' "${KAFKA_HARNESS_URL}/docs/asyncapi.json")"
if [ "$http_status" != "200" ]; then
  echo "FAIL: expected HTTP 200 fetching the spec, got ${http_status}. Is kafka-harness running?" >&2
  exit 1
fi
echo "   Saved to ${SPEC_FILE}"

echo "2. Generating static HTML via @asyncapi/cli (generate fromTemplate) + @asyncapi/html-template..."
# Same versions, cache layout and Node guard as sbt-purekafka-docs'
# AsyncApiHtmlGenerator. @asyncapi/generator 3.x (bundled by cli 6.x) needs
# Node >= 24.11. Both packages go into one version-named cache so an older
# pin's cache is never reused, and the template renders from its local path:
# a registry-named template is re-installed on every run.
ASYNCAPI_CLI_VERSION="6.2.2"
HTML_TEMPLATE_VERSION="3.5.6"
TOOL_DIR="target/asyncapi-cli-tool/cli-${ASYNCAPI_CLI_VERSION}_html-${HTML_TEMPLATE_VERSION}"
ASYNCAPI_BIN="${TOOL_DIR}/node_modules/.bin/asyncapi"
HTML_TEMPLATE="${TOOL_DIR}/node_modules/@asyncapi/html-template"
if [ ! -x "$ASYNCAPI_BIN" ] || [ ! -d "$HTML_TEMPLATE" ]; then
  echo "   (first run — installing @asyncapi/cli@${ASYNCAPI_CLI_VERSION} + @asyncapi/html-template@${HTML_TEMPLATE_VERSION} into ${TOOL_DIR}, cached for next time)"
  mkdir -p "$TOOL_DIR"
  npm install --no-save --loglevel=error --prefix "$TOOL_DIR" "@asyncapi/cli@${ASYNCAPI_CLI_VERSION}" "@asyncapi/html-template@${HTML_TEMPLATE_VERSION}" >/dev/null
fi

# Clear contents without removing the directory itself (`rm -rf
# "$OUTPUT_DIR"` then recreating it breaks kafka-harness's live bind mount
# of this same path - OrbStack/Docker's virtiofs loses track of a bind
# mount when the host side deletes and recreates the mount point's inode;
# `--force-write` below handles overwriting the files that remain).
mkdir -p "$OUTPUT_DIR"
find "$OUTPUT_DIR" -mindepth 1 -delete

SUPPRESS_NO_CONFIG_WARNING=true "$ASYNCAPI_BIN" generate fromTemplate "$SPEC_FILE" "$HTML_TEMPLATE" --output "$OUTPUT_DIR" --force-write --no-interactive

echo "3. Done — static HTML at ${OUTPUT_DIR}/index.html"
