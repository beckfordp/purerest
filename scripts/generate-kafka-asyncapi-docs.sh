#!/usr/bin/env bash
set -euo pipefail

# Generates static AsyncAPI HTML docs for kafka-harness's registered topic —
# the AsyncAPI-world counterpart to this repo's Scaladoc generation (`sbt
# purerestlib/doc`), run on-demand rather than wired into CI/GitHub Pages
# (ADR 0010's AsyncAPI follow-up, Phase 5).
#
# Fetches the running spec from kafka-harness's own /docs/asyncapi.json
# route (added in Phase 4), saves it to disk, then runs the official
# @asyncapi/generator + @asyncapi/html-template (Node.js/npm, via npx) to
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

mkdir -p "$(dirname "$SPEC_FILE")"

echo "1. Fetching the running spec from ${KAFKA_HARNESS_URL}/docs/asyncapi.json..."
http_status="$(curl -s -o "$SPEC_FILE" -w '%{http_code}' "${KAFKA_HARNESS_URL}/docs/asyncapi.json")"
if [ "$http_status" != "200" ]; then
  echo "FAIL: expected HTTP 200 fetching the spec, got ${http_status}. Is kafka-harness running?" >&2
  exit 1
fi
echo "   Saved to ${SPEC_FILE}"

echo "2. Generating static HTML via @asyncapi/cli (generate fromTemplate) + @asyncapi/html-template..."
# Not `npx @asyncapi/generator` directly - that package dropped its own CLI
# bin in recent versions (confirmed via `npm view @asyncapi/generator bin`,
# empty) and is now a library consumed by @asyncapi/cli's `generate
# fromTemplate` subcommand instead.
#
# Not a bare `npx @asyncapi/cli` either, for two separate reasons found
# while building this script:
#   1. The current @asyncapi/cli major version requires Node >=24
#      (confirmed via `npm view @asyncapi/cli engines`); this machine's
#      Node is 18.16.0, so pinned to @asyncapi/cli@4.1.1 instead (the last
#      major supporting Node >12.16).
#   2. Even pinned to 4.1.1, `npx @asyncapi/cli@4.1.1 ...` reproducibly
#      failed with "asyncapi: command not found" - npx's own install
#      didn't create a node_modules/.bin/asyncapi symlink for this
#      package in this environment (confirmed by inspecting its npx cache
#      directory directly: the package and its bin/run_bin script were
#      both present, just no .bin symlink pointing at it). A plain `npm
#      install` into a local, repo-local cache (gitignored via the
#      existing target/ pattern) reliably creates that symlink instead,
#      so that's what this script does - install once, reuse the cached
#      binary on subsequent runs.
ASYNCAPI_CLI_DIR="target/asyncapi-cli-tool"
ASYNCAPI_BIN="${ASYNCAPI_CLI_DIR}/node_modules/.bin/asyncapi"
if [ ! -x "$ASYNCAPI_BIN" ]; then
  echo "   (first run — installing @asyncapi/cli@4.1.1 into ${ASYNCAPI_CLI_DIR}, cached for next time)"
  mkdir -p "$ASYNCAPI_CLI_DIR"
  npm install --no-save --prefix "$ASYNCAPI_CLI_DIR" @asyncapi/cli@4.1.1 >/dev/null
fi

# Clear contents without removing the directory itself (`rm -rf
# "$OUTPUT_DIR"` then recreating it breaks kafka-harness's live bind mount
# of this same path - OrbStack/Docker's virtiofs loses track of a bind
# mount when the host side deletes and recreates the mount point's inode;
# `--force-write` below handles overwriting the files that remain).
mkdir -p "$OUTPUT_DIR"
find "$OUTPUT_DIR" -mindepth 1 -delete
# @asyncapi/html-template pinned to 1.0.0, not "latest" - @asyncapi/cli@4.1.1
# bundles @asyncapi/generator 1.17.25, and every html-template release from
# 2.x onward requires generator >=2.0.0 (confirmed via `npm view
# @asyncapi/html-template@latest generator`, a real "Generator Error:
# This template is not compatible" failure, not a guess). 1.0.0 is the last
# 1.x release and explicitly declares generator '>=1.13.0 <2.0.0', which
# matches what's actually installed.
#
# html-template@1.0.0's package.json declares `generator.apiVersion: "v2"`,
# which makes @asyncapi/generator route the parsed document through
# @smoya/multi-parser's "v2" slot - an aliased, pinned
# @asyncapi/parser@3.0.0-next-major-spec.8 whose internal ParserAPIVersion
# constant is 2. The template's own bundled code separately does a plain
# `require('@asyncapi/parser')`, which resolves to whatever
# @asyncapi/parser generator actually has installed (3.6.6 here,
# ParserAPIVersion 3). Its `stringify()` checks the document's embedded
# version tag against ITS OWN constant (3) and silently returns `undefined`
# on a mismatch (2 !== 3) - no error, just a template that renders
# `const schema = undefined;` with an empty doc body (confirmed by patching
# apiVersion to "v3" and seeing the generated HTML pick up real content).
#
# Can't just pin a newer html-template, since 2.x+ needs generator >=2.0.0
# (see above). Can't pass apiVersion as a CLI flag either - it's only ever
# read from the template's own package.json. So: install the template once,
# copy it to a patched sibling with apiVersion forced to "v3", and always
# generate from that local copy (a filesystem path, not an npm specifier) -
# @asyncapi/generator skips the npm-install/version-check path entirely for
# local paths, so this local fix isn't clobbered by its "reinstall every
# run" behavior for registry-named templates (confirmed: the bare
# `@asyncapi/html-template@1.0.0` name gets its package.json silently
# overwritten back to "v2" on every single invocation).
GENERATOR_NODE_MODULES="${ASYNCAPI_CLI_DIR}/node_modules/@asyncapi/generator/node_modules"
HTML_TEMPLATE_ORIGINAL="${GENERATOR_NODE_MODULES}/@asyncapi/html-template"
HTML_TEMPLATE_PATCHED="${GENERATOR_NODE_MODULES}/@asyncapi/html-template-patched"
if [ ! -d "$HTML_TEMPLATE_PATCHED" ]; then
  if [ ! -d "$HTML_TEMPLATE_ORIGINAL" ]; then
    echo "   (installing @asyncapi/html-template@1.0.0 once, to patch it)"
    "$ASYNCAPI_BIN" generate fromTemplate "$SPEC_FILE" @asyncapi/html-template@1.0.0 --output "$(mktemp -d)" --force-write >/dev/null 2>&1 || true
  fi
  cp -R "$HTML_TEMPLATE_ORIGINAL" "$HTML_TEMPLATE_PATCHED"
  python3 -c "
import json
path = '${HTML_TEMPLATE_PATCHED}/package.json'
with open(path) as f:
    data = json.load(f)
data['generator']['apiVersion'] = 'v3'
with open(path, 'w') as f:
    json.dump(data, f, indent=2)
"
fi

"$ASYNCAPI_BIN" generate fromTemplate "$SPEC_FILE" "$HTML_TEMPLATE_PATCHED" --output "$OUTPUT_DIR" --force-write

echo "3. Done — static HTML at ${OUTPUT_DIR}/index.html"
