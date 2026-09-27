#!/usr/bin/env bash
set -euo pipefail

# Manual verification for Phase 1 of the scaladoc_20260925 track: confirms
# purerestlib's public API compiles and generates Scaladoc cleanly, and that
# the specific gaps found by this track's audit (see plan.md) are still
# documented — a regression check against those comments being removed later,
# not a general "every public symbol has a comment" scanner.
#
# Usage: ./scripts/verify-scaladoc-coverage.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SRC_DIR="modules/purerestlib/src/main/scala/purerest"
FAILED=0

echo "1. Compiling purerestlib and generating its Scaladoc..."
BUILD_LOG="$(mktemp -t verify-scaladoc-coverage-build)"
if sbt -batch "purerestlib/compile" "purerestlib/doc" >"$BUILD_LOG" 2>&1; then
  echo "   OK: compile + doc generation succeeded"
else
  echo "   FAIL: compile or doc generation failed — see $BUILD_LOG" >&2
  tail -40 "$BUILD_LOG" >&2
  FAILED=1
fi

echo
echo "2. Confirming the symbols found undocumented by this track's audit are documented..."
# Parallel arrays, not an associative array — the default bash on macOS (3.2)
# doesn't support `declare -A`.
files=(
  "client/HttpClient.scala"
  "docs/Docs.scala"
  "logging/Logging.scala"
  "metrics/Metrics.scala"
  "metrics/ClientMetrics.scala"
  "metrics/ServerMetrics.scala"
  "resilience/Resilience.scala"
  "resilience/Retry.scala"
  "resilience/CircuitBreaker.scala"
  "tracing/Tracing.scala"
  "tracing/ClientTracing.scala"
  "tracing/ServerTracing.scala"
)
symbols=(
  "object HttpClient"
  "def routes"
  "object Logging"
  "object Metrics"
  "object ClientMetrics"
  "object ServerMetrics"
  "final case class ResilienceConfig"
  "def isRetriableError"
  "def middleware"
  "object Tracing"
  "object ClientTracing"
  "object ServerTracing"
)

for i in "${!files[@]}"; do
  file="${files[$i]}"
  symbol="${symbols[$i]}"
  path="$SRC_DIR/$file"
  # The symbol's declaration line must be immediately preceded by a doc
  # comment's closing `*/` (allowing for blank lines from formatting).
  if grep -B1 -F "$symbol" "$path" | grep -q '\*/'; then
    echo "   OK: $file — '$symbol' is documented"
  else
    echo "   FAIL: $file — '$symbol' has no preceding Scaladoc comment" >&2
    FAILED=1
  fi
done

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
